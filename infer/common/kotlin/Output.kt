package common

import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.ffmpeg.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class Output(val encoder: Encoder, val input: Flow<CPointer<AVPacket>?>) : AutoCloseable, suspend () -> Unit {
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    val main = newSingleThreadContext("Output")

    override fun close() = main.close()

    abstract class Context(
        val options: Array<Pair<String, String>>,
        val formatContext: CPointer<AVFormatContext>,
        var stream: CPointer<AVStream>?,
    ) : AutoCloseable {
        abstract fun initPb(startTimeRealtime: Long)
    }

    class ContextRtsp(url: String) : Context(options, initFormatContext(url), null) {
        companion object {
            val options = arrayOf("tune" to "zerolatency", "rtsp_transport" to "tcp")
            fun initFormatContext(url: String) = cPointer {
                avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
            }
        }

        override fun close() {
            avformat_free_context(formatContext)
        }

        override fun initPb(startTimeRealtime: Long) = Unit
    }

    class ContextFmp4(val id: String) : Context(options, initFormatContext(), null) {
        companion object {
            val options = arrayOf("movflags" to "frag_keyframe+empty_moov+default_base_moof")
            fun initFormatContext() = cPointer {
                avformat_alloc_output_context2(it, null, "mp4", "stream.mp4").check("avformat_alloc_output_context2")
            }
        }

        override fun close() {
            if (formatContext.pointed.pb != null) {
                avio_closep(cValuesOf(formatContext.pointed.pb))
                formatContext.pointed.pb = null
            }
            avformat_free_context(formatContext)
        }

        override fun initPb(startTimeRealtime: Long) {
            val time = Instant.fromEpochMilliseconds(startTimeRealtime).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
            formatContext.pointed.pb = cPointer { avio_open(it, "$id/${time}.mp4", AVIO_FLAG_WRITE).check("avio_open") }
        }
    }

    class ContextFmp4Stream(write: (CPointer<UByteVar>?, Int) -> Unit) :
        Context(options, initFormatContext(write), null) {

        companion object {
            val options = arrayOf(
                "movflags" to "frag_keyframe+empty_moov+default_base_moof",
                "fflags" to "nobuffer",
            )

            fun initFormatContext(write: (CPointer<UByteVar>?, Int) -> Unit) = cPointer {
                avformat_alloc_output_context2(it, null, "mp4", "stream.mp4")
                    .check("avformat_alloc_output_context2")
            }.apply {
                val bufferSize = 4096
                val avioBuffer = av_malloc(bufferSize.toULong())?.reinterpret<UByteVar>()
                    ?: throw Error("Failed to allocate avio buffer")
                val data = StableRef.create(write)
                pointed.pb = avio_alloc_context(
                    avioBuffer, bufferSize, 1, data.asCPointer(), null,
                    staticCFunction { rawData, buf, bufSize ->
                        rawData!!.asStableRef<(CPointer<UByteVar>?, Int) -> Int>().get()(buf!!, bufSize)
                    },
                    null,
                ) ?: throw RuntimeException("Failed to alloc avio context")
            }
        }

        override fun close() {
            if (formatContext.pointed.pb != null) {
                formatContext.pointed.pb!!.pointed.opaque!!.asStableRef<(CPointer<UByteVar>?, Int) -> Int>()
                    .apply { get()(null, 0) }
                    .dispose()
                av_free(formatContext.pointed.pb!!.pointed.buffer)
                av_free(formatContext.pointed.pb)
                formatContext.pointed.pb = null
            }
            avformat_free_context(formatContext)
        }

        override fun initPb(startTimeRealtime: Long) = Unit
    }

    val contexts = mutableSetOf<Context>()

    fun addRtsp(url: String) =
        ContextRtsp(url).also { CoroutineScope(main).launch { contexts.add(it) } }

    fun addFmp4(id: String) =
        ContextFmp4(id).also { CoroutineScope(main).launch { contexts.add(it) } }

    fun addFmp4Stream(write: (CPointer<UByteVar>?, Int) -> Unit) =
        ContextFmp4Stream(write).also { CoroutineScope(main).launch { contexts.add(it) } }

    fun remove(context: Context) = CoroutineScope(main).launch { context.also { contexts.remove(it) }.close() }

    override suspend fun invoke() {
        input.collect { packet ->
            withContext(main) {
                val toWrite = av_packet_alloc()!!
                contexts.forEach { context ->
                    if (context.stream == null && packet!!.pointed.flags.and(AV_PKT_FLAG_KEY) != 0) {
                        encoder.startTimeRealtime().let {
                            context.formatContext.pointed.start_time_realtime = it
                            context.initPb(it)
                        }
                        context.stream = encoder.initStream(context.formatContext.pointed)
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
                        av_write_frame(context.formatContext, toWrite).check("av_write_frame")
                        context.formatContext.pointed.pb?.let { avio_flush(it) }
                    }
                }
                av_packet_free(cValuesOf(toWrite))
                av_packet_unref(packet)
            }
        }
        withContext(main) {
            contexts.forEach { context ->
                av_write_frame(context.formatContext, null).check("av_write_frame")
                av_write_trailer(context.formatContext)
                context.close()
            }
        }
    }
}
