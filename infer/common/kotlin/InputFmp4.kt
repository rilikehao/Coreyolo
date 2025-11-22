package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.timeZone
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.ffmpeg.*
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class InputFmp4(val id: String, val begin: Double, val end: Double, val fast: Boolean) : Input,
    suspend () -> Flow<CPointer<AVPacket>?> {

    val speed = if (fast) 3.0 else 1.0

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
            .let { SpeedLimiter()(it) }
    }

    class SegmentWalker(val dirPath: String, val startCursor: String) {
        operator fun invoke() = flow {
            var currentFile = findFile(Op.LE_MAX, startCursor) ?: findFile(Op.MIN_GLOBAL, "") ?: return@flow
            emit(currentFile)
            while (true) {
                currentFile = findFile(Op.GT_MIN, currentFile) ?: break
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
            var pts0: Long? = null
            var inputCount = 0L
            var outputCount = 0L
            val filePath = "$id/$filename"
            val fileStartTime = parseTime(filename)
            val pkt = av_packet_alloc() ?: return@flow
            val formatCtx = cPointer { ptr ->
                avformat_open_input(ptr, filePath, null, null).check("avformat_open_input")
            }.also { formatContext = it.pointed }
            try {
                avformat_find_stream_info(formatCtx, null).check("avformat_find_stream_info")
                val vidIdx = av_find_best_stream(formatCtx, AVMEDIA_TYPE_VIDEO, -1, -1, null, 0)
                stream = formatCtx.pointed.streams!![vidIdx]!!.pointed
                val tb = stream.time_base
                val timeBase = tb.num.toDouble() / tb.den.toDouble()
                while (av_read_frame(formatCtx, pkt) >= 0) {
                    if (pkt.pointed.stream_index == vidIdx) {
                        if (pts0 == null) pts0 = pkt.pointed.pts
                        inputCount++
                        if (!fast || isReference(pkt) || outputCount < inputCount / speed) {
                            outputCount++
                            val offsetTicks = (fileStartTime / timeBase).roundToLong() - pts0
                            pkt.pointed.pts += offsetTicks
                            pkt.pointed.dts += offsetTicks
                            pkt.pointed.time_base.num = tb.num
                            pkt.pointed.time_base.den = tb.den
                            emit(pkt)
                        }
                    }
                    av_packet_unref(pkt)
                }
            } finally {
                av_packet_free(cValuesOf(pkt))
                avformat_close_input(cValuesOf(formatCtx))
            }
        }

        fun isReference(pkt: CPointer<AVPacket>): Boolean {
            val ptr = pkt.pointed
            val data = ptr.data ?: return false
            val size = ptr.size
            var offset = 0
            var hasReferenceSlice = false
            while (offset + 4 < size) {
                val len = ((data[offset].toInt() and 0xFF) shl 24) or
                        ((data[offset + 1].toInt() and 0xFF) shl 16) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)
                offset += 4
                if (offset >= size) break

                val header = data[offset].toInt() and 0xFF
                val type = header and 0x1F
                val nri = (header shr 5) and 0x03

                // Type 1 (Slice) 或 Type 5 (IDR)
                if (type == 1 || type == 5) {
                    if (nri != 0) {
                        hasReferenceSlice = true
                        break
                    }
                }
                offset += len
            }
            return hasReferenceSlice
        }

        fun parseTime(name: String) =
            LocalDateTime.parse(name.removeSuffix(".mp4")).toInstant(timeZone).toEpochMilliseconds() / 1000.0
    }

    inner class SmartFilter {
        operator fun invoke(upstream: Flow<CPointer<AVPacket>?>) = flow {
            var hasStarted = false
            try {
                upstream.collect { pkt ->
                    if (hasStarted) {
                        emit(pkt)
                    } else {
                        val ptr = pkt!!.pointed
                        val timeBase = ptr.time_base.let { it.num.toDouble() / it.den.toDouble() }
                        val currentTime = ptr.pts * timeBase
                        val isKeyFrame = (ptr.flags and AV_PKT_FLAG_KEY) != 0
                        if (!end.isInfinite() && end <= currentTime) throw FlowStopException()
                        if (begin <= currentTime && isKeyFrame) hasStarted = true
                        if (hasStarted) emit(pkt)
                    }
                }
            } catch (_: FlowStopException) {
            }
        }
    }

    inner class SpeedLimiter {
        operator fun invoke(upstream: Flow<CPointer<AVPacket>?>) = flow {
            var startSystemTime = 0L
            var startOriginalDts = 0L
            var lastOriginalDts = 0L
            var totalSkippedTicks = 0L
            var isFirst = true
            upstream.collect { pkt ->
                val ptr = pkt!!.pointed
                val originalPts = ptr.pts
                val originalDts = ptr.dts
                val tb = ptr.time_base
                val timeBaseDouble = tb.num.toDouble() / tb.den.toDouble()
                if (isFirst) {
                    startOriginalDts = originalDts
                    lastOriginalDts = originalDts
                    totalSkippedTicks = 0L
                    // 计算第一帧的新 PTS (保留首帧可能存在的 PTS-DTS 延迟)
                    // 公式：(OrigPTS - StartDTS - Skipped) / Speed
                    val initialDelay = originalPts - originalDts
                    val newPts = (initialDelay / speed).roundToLong()
                    ptr.pts = newPts
                    ptr.dts = 0 // 首帧 DTS 归零
                    // 初始化系统时钟，并扣除首帧的 Presentation Delay，确保画面立刻播放
                    // 否则带有延迟的 B 帧流可能会在第一帧这就 Sleep
                    startSystemTime =
                        Clock.System.now().toEpochMilliseconds() - (newPts * timeBaseDouble * 1000).toLong()
                    isFirst = false
                } else {
                    // 1. 使用 DTS (解码序) 计算物理间隔，这在 B 帧存在时依然是单调递增的
                    val dtsDelta = originalDts - lastOriginalDts
                    val dtsDeltaSeconds = dtsDelta * timeBaseDouble
                    // 2. 检测大间隔 (> 1.0s)
                    if (dtsDeltaSeconds > 1.0) {
                        // 计算目标间隔 (1.0s) 对应的 ticks
                        val targetGapTicks = (1.0 / timeBaseDouble).roundToLong()
                        // 计算多余的部分：实际间隔 - 1.0s
                        // 这些多余的 ticks 将被永远“切除”
                        val excessTicks = dtsDelta - targetGapTicks
                        totalSkippedTicks += excessTicks
                    }
                    // 3. 核心公式：绝对位置计算 (无累计误差)
                    // 这里的关键是 PTS 和 DTS 都减去同一个 totalSkippedTicks 和 startOriginalDts
                    // 从而完美保持了 (PTS - DTS) 的相对关系
                    val effectivePtsTicks = originalPts - startOriginalDts - totalSkippedTicks
                    val effectiveDtsTicks = originalDts - startOriginalDts - totalSkippedTicks
                    val newPts = (effectivePtsTicks / speed).roundToLong()
                    val newDts = (effectiveDtsTicks / speed).roundToLong()
                    ptr.pts = newPts
                    ptr.dts = newDts
                    lastOriginalDts = originalDts
                    // 4. 控速逻辑 (基于显示时间 PTS)
                    val currentVideoTimeMs = newPts * timeBaseDouble * 1000
                    val systemElapsedMs = Clock.System.now().toEpochMilliseconds() - startSystemTime
                    val delayMs = (currentVideoTimeMs - systemElapsedMs).toLong()
                    if (delayMs > 0) delay(delayMs)
                }
                emit(pkt)
            }
        }
    }

    class FlowStopException : Exception()
}
