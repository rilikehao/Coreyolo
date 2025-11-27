import cnames.structs.DecoderProcess
import common.Command
import common.Decoder
import common.Input
import common.Utils.cPointer
import common.Utils.check
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
        const val ID_SIZE = 16
        val availableIds = Channel<Int>(capacity = ID_SIZE).apply { repeat(ID_SIZE) { trySend(it) } }
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
        val id = availableIds.receive()
        
        var bsfCtx: CPointer<AVBSFContext>? = null
        val filterPacket = av_packet_alloc()!!
        
        return flow {
            coroutineScope {
                launch {
                    packets.collect { packet ->
                        if (swsCtx == null) {
                            val codecpar = input.getStream().codecpar!!
                            width = codecpar.pointed.width
                            height = codecpar.pointed.height
                            val codecId = codecpar.pointed.codec_id
                            val decodeType = avcodec_find_decoder(codecId)!!.pointed.name!!.toKString()
                            val bsfName = when (codecId) {
                                AV_CODEC_ID_H264 -> "h264_mp4toannexb"
                                AV_CODEC_ID_HEVC -> "hevc_mp4toannexb"
                                else -> throw Error("未知的视频流")
                            }
                            val bsf = av_bsf_get_by_name(bsfName)!!
                            bsfCtx = cPointer { av_bsf_alloc(bsf, it).check("av_bsf_alloc") }
                            avcodec_parameters_copy(bsfCtx.pointed.par_in, codecpar).check("avcodec_parameters_copy")
                            av_bsf_init(bsfCtx).check("av_bsf_init")
                            decoderProcess.complete(StartDecoder(device, id, decodeType, width, height)!!)
                            swsCtx = sws_getContext(
                                width, height, AV_PIX_FMT_NV12,
                                width, height, AV_PIX_FMT_RGB24,
                                SWS_BILINEAR.toInt(), null, null, null,
                            )
                        }
                        av_bsf_send_packet(bsfCtx, packet).check("av_bsf_send_packet")
                        while (av_bsf_receive_packet(bsfCtx, filterPacket) == 0) {
                            val tb = input.getStream().time_base
                            val start = Instant.fromEpochMilliseconds(input.getFormatCtx().start_time_realtime / 1000L)
                            val timestamp = start + (1000 * filterPacket.pointed.pts * tb.num / tb.den).milliseconds
                            if (inputFrames == 0L) timestamp0 = timestamp
                            val keep = inputFrames <= Decoder.maxFrames(timestamp - timestamp0)
                            if (keep) ++inputFrames
                            DecoderW(decoderProcess.await(), cValue {
                                timestamp_ = if (keep) timestamp.toEpochMilliseconds() else -1
                                data_ = filterPacket.pointed.data!!.reinterpret()
                                size_ = filterPacket.pointed.size.toLong()
                            })
                            av_packet_unref(filterPacket)
                        }
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
                        try {
                            if (io.timestamp_ == 0L) break
                            if (io.timestamp_ == -1L) continue
                            val timestamp = Instant.fromEpochMilliseconds(io.timestamp_)
                            val wstride = (width + 15) / 16 * 16
                            val hstride = (height + 1) / 2 * 2
                            CreateImageRGB24(width, height)!!.also { image ->
                                sws_scale(
                                    swsCtx,
                                    cValuesOf(
                                        io.data_!!.reinterpret(),
                                        io.data_!!.reinterpret<UByteVar>() + wstride * hstride
                                    ),
                                    cValuesOf(wstride, wstride),
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
            if (bsfCtx != null) av_bsf_free(cValuesOf(bsfCtx))
            av_packet_free(cValuesOf(filterPacket))
            if (swsCtx != null) {
                sws_freeContext(swsCtx)
                StopDecoder(decoderProcess.getCompleted())
                availableIds.trySend(id)
            }
        }.transform { frame ->
            reorder.add(frame)
            while (REORDER_SIZE < reorder.size) emit(pop())
        }.onCompletion { cause ->
            if (cause == null) {
                while (!reorder.isEmpty()) emit(pop())
            }
        }.buffer(Channel.UNLIMITED)
    }
}
