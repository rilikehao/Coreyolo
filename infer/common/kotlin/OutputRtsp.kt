package common

import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object OutputRtsp : suspend (Flow<OutputRtsp.Input>) -> Unit {
    class Context(url: String) : AutoCloseable {
        val formatCtx = cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }

        val packet = av_packet_alloc()!!

        var videoStream: CPointer<AVStream>? = null

        override fun close() {
            av_packet_free(cValuesOf(packet))
            avformat_free_context(formatCtx)
        }
    }

    data class Input(val ctx: Context, val packet: CPointer<AVPacket>)

    override suspend fun invoke(inputFlow: Flow<Input>) {
        val contexts = mutableSetOf<Context>()
        inputFlow.collect { input ->
            contexts.add(input.ctx)
            input.packet.pointed.stream_index = input.ctx.videoStream!!.pointed.index
            av_interleaved_write_frame(input.ctx.formatCtx, input.packet)
            av_packet_unref(input.packet)
        }
        contexts.forEach { if (it.videoStream != null) av_write_trailer(it.formatCtx) }
    }
}
