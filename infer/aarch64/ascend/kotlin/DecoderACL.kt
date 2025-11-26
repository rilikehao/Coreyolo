import cnames.structs.DecoderProcess
import co.touchlab.kermit.Logger
import common.Command
import common.Decoder
import common.Input
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import platform.ffmpeg.*
import platform.native.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
open class DecoderACL(
    val device: Int,
    val input: Input,
    val packets: Flow<CPointer<AVPacket>?>,
) : Decoder {
    companion object {
        const val REORDER_SIZE = 5
    }

    override suspend fun invoke(): Flow<Command.CommandImage> {
        var width = 0
        var height = 0
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val decoderProcess = CompletableDeferred<CPointer<DecoderProcess>>()
        var swsCtx: CPointer<SwsContext>? = null
        val reorder = mutableSetOf<Command.CommandImage>()
        fun pop() = reorder.minBy { it.timestamp }.also { reorder.remove(it) }
        return flow {
            coroutineScope {
                launch {
                    packets.collect { packet ->
                        if (swsCtx == null) {
                            val codecpar = input.getStream().codecpar!!
                            width = codecpar.pointed.width
                            height = codecpar.pointed.height
                            val decodeType =
                                avcodec_find_decoder(codecpar.pointed.codec_id)!!.pointed.name!!.toKString()
                            decoderProcess.complete(StartDecoder(device, decodeType, width, height)!!)
                            swsCtx = sws_getContext(
                                width, height, AV_PIX_FMT_NV12,
                                width, height, AV_PIX_FMT_RGB24,
                                SWS_BILINEAR.toInt(), null, null, null,
                            )
                        }
                        val tb = input.getStream().time_base
                        val start = Instant.fromEpochMilliseconds(input.getFormatCtx().start_time_realtime / 1000L)
                        val timestamp = start + (1000 * packet!!.pointed.pts * tb.num / tb.den).milliseconds
                        if (inputFrames == 0L) timestamp0 = timestamp
                        val keep = inputFrames <= Decoder.maxFrames(timestamp - timestamp0)
                        if (keep) ++inputFrames
                        DecoderW(decoderProcess.await(), cValue {
                            timestamp_ = timestamp.toEpochMilliseconds()
                            data_ = packet.pointed.data!!.reinterpret()
                            size_ = packet.pointed.size.toLong()
                        })
                        av_packet_unref(packet)
                    }
                    DecoderW(decoderProcess.await(), cValue {
                        timestamp_ = 0
                        data_ = null
                        size_ = 0
                    })
                }
                memScoped {
                    while (true) {
                        val io = alloc<DecoderIO>().also { it.timestamp_ = 0L }
                        DecoderR(decoderProcess.await(), io.ptr)
                        if (io.timestamp_ == 0L) break
                        val timestamp = Instant.fromEpochMilliseconds(io.timestamp_)
                        try {
                            CreateImageRGB24(width, height)!!.also { image ->
                                sws_scale(
                                    swsCtx,
                                    cValuesOf(
                                        io.data_!!.reinterpret(),
                                        io.data_!!.reinterpret<UByteVar>() + height * width
                                    ),
                                    cValuesOf(width, width),
                                    0, height,
                                    cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)),
                                )
                            }.let { emit(Command.CommandImage(timestamp, it)) }
                        } finally {
                            DestroyDecoderIO(io.ptr)
                        }
                    }
                }
            }
        }.onCompletion {
            if (swsCtx != null) {
                sws_freeContext(swsCtx)
                StopDecoder(decoderProcess.await())
            }
        }.transform { frame ->
            Logger.i { frame.timestamp.toTimeString() }
            reorder.add(frame)
            while (REORDER_SIZE < reorder.size) emit(pop())
        }.onCompletion {
            while (!reorder.isEmpty()) emit(pop())
        }.buffer(Channel.UNLIMITED)
    }
}
