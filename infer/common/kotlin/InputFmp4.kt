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

    val speed = if (fast) 4.0 else 1.0

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

                var pts0: Long? = null
                while (av_read_frame(formatCtx, pkt) >= 0) {
                    if (pkt.pointed.stream_index == vidIdx) {
                        // 1. 必须先锁定时间锚点 (防止时间吞噬)
                        if (pts0 == null) pts0 = pkt.pointed.pts

                        if (!fast || (pkt.pointed.flags.and(AV_PKT_FLAG_KEY) != 0)) {
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
    }

    inner class SmartFilter {
        operator fun invoke(upstream: Flow<CPointer<AVPacket>?>) = flow {
            var hasStarted = false
            try {
                upstream.collect { pkt ->
                    val ptr = pkt!!.pointed
                    val currentEpochMs = ptr.time_base.let { 1000 * ptr.pts * it.num / it.den }
                    if (hasStarted) {
                        if (end <= currentEpochMs) throw FlowStopException()
                        emit(pkt)
                    } else {
                        val isKeyFrame = (ptr.flags and AV_PKT_FLAG_KEY) != 0
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
