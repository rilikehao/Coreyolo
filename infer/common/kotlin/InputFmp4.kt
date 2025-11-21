package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.timeZone
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.*
import platform.ffmpeg.*
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class InputFmp4(val id: String, val begin: Double, val end: Double, val fast: Boolean) : Input,
    suspend () -> Flow<CPointer<AVPacket>?> {
    lateinit var stream: AVStream
    lateinit var formatContext: AVFormatContext

    override fun getStream() = stream
    override fun getFormatCtx() = formatContext

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        val startInstant = Instant.fromEpochMilliseconds((begin * 1000).toLong())
        val startCursor = startInstant.toLocalDateTime(timeZone).toString()
        return SegmentWalker(id, startCursor)()
            .flatMapConcat { filename -> FrameReader(filename)() }
            .let { SmartFilter()(it) }
            .let { SpeedLimiter(if (fast) 3.0 else 1.0)(it) }
    }

    class SegmentWalker(val dirPath: String, val startCursor: String) {
        operator fun invoke() = flow {
            var currentFile = findFile(Op.LE_MAX, startCursor) ?: findFile(Op.MIN_GLOBAL, "")
            emit(currentFile!!)
            while (true) {
                currentFile = findFile(Op.GT_MIN, currentFile!!)
                if (currentFile == null) break
                emit(currentFile)
            }
        }

        fun findFile(op: Op, pivot: String): String? {
            val dir = opendir(dirPath) ?: return null
            var res: String? = null
            try {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name == "." || name == ".." || !name.endsWith(".mp4")) continue

                    val update = when (op) {
                        Op.LE_MAX -> name <= pivot && (res == null || name > res)
                        Op.GT_MIN -> name > pivot && (res == null || name < res)
                        Op.MIN_GLOBAL -> res == null || name < res
                    }
                    if (update) res = name
                }
            } finally {
                closedir(dir)
            }
            return res
        }

        enum class Op { LE_MAX, GT_MIN, MIN_GLOBAL }
    }

    inner class FrameReader(private val filename: String) {
        operator fun invoke() = flow {
            val filePath = "$id/$filename"
            val fileStartTime = parseTime(filename)

            val pkt = av_packet_alloc() ?: return@flow
            val formatCtx = cPointer { ptr ->
                withOptions("fflags" to "nobuffer") {
                    avformat_open_input(ptr, filePath, null, it).check("avformat_open_input")
                }
            }.also { formatContext = it.pointed }
            try {
                avformat_find_stream_info(formatCtx, null).check("avformat_find_stream_info")
                val vidIdx = av_find_best_stream(formatCtx, AVMEDIA_TYPE_VIDEO, -1, -1, null, 0)
                stream = formatCtx.pointed.streams!![vidIdx]!!.pointed
                val tb = formatCtx.pointed.streams!![vidIdx]!!.pointed.time_base
                val timeBase = tb.num.toDouble() / tb.den.toDouble()
                val offsetTicks = (fileStartTime / timeBase).roundToLong()
                while (av_read_frame(formatCtx, pkt) >= 0) {
                    if (pkt.pointed.stream_index == vidIdx) {
                        if (fast && isBFrame(pkt)) {
                            av_packet_unref(pkt)
                            continue
                        }
                        pkt.pointed.pts += offsetTicks
                        pkt.pointed.dts += offsetTicks
                        pkt.pointed.time_base.num = tb.num
                        pkt.pointed.time_base.den = tb.den
                        emit(pkt)
                    }
                    av_packet_unref(pkt)
                }
            } finally {
                av_packet_free(cValuesOf(pkt))
                avformat_close_input(cValuesOf(formatCtx))
            }
        }

        fun isBFrame(pkt: CPointer<AVPacket>): Boolean {
            val size = pkt.pointed.size
            if (size < 5) return false
            val data = pkt.pointed.data ?: return false
            // AVCC 格式：4字节长度 + 1字节 NAL Header
            // NAL Header: [F(1) | NRI(2) | Type(5)]
            // 提取 NRI (nal_ref_idc)
            val nri = (data[4].toInt() shr 5) and 0x03
            return nri == 0 // NRI为0表示不被参考，可以安全丢弃（即 B 帧）
        }

        fun parseTime(name: String) = try {
            LocalDateTime.parse(name.removeSuffix(".mp4")).toInstant(timeZone).toEpochMilliseconds() / 1000.0
        } catch (_: Exception) {
            0.0
        }
    }

    inner class SmartFilter {
        operator fun invoke(upstream: Flow<CPointer<AVPacket>?>) = flow {
            var hasStarted = false

            try {
                upstream.collect { pkt ->
                    val ptr = pkt!!.pointed
                    val timeBase = ptr.time_base.let { it.num.toDouble() / it.den.toDouble() }
                    val currentTime = ptr.pts * timeBase

                    // 判定关键帧
                    val isKeyFrame = (ptr.flags and AV_PKT_FLAG_KEY) != 0

                    // 1. 结束逻辑：严格大于 end 即刻停止（不含当前帧）
                    if (!end.isInfinite() && end <= currentTime) {
                        throw FlowStopException()
                    }

                    if (hasStarted) {
                        // 已经开始，直接透传
                        emit(pkt)
                    } else {
                        // 2. 起播逻辑：时间达到 begin 且 必须是关键帧
                        if (begin <= currentTime && isKeyFrame) {
                            hasStarted = true
                            emit(pkt)
                        }
                        // 否则直接丢弃（什么都不做，上游 FrameReader 会自动 av_packet_unref）
                    }
                }
            } catch (_: FlowStopException) {
                // 正常退出
            }
        }
    }

    class SpeedLimiter(private val speed: Double) {
        operator fun invoke(upstream: Flow<CPointer<AVPacket>?>) = flow {
            var startSystemTime = 0L
            var startOriginalPts = 0L
            var isFirst = true

            upstream.collect { pkt ->
                val ptr = pkt!!
                val originalPts = ptr.pointed.pts
                val originalDts = ptr.pointed.dts // 获取原始 DTS

                val tb = ptr.pointed.time_base
                val timeBaseDouble = tb.num.toDouble() / tb.den.toDouble()

                if (isFirst) {
                    startOriginalPts = originalPts
                    startSystemTime = Clock.System.now().toEpochMilliseconds()

                    // 第一帧全部归零 (DTS 也必须相对 StartPTS 归零，保持相位)
                    ptr.pointed.pts = 0
                    ptr.pointed.dts = (originalDts - startOriginalPts).toLong() // 通常为 <= 0

                    isFirst = false
                } else {
                    // --- 你的神来之笔 (注意加括号) ---
                    // 统一计算，无需区分 fast/normal，自动保留了 PTS/DTS 的相对关系
                    val newPts = ((originalPts - startOriginalPts) / speed).toLong()
                    val newDts = ((originalDts - startOriginalPts) / speed).toLong()

                    ptr.pointed.pts = newPts
                    ptr.pointed.dts = newDts

                    // --- 限速逻辑 ---
                    val currentVideoTimeMs = newPts * timeBaseDouble * 1000
                    val systemElapsedMs = Clock.System.now().toEpochMilliseconds() - startSystemTime

                    val delayMs = (currentVideoTimeMs - systemElapsedMs).toLong()

                    if (delayMs > 0) {
                        delay(delayMs)
                    }
                }
                emit(pkt)
            }
        }
    }

    class FlowStopException : Exception()
}
