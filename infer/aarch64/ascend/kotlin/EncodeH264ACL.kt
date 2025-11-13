import cnames.structs.Image
import cnames.structs.aclvencChannelDesc
import co.touchlab.kermit.Logger
import common.Frame
import common.OutputRtsp
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.onCompletion
import platform.acl.*
import platform.ffmpeg.*
import platform.native.*
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
object EncodeH264ACL : (String, OutputRtsp.Context, OutputRtsp.Context, Flow<Frame>) -> Flow<OutputRtsp.Input> {
    val codec = avcodec_find_encoder_by_name(Device.ENCODER_NAME).check("avcodec_find_encoder_by_name")

    class Context(val ctx: OutputRtsp.Context) : AutoCloseable {
        val acl = SessionACL(0)
        override fun close() = acl.close()
    }

    data class Data(
        val thisData: ProducerScope<OutputRtsp.Input>,
        val pts: Long,
        val ctx: OutputRtsp.Context,
        val codec: CPointer<AVCodec>,
    )

    override fun invoke(
        id: String,
        original: OutputRtsp.Context,
        processed: OutputRtsp.Context,
        input: Flow<Frame>
    ): Flow<OutputRtsp.Input> {
        val originalCtx = Context(original)
        val processedCtx = Context(processed)
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        var timestamp0 = Clock.System.now()
        var channel: CPointer<aclvencChannelDesc>? = null
        return input.flatMapConcat { frame ->
            callbackFlow {
                if (inputFrames++ == 0L) timestamp0 = frame.timestamp
                Logger.i {
                    if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                    val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                    val delayed = frame0.elapsedNow() - (frame.timestamp - timestamp0)
                    val delayMs = max(0L, delayed.inWholeMilliseconds)
                    "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
                }
                fun Context.createPacket(timestamp: Instant, image: CPointer<Image>) {
                    try {
                        acl.setContext()
                        val width = GetWidth(image)
                        val height = GetHeight(image)
                        if (channel == null) {
                            channel = aclvencCreateChannelDesc()
                            aclvencSetChannelDescEnType(channel, H264_BASELINE_LEVEL)
                            aclvencSetChannelDescCallback(channel, staticCFunction { input, output, rawData ->
                                val dev = acldvppGetPicDescData(input)
                                val streamDev = acldvppGetStreamDescData(output)
                                val streamSize = acldvppGetStreamDescSize(output)
                                val data = rawData!!.asStableRef<Data>().get()
                                data.thisData.apply {
                                    av_new_packet(data.ctx.packet, streamSize.toInt()).check("av_new_packet")
                                    aclrtMemcpy(
                                        data.ctx.packet.pointed.data, streamSize.toULong(),
                                        streamDev, streamSize.toULong(),
                                        acl.downloadMode(),
                                    ).check("aclrtMemcpy")
                                    acldvppFree(dev)
                                    acldvppDestroyStreamDesc(output)
                                    acldvppDestroyPicDesc(input)
                                    if (data.ctx.videoStream == null) {
                                        data.ctx.videoStream = avformat_new_stream(
                                            data.ctx.formatCtx,
                                            data.codec
                                        ).check("avformat_new_stream")
                                        val bsf =
                                            av_bsf_get_by_name("extract_extradata") ?: throw Error("av_bsf_get_by_name")
                                        val bsfContext = cPointer { av_bsf_alloc(bsf, it) }
                                        bsfContext.pointed.par_in =
                                            avcodec_parameters_alloc().check("avcodec_parameters_alloc")
                                        bsfContext.pointed.par_in!!.pointed.let {
                                            it.codec_type = AVMEDIA_TYPE_VIDEO
                                            it.codec_id = data.codec.pointed.id
                                            it.codec_tag = 0U
                                            it.width = width
                                            it.height = height
                                            it.format = AV_PIX_FMT_YUV420P
                                        }
                                        av_bsf_init(bsfContext).check("av_bsf_init")
                                        av_bsf_send_packet(bsfContext, data.ctx.packet).check("av_bsf_send_packet")
                                        av_packet_alloc().let {
                                            av_bsf_receive_packet(bsfContext, it).check("av_bsf_receive_packet")
                                            av_packet_free(cValuesOf(it))
                                        }
                                        avcodec_parameters_copy(
                                            data.ctx.videoStream!!.pointed.codecpar,
                                            bsfContext.pointed.par_out
                                        )
                                        avcodec_parameters_free(cValuesOf(bsfContext.pointed.par_in))
                                        av_bsf_free(cValuesOf(bsfContext))
                                        data.ctx.formatCtx.pointed.start_time_realtime = data.pts / 90L * 1000L
                                        withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                                            avformat_write_header(data.ctx.formatCtx, it).check("avformat_write_header")
                                        }
                                    }
                                    data.ctx.packet.pointed.pts = data.pts
                                    data.ctx.packet.pointed.dts = data.pts
                                    trySend(OutputRtsp.Input(data.ctx, data.ctx.packet))
                                }
                            })
                            aclvencSetChannelDescPicFormat(channel, PIXEL_FORMAT_RGB_888)
                            aclvencSetChannelDescPicWidth(channel, width.toUInt())
                            aclvencSetChannelDescPicHeight(channel, height.toUInt())
                            aclvencSetChannelDescRcMode(channel, 1U)
                            aclvencSetChannelDescMaxBitRate(channel, 1000U)
                            aclvencCreateChannel(channel).check("aclvencCreateChannel")
                        }
                        val picDesc = acldvppCreatePicDesc()
                        val frameSize = (width * height * 3).toULong()
                        val dev =
                            cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).check("acldvppMalloc") }
                        acldvppSetPicDescData(picDesc, dev)
                        acldvppSetPicDescSize(picDesc, frameSize.toUInt())
                        acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_RGB_888)
                        acldvppSetPicDescWidth(picDesc, width.toUInt())
                        acldvppSetPicDescHeight(picDesc, height.toUInt())
                        acldvppSetPicDescWidthStride(picDesc, width.toUInt())
                        acldvppSetPicDescHeightStride(picDesc, height.toUInt())
                        val streamDesc = acldvppCreateStreamDesc()
                        aclrtMemcpy2d(
                            dev.reinterpret<UByteVar>(), (width * 3).toULong(),
                            Bits(image), BytesPerLine(image).toULong(),
                            (width * 3).toULong(),
                            height.toULong(),
                            acl.uploadMode(),
                        ).check("aclrtMemcpy2d")
                        val data = Data(this@callbackFlow, timestamp.toEpochMilliseconds() * 90, ctx, codec)
                        aclvencSendFrame(channel, picDesc, streamDesc, null, StableRef.create(data).asCPointer())
                            .check("aclvencSendFrame")
                        acl.process(-1)
                    } finally {
                        DestroyImage(image)
                    }
                }
                originalCtx.createPacket(frame.timestamp, frame.original)
                processedCtx.createPacket(frame.timestamp, frame.processed!!)
            }
        }.onCompletion {
            processedCtx.close()
            originalCtx.close()
        }
    }
}
