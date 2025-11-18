//package common
//
//import common.Utils.cPointer
//import common.Utils.check
//import kotlinx.cinterop.CPointer
//import kotlinx.cinterop.ExperimentalForeignApi
//import kotlinx.cinterop.StableRef
//import kotlinx.cinterop.UByteVar
//import kotlinx.cinterop.asStableRef
//import kotlinx.cinterop.pointed
//import kotlinx.cinterop.readBytes
//import kotlinx.cinterop.reinterpret
//import kotlinx.cinterop.staticCFunction
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.MutableSharedFlow
//import platform.ffmpeg.*
//import kotlin.time.ExperimentalTime
//
//@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
//class OutputWebM(val out: MutableSharedFlow<ByteArray>, val encoder: Encoder) : suspend (Flow<Command.CommandImage>) -> Unit {
//    override suspend fun invoke(input: Flow<Command.CommandImage>) {
//        val formatContext = cPointer {
//            avformat_alloc_output_context2(it, null, "webm", "stream.webm").check("avformat_alloc_output_context2")
//        }
//        val bufferSize = 4096
//        val avioBuffer = av_malloc(bufferSize.toULong())?.reinterpret<UByteVar>()
//            ?: throw Error("Failed to allocate avio buffer")
//        fun run(buf: CPointer<UByteVar>, bufSize: Int): Int {
//            out.tryEmit(buf.readBytes(bufSize))
//            return bufSize
//        }
//        val data = StableRef.create(::run)
//        try {
//            formatContext.pointed.pb = avio_alloc_context(
//                avioBuffer, bufferSize, 1, data.asCPointer(), null,
//                staticCFunction { rawData, buf, bufSize ->
//                    rawData!!.asStableRef<(CPointer<UByteVar>, Int) -> Int>().let { ref ->
//                        ref.get()(buf!!, bufSize)
//                    }
//                },
//                null,
//            ) ?: throw RuntimeException("Failed to alloc avio context")
//            formatContext.pointed.flags = formatContext.pointed.flags or AVFMT_FLAG_FLUSH_PACKETS
//            var begin = true
//            encoder.apply {
//                setFormatContext(formatContext.pointed)
//            }(input).collect { packet ->
//                if (begin) {
//                    begin = false
//                    avformat_write_header(formatContext, null).check("avformat_write_header")
//                }
//                av_interleaved_write_frame(formatContext, packet)
//                av_packet_unref(packet)
//                avio_flush(formatContext.pointed.pb)
//            }
//            av_write_trailer(formatContext)
//        } finally {
//            data.dispose()
//            av_free(formatContext.pointed.pb!!.pointed.buffer)
//            av_free(formatContext.pointed.pb)
//            avformat_free_context(formatContext)
//        }
//    }
//}
