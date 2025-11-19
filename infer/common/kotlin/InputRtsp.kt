package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class InputRtsp(val url: String) : suspend () -> Flow<CPointer<AVPacket>?> {
    lateinit var stream: AVStream
    lateinit var formatCtx: AVFormatContext

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        val formatContext = cPointer { ptr ->
            withOptions("fflags" to "nobuffer", "rtsp_transport" to "tcp") {
                avformat_open_input(ptr, url, null, it).check("avformat_open_input")
            }
        }
        avformat_find_stream_info(formatContext, null).check("avformat_find_stream_info")
        val videoStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, null, 0)
        val packet = av_packet_alloc()!!
        stream = formatContext.pointed.streams!![videoStreamIndex]!!.pointed
        formatCtx = formatContext.pointed
        return flow {
            while (av_read_frame(formatContext, packet) != AVERROR_EOF) {
                if (packet.pointed.stream_index == videoStreamIndex) emit(packet as CPointer<AVPacket>?)
            }
        }.onCompletion {
            avformat_close_input(cValuesOf(formatContext))
            av_packet_free(cValuesOf(packet))
        }
    }
}
