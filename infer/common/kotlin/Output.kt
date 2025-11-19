package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readValue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class Output(val encoder: Encoder) : AutoCloseable, suspend (Flow<Command.CommandImage>) -> Unit {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val main = newSingleThreadContext("OutputRtsp")

    override fun close() = main.close()

    class Context(
        val options: Array<Pair<String, String>>,
        val formatContext: CPointer<AVFormatContext>,
        var stream: CPointer<AVStream>?,
    )

    val contexts = mutableListOf<Context>()

    suspend fun addRtsp(url: String) = withContext(main) {
        val formatContext = cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }
        contexts.add(Context(arrayOf("tune" to "zerolatency", "rtsp_transport" to "tcp"), formatContext, null))
    }

    suspend fun addMatroska(path: String) = withContext(main) {
        val formatContext = cPointer {
            avformat_alloc_output_context2(it, null, "matroska", "stream.mkv").check("avformat_alloc_output_context2")
        }
        formatContext.pointed.pb = cPointer { avio_open(it, path, AVIO_FLAG_WRITE).check("avio_open") }
        contexts.add(Context(arrayOf(), formatContext, null))
    }

    override suspend fun invoke(input: Flow<Command.CommandImage>) {
        encoder(input).collect { packet ->
            withContext(main) {
                val toWrite = av_packet_alloc()!!
                contexts.forEach { context ->
                    if (context.stream == null && packet.pointed.flags.and(AV_PKT_FLAG_KEY) != 0) {
                        context.stream = encoder.initStream(context.formatContext.pointed)
                        context.formatContext.pointed.start_time_realtime = encoder.startTimeRealtime()
                        withOptions(*context.options) {
                            avformat_write_header(context.formatContext, it).check("avformat_write_header")
                        }
                    }
                    if (context.stream != null) {
                        av_packet_ref(toWrite, packet).check("av_packet_ref")
                        toWrite.pointed.stream_index = context.stream!!.pointed.index
                        av_packet_rescale_ts(
                            toWrite,
                            cValue { num = 1; den = 90000 },
                            context.stream!!.pointed.time_base.readValue(),
                        )
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
                avio_closep(cValuesOf(context.formatContext.pointed.pb))
                context.formatContext.pointed.pb = null
                avformat_free_context(context.formatContext)
            }
        }
    }
}
