package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class OutputRtsp(val encoder: Encoder) : AutoCloseable, suspend (Flow<Command.CommandImage>) -> Unit {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val main = newSingleThreadContext("OutputRtsp")

    override fun close() = main.close()

    data class Context(val formatContext: CPointer<AVFormatContext>, var stream: CPointer<AVStream>?)

    val contexts = mutableListOf<Context>()

    suspend fun add(url: String) = withContext(main) {
        cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }.let { contexts.add(Context(it, null)) }
    }

    override suspend fun invoke(input: Flow<Command.CommandImage>) {
        encoder(input).collect { packet ->
            withContext(main) {
                val toWrite = av_packet_alloc()!!
                contexts.forEach { context ->
                    if (context.stream == null && packet.pointed.flags.and(AV_PKT_FLAG_KEY) != 0) {
                        context.stream = encoder.initStream(context.formatContext.pointed)
                        context.formatContext.pointed.start_time_realtime = packet.pointed.pts / 90L * 1000L
                        withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                            avformat_write_header(context.formatContext, it).check("avformat_write_header")
                        }
                    }
                    if (context.stream != null) {
                        av_packet_ref(toWrite, packet).check("av_packet_ref")
                        toWrite.pointed.stream_index = context.stream!!.pointed.index
                        av_interleaved_write_frame(context.formatContext, toWrite).check("av_interleaved_write_frame")
                    }
                }
                av_packet_free(cValuesOf(toWrite))
                av_packet_unref(packet)
            }
        }
        withContext(main) {
            contexts.forEach { context ->
                av_write_trailer(context.formatContext)
                avformat_free_context(context.formatContext)
            }
        }
    }
}
