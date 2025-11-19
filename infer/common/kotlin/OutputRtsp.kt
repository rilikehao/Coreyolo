package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class OutputRtsp(val urls: Array<String>, val encoder: Encoder) : suspend (Flow<Command.CommandImage>) -> Unit {
    data class Context(val formatContext: CPointer<AVFormatContext>, var stream: CPointer<AVStream>?)

    override suspend fun invoke(input: Flow<Command.CommandImage>) {
        val contexts = urls.map { url ->
            cPointer {
                avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
            }.let { Context(it, null) }
        }
        var begin = true
        encoder(input).collect { packet ->
            val toWrite = av_packet_alloc()!!
            contexts.forEach { context ->
                if (begin) {
                    context.formatContext.pointed.start_time_realtime = packet.pointed.pts / 90L * 1000L
                    context.stream = encoder.initStream(context.formatContext.pointed)
                    withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                        avformat_write_header(context.formatContext, it).check("avformat_write_header")
                    }
                }
                av_packet_ref(toWrite, packet).check("av_packet_ref")
                toWrite.pointed.stream_index = context.stream!!.pointed.index
                av_interleaved_write_frame(context.formatContext, toWrite).check("av_interleaved_write_frame")
            }
            av_packet_free(cValuesOf(toWrite))
            av_packet_unref(packet)
            begin = false
        }
        contexts.forEach { context ->
            av_write_trailer(context.formatContext)
            avformat_free_context(context.formatContext)
        }
    }
}
