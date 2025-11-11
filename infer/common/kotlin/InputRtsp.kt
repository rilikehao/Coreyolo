package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import platform.ffmpeg.*
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object InputRtsp : (String) -> InputRtsp.Output {
    data class Output(val formatCtx: AVFormatContext, val stream: AVStream, val packets: Flow<CPointer<AVPacket>?>)

    override fun invoke(url: String): Output {
        val formatCtx = cPointer { ptr ->
            withOptions("fflags" to "nobuffer", "rtsp_transport" to "tcp") {
                avformat_open_input(ptr, url, null, it).check("avformat_open_input")
            }
        }
        avformat_find_stream_info(formatCtx, null).check("avformat_find_stream_info")
        val videoStreamIndex = av_find_best_stream(formatCtx, AVMEDIA_TYPE_VIDEO, -1, -1, null, 0)
        val packet = av_packet_alloc()!!
        val packets = flow {
            while (av_read_frame(formatCtx, packet) != AVERROR_EOF) {
                if (packet.pointed.stream_index == videoStreamIndex) emit(packet as CPointer<AVPacket>?)
            }
        }.onCompletion {
            avformat_close_input(cValuesOf(formatCtx))
            emit(null)
        }.onCompletion {
            av_packet_free(cValuesOf(packet))
        }
        return Output(formatCtx.pointed, formatCtx.pointed.streams!![videoStreamIndex]!!.pointed, packets)
    }

    fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsDecode).inWholeSeconds
}
