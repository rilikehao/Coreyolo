package common

import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class OutputRtsp(val url: String, val encoder: Encoder) : suspend (Flow<Command.CommandImage>) -> Unit {
    override suspend fun invoke(input: Flow<Command.CommandImage>) {
        val formatContext = cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }
        encoder.apply {
            setFormatContext(formatContext)
        }(input).collect { packet ->
            av_interleaved_write_frame(formatContext, packet)
            av_packet_unref(packet)
        }
        av_write_trailer(formatContext)
        avformat_free_context(formatContext)
    }
}
