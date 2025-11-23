package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import platform.native.EpochMsFromTimeString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class InputFmp4(val id: String, val begin: Long, val end: Long, val fast: Boolean) : Input,
    suspend () -> Flow<CPointer<AVPacket>?> {

    val speed = if (fast) 3.0 else 1.0

    lateinit var stream: AVStream
    lateinit var formatContext: AVFormatContext

    override fun getStream() = stream
    override fun getFormatCtx() = formatContext

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        return SegmentWalker(id, begin.toTimeString())()
            .flatMapConcat { filename -> FrameReader(filename)() }
            .let { SmartFilter()(it) }
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
            // === 丢帧算法变量 (积分累加器模型) ===
            var credits = 0.0
            val earnPerFrame = 1.0 / speed
            val costPerFrame = 1.0
            // 债务下限：最多允许欠 1 帧的债。防止因连续参考帧导致积分过低，
            // 导致后续非参考帧长时间无法发送 (不均匀问题)
            val maxDebt = -1.0

            val filePath = "$id/$filename"
            val fileStartTime = EpochMsFromTimeString(filename.removeSuffix(".mp4"))
            val pkt = av_packet_alloc() ?: return@flow
            val formatCtx = cPointer { ptr ->
                avformat_open_input(ptr, filePath, null, null).check("avformat_open_input")
            }.also {
                formatContext = it.pointed
                formatContext.start_time_realtime = 0L
            }

            try {
                avformat_find_stream_info(formatCtx, null).check("avformat_find_stream_info")
                val vidIdx = av_find_best_stream(formatCtx, AVMEDIA_TYPE_VIDEO, -1, -1, null, 0)
                stream = formatCtx.pointed.streams!![vidIdx]!!.pointed
                val tb = stream.time_base

                var pts0 : Long? = null
                while (av_read_frame(formatCtx, pkt) >= 0) {
                    if (pkt.pointed.stream_index == vidIdx) {
                        // 1. 必须先锁定时间锚点 (防止时间吞噬)
                        if (pts0 == null) pts0 = pkt.pointed.pts

                        var shouldEmit = false

                        // 2. 积分计算
                        credits += earnPerFrame // 每输入一帧，赚取配额

                        if (!fast) {
                            shouldEmit = true
                        } else {
                            // 3. HEVC 参考帧判断 (强制保留)
                            if (isReference(pkt)) {
                                shouldEmit = true
                                credits -= costPerFrame
                                // 债务宽恕：如果欠债太多，强行拉回 maxDebt，既往不咎
                                if (credits < maxDebt) credits = maxDebt
                            } else {
                                // 4. 非参考帧 (仅在积分充足时发送)
                                if (credits >= 0.0) {
                                    shouldEmit = true
                                    credits -= costPerFrame
                                } else {
                                    shouldEmit = false
                                    // 丢弃时不扣积分，保留配额给下一帧
                                }
                            }
                        }

                        if (shouldEmit) {
                            val offsetTicks = fileStartTime * tb.den / tb.num / 1000
                            pkt.pointed.pts += offsetTicks - pts0
                            pkt.pointed.dts += offsetTicks - pts0
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

        /**
         * 兼容 H.264 和 H.265 的参考帧检测
         */
        fun isReference(pkt: CPointer<AVPacket>): Boolean {
            val ptr = pkt.pointed
            val data = ptr.data ?: return false
            val size = ptr.size

            // 获取 Codec ID 以区分逻辑
            val codecId = stream.codecpar!!.pointed.codec_id
            val isHevc = (codecId == AV_CODEC_ID_HEVC)

            var offset = 0
            while (offset + 4 < size) {
                // 读取 NALU 长度 (Big Endian)
                val len = ((data[offset].toInt() and 0xFF) shl 24) or
                        ((data[offset + 1].toInt() and 0xFF) shl 16) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)
                offset += 4
                if (offset >= size) break

                val headerByte = data[offset].toInt() and 0xFF

                if (isHevc) {
                    // === H.265 (HEVC) 逻辑 ===
                    // Header: F(1) Type(6) LayerId(6) TID(3)
                    val nalType = (headerByte shr 1) and 0x3F

                    // 32-34: VPS/SPS/PPS, 16-23: IRAP -> 必须保留
                    if (nalType in 32..34 || nalType in 16..23) return true
                    // 0-15: VCL, 奇数为参考(_R)
                    if (nalType < 16 && (nalType % 2 != 0)) return true

                } else {
                    // === H.264 (AVC) 逻辑 ===
                    // Header: F(1) NRI(2) Type(5)
                    val nri = (headerByte shr 5) and 0x03

                    // 只要 NRI != 0，就是参考帧 (包括 I/P/Ref-B/SPS/PPS)
                    // 这是 H.264 标准中最快速的判断方式
                    if (nri != 0) return true
                }

                offset += len
            }
            return false // 未发现任何参考 Slice
        }
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
                        val currentEpochMs = ptr.time_base.let { 1000 * ptr.pts * it.num / it.den }
                        val isKeyFrame = (ptr.flags and AV_PKT_FLAG_KEY) != 0
                        if (end <= currentEpochMs) throw FlowStopException()
                        if (begin <= currentEpochMs && isKeyFrame) hasStarted = true
                        if (hasStarted) emit(pkt)
                    }
                }
            } catch (_: FlowStopException) {
            }
        }
    }

    class FlowStopException : Exception()
}
