import cnames.structs.EncoderProcess
import co.touchlab.kermit.Logger
import common.Command
import common.Encoder
import common.StringFormat.toString
import common.Utils.check
import common.Utils.roundToEpochMs
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import platform.ffmpeg.*
import platform.native.*
import platform.posix.memcpy
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
    val encoderProcess = CompletableDeferred<CPointer<EncoderProcess>>()

    private var swsCtx: CPointer<SwsContext>? = null
    private var width = 0
    private var height = 0
    private var codecpar: CPointer<AVCodecParameters>? = null
    private var codec: CPointer<AVCodec>? = null

    lateinit var timestamp0: Instant

    override fun startTimeRealtime() = timestamp0.roundToEpochMs()

    override fun initStream(formatContext: AVFormatContext): CPointer<AVStream> {
        return avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_copy(it.pointed.codecpar, codecpar)
        }
    }

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()

        return flow {
            coroutineScope {
                launch {
                    input.collect { commandImage ->
                        if (inputFrames == 0L) {
                            timestamp0 = commandImage.timestamp
                            val name = timestamp0.toTimeString()
                            suggestedName?.complete(name)
                            width = GetWidth(commandImage.data)
                            height = GetHeight(commandImage.data)
                            encoderProcess.complete(StartEncoder(0, 0, encodeType, width, height)!!)
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
                        val wstride = (width + 15) / 16 * 16
                        val hstride = (height + 1) / 2 * 2
                        val nv12Size = wstride * hstride * 3 / 2
                        memScoped {
                            val nv12 = allocArray<UByteVar>(nv12Size)
                            sws_scale(
                                swsCtx,
                                cValuesOf(Bits(commandImage.data)),
                                cValuesOf(BytesPerLine(commandImage.data)),
                                0, height,
                                cValuesOf(nv12, nv12 + wstride * hstride),
                                cValuesOf(wstride, wstride),
                            )
                            EncoderW(encoderProcess.await(), cValue {
                                timestamp_ = commandImage.timestamp.toEpochMilliseconds()
                                is_key_frame_ = inputFrames % 12L == 0L
                                size_ = nv12Size.toLong()
                                data_ = nv12.reinterpret()
                            })
                        }
                        DestroyImage(commandImage.data)
                        ++inputFrames
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
                    if (codecpar == null) {
                        codecpar = avcodec_parameters_alloc().also {
                            it!!.pointed.codec_type = AVMEDIA_TYPE_VIDEO
                            it.pointed.codec_id = codec!!.pointed.id
                            it.pointed.codec_tag = 0U
                            it.pointed.width = width
                            it.pointed.height = height
                            it.pointed.format = AV_PIX_FMT_YUV420P
                            val extradata = av_malloc(packet.pointed.size.toULong())!!
                            memcpy(extradata, packet.pointed.data, packet.pointed.size.toULong())
                            it.pointed.extradata = extradata.reinterpret()
                            it.pointed.extradata_size = packet.pointed.size
                        }
                    }
                    val pts = (timestamp - timestamp0).inWholeMicroseconds * 90 / 1000
                    packet.pointed.pts = pts
                    packet.pointed.dts = pts
                    emit(packet as CPointer<AVPacket>?)
                }
            }
        }.onCompletion {
            if (swsCtx != null) sws_freeContext(swsCtx)
            if (codecpar != null) av_free(codecpar!!.pointed.extradata)
            StopEncoder(encoderProcess.getCompleted())
        }
    }
}
