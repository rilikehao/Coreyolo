import cnames.structs.EncoderProcess
import co.touchlab.kermit.Logger
import common.Command
import common.Encoder
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import common.Utils.roundToEpochMs
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import platform.ffmpeg.*
import platform.native.*
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
open class EncoderACL(
    val id: String,
    val suggestedName: CompletableDeferred<String>?,
    val input: Flow<Command.CommandImage>,
    val encodeType: String,
) : Encoder {
    companion object {
        const val ID_SIZE = 16
        val availableIds = Channel<Int>(capacity = ID_SIZE).apply { repeat(ID_SIZE) { trySend(it) } }
    }

    val encoderProcess = CompletableDeferred<CPointer<EncoderProcess>>()

    private var swsCtx: CPointer<SwsContext>? = null
    private var width = 0
    private var height = 0
    private var videoStream: CPointer<AVStream>? = null
    private var bsfContext: CPointer<AVBSFContext>? = null
    private var codec: CPointer<AVCodec>? = null

    lateinit var timestamp0: Instant

    override fun startTimeRealtime() = timestamp0.roundToEpochMs()

    override fun initStream(formatContext: AVFormatContext): CPointer<AVStream> {
        return avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_copy(videoStream!!.pointed.codecpar, bsfContext!!.pointed.par_out)
        }
    }

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        val id = availableIds.receive()

        return flow {
            coroutineScope {
                launch {
                    input.collect { commandImage ->
                        if (inputFrames++ == 0L) {
                            timestamp0 = commandImage.timestamp
                            val name = timestamp0.toTimeString()
                            suggestedName?.complete(name)
                            width = GetWidth(commandImage.data)
                            height = GetHeight(commandImage.data)
                            encoderProcess.complete(StartEncoder(0, id, encodeType, width, height)!!)
                            swsCtx = sws_getContext(
                                width, height, AV_PIX_FMT_RGB24,
                                width, height, AV_PIX_FMT_NV12,
                                SWS_BILINEAR.toInt(), null, null, null,
                            )
                            codec = when (encodeType) {
                                "h264" -> AV_CODEC_ID_H264
                                "hevc" -> AV_CODEC_ID_HEVC
                                else -> throw Error("不支持的视频编码")
                            }.let { avcodec_find_encoder(it) }
                        }
                        Logger.i {
                            if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                            val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                            val delayed = frame0.elapsedNow() - (commandImage.timestamp - timestamp0)
                            val delayMs = max(0L, delayed.inWholeMilliseconds)
                            "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
                        }
                        val image = commandImage.data
                        val timestamp = commandImage.timestamp
                        val wstride = (width + 15) / 16 * 16
                        val hstride = (height + 1) / 2 * 2
                        val nv12Size = wstride * hstride * 3 / 2
                        memScoped {
                            val nv12 = allocArray<UByteVar>(nv12Size)
                            sws_scale(
                                swsCtx,
                                cValuesOf(Bits(image)),
                                cValuesOf(BytesPerLine(image)),
                                0, height,
                                cValuesOf(nv12, nv12 + wstride * hstride),
                                cValuesOf(wstride, wstride),
                            )
                            EncoderW(encoderProcess.await(), cValue {
                                timestamp_ = timestamp.toEpochMilliseconds()
                                is_key_frame_ = inputFrames % 12L == 0L
                                size_ = nv12Size.toLong()
                                data_ = nv12.reinterpret()
                            })
                        }
                    }
                    EncoderW(encoderProcess.await(), cValue {
                        timestamp_ = 0
                        is_key_frame_ = false
                        size_ = 0
                        data_ = null
                    })
                }
                while (true) {
                    val packet = av_packet_alloc()!!
                    val timestamp = memScoped {
                        val io = alloc<EncoderIO>().also { it.timestamp_ = 0L }
                        EncoderR0(encoderProcess.await(), io.ptr)
                        if (io.timestamp_ == 0L) break
                        av_new_packet(packet, io.size_.toInt()).check("av_new_packet")
                        io.data_ = packet.pointed.data!!.reinterpret()
                        EncoderR1(encoderProcess.await(), io.ptr)
                        if (io.is_key_frame_) {
                            packet.pointed.flags = packet.pointed.flags.or(AV_PKT_FLAG_KEY)
                        }
                        Instant.fromEpochMilliseconds(io.timestamp_)
                    }
                    if (bsfContext == null) setupBSF(packet)
                    val pts = (timestamp - timestamp0).inWholeMicroseconds * 90 / 1000
                    packet.pointed.pts = pts
                    packet.pointed.dts = pts
                    emit(packet as CPointer<AVPacket>?)
                }
            }
        }.onCompletion {
            if (swsCtx != null) sws_freeContext(swsCtx)
            if (bsfContext != null) av_bsf_free(cValuesOf(bsfContext))
            StopEncoder(encoderProcess.getCompleted())
            availableIds.trySend(id)
        }
    }

    private fun setupBSF(packet: CPointer<AVPacket>) {
        val bsf = av_bsf_get_by_name("extract_extradata")
            ?: throw Error("av_bsf_get_by_name failed")
        bsfContext = cPointer { av_bsf_alloc(bsf, it).check("av_bsf_alloc") }
        bsfContext!!.pointed.par_in!!.pointed.let {
            it.codec_type = AVMEDIA_TYPE_VIDEO
            it.codec_id = codec!!.pointed.id
            it.codec_tag = 0U
            it.width = width
            it.height = height
            it.format = AV_PIX_FMT_YUV420P
        }
        av_bsf_init(bsfContext).check("av_bsf_init")
        av_bsf_send_packet(bsfContext, packet).check("av_bsf_send_packet")
        val outputPacket = av_packet_alloc()!!
        av_bsf_receive_packet(bsfContext, outputPacket).check("av_bsf_receive_packet")
        av_packet_move_ref(packet, outputPacket)
        av_packet_free(cValuesOf(outputPacket))
    }
}
