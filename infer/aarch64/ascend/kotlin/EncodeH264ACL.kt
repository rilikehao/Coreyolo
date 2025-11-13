import cnames.structs.Image
import cnames.structs.aclvencChannelDesc
import co.touchlab.kermit.Logger
import common.Frame
import common.OutputRtsp
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import common.Utils.checkEq0
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
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
        var channel: CPointer<aclvencChannelDesc>? = null

        override fun close() {
            if (channel != null) {
                aclvencDestroyChannel(channel)
                aclvencDestroyChannelDesc(channel)
            }
            acl.close()
        }
    }

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
        return input.transform { frame ->
            if (inputFrames++ == 0L) timestamp0 = frame.timestamp
            Logger.i {
                if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                val delayed = frame0.elapsedNow() - (frame.timestamp - timestamp0)
                val delayMs = max(0L, delayed.inWholeMilliseconds)
                "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
            }
            suspend fun Context.createPacket(timestamp: Instant, image: CPointer<Image>) {
                try {
                    acl.setContext()
                    val width = GetWidth(image)
                    val height = GetHeight(image)
                    if (channel == null) {
                        channel = aclvencCreateChannelDesc()
                        aclvencSetChannelDescEnType(channel, H264_BASELINE_LEVEL)
                        aclvencSetChannelDescCallback(channel, null)
                        aclvencSetChannelDescPicFormat(channel, PIXEL_FORMAT_RGB_888)
                        aclvencSetChannelDescPicWidth(channel, width.toUInt())
                        aclvencSetChannelDescPicHeight(channel, height.toUInt())
                        aclvencSetChannelDescRcMode(channel, 1U)
                        aclvencSetChannelDescMaxBitRate(channel, 1000U)
                        aclvencCreateChannel(channel).checkEq0("aclvencCreateChannel")
                    }
                    val picDesc = acldvppCreatePicDesc()
                    val frameSize = (width * height * 3).toULong()
                    val dev =
                        cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).checkEq0("acldvppMalloc") }
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
                    ).checkEq0("aclrtMemcpy2d")
                    val pts = timestamp.toEpochMilliseconds() * 90
                    aclvencSendFrame(channel, picDesc, streamDesc, null, null).checkEq0("aclvencSendFrame")
                    val streamSize = acldvppGetStreamDescSize(streamDesc)
                    val streamDev = acldvppGetStreamDescData(streamDesc)
                    av_new_packet(ctx.packet, streamSize.toInt()).checkEq0("av_new_packet")
                    aclrtMemcpy(
                        ctx.packet.pointed.data, streamSize.toULong(),
                        streamDev, streamSize.toULong(),
                        acl.downloadMode(),
                    ).checkEq0("aclrtMemcpy")
                    acldvppFree(dev)
                    acldvppDestroyStreamDesc(streamDesc)
                    acldvppDestroyPicDesc(picDesc)
                    if (ctx.videoStream == null) {
                        ctx.videoStream = avformat_new_stream(
                            ctx.formatCtx,
                            codec,
                        ).check("avformat_new_stream")
                        val bsf =
                            av_bsf_get_by_name("extract_extradata") ?: throw Error("av_bsf_get_by_name")
                        val bsfContext = cPointer { av_bsf_alloc(bsf, it) }
                        bsfContext.pointed.par_in =
                            avcodec_parameters_alloc().check("avcodec_parameters_alloc")
                        bsfContext.pointed.par_in!!.pointed.let {
                            it.codec_type = AVMEDIA_TYPE_VIDEO
                            it.codec_id = codec.pointed.id
                            it.codec_tag = 0U
                            it.width = width
                            it.height = height
                            it.format = AV_PIX_FMT_YUV420P
                        }
                        av_bsf_init(bsfContext).checkEq0("av_bsf_init")
                        av_bsf_send_packet(bsfContext, ctx.packet).checkEq0("av_bsf_send_packet")
                        av_packet_alloc().let {
                            av_bsf_receive_packet(bsfContext, it).checkEq0("av_bsf_receive_packet")
                            av_packet_free(cValuesOf(it))
                        }
                        avcodec_parameters_copy(
                            ctx.videoStream!!.pointed.codecpar,
                            bsfContext.pointed.par_out
                        )
                        avcodec_parameters_free(cValuesOf(bsfContext.pointed.par_in))
                        av_bsf_free(cValuesOf(bsfContext))
                        ctx.formatCtx.pointed.start_time_realtime = pts / 90L * 1000L
                        withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                            avformat_write_header(ctx.formatCtx, it).checkEq0("avformat_write_header")
                        }
                    }
                    ctx.packet.pointed.pts = pts
                    ctx.packet.pointed.dts = pts
                    emit(OutputRtsp.Input(ctx, ctx.packet))
                } finally {
                    DestroyImage(image)
                }
            }
            originalCtx.createPacket(frame.timestamp, frame.original)
            processedCtx.createPacket(frame.timestamp, frame.processed!!)
        }.onCompletion {
            processedCtx.close()
            originalCtx.close()
        }
    }
}
//val configResize = acldvppCreateResizeConfig()!!
//acldvppSetResizeConfigInterpolation(configResize, 0U)
//val channelResize = acldvppCreateChannelDesc()!!
//acldvppCreateChannel(channelResize)

//val rgbSize = (wStride(data.codecParams) * hStride(data.codecParams) * 3).toULong()  // RGB
//val rgbDev = cPointer<CPointed> {
//    acldvppMalloc(it.reinterpret(), rgbSize).checkEq0("acldvppMalloc")
//}
//val rgbDesc = acldvppCreatePicDesc()
//acldvppSetPicDescData(rgbDesc, rgbDev)
//acldvppSetPicDescFormat(rgbDesc, PIXEL_FORMAT_RGB_888)
//acldvppSetPicDescWidth(rgbDesc, data.codecParams.width.toUInt())
//acldvppSetPicDescHeight(rgbDesc, data.codecParams.height.toUInt())
//acldvppSetPicDescWidthStride(rgbDesc, wStride(data.codecParams).toUInt())
//acldvppSetPicDescHeightStride(rgbDesc, hStride(data.codecParams).toUInt())
//acldvppSetPicDescSize(rgbDesc, rgbSize.toUInt())
//acldvppVpcResizeAsync(data.channelResize, output, rgbDesc, data.configResize, null)
//.checkEq0("acldvppVpcResizeAsync")
//aclrtSynchronizeStream(null)
//val image = CreateImageRGB24(data.codecParams.width, data.codecParams.height)
//repeat(data.codecParams.height) {
//    val dstLine = BytesPerLine(image)
//    val dst = Bits(image) + dstLine * it
//    val srcLine = data.codecParams.width * 3
//    val src = frameDev.reinterpret<UByteVar>() + srcLine * it
//    aclrtMemcpy(dst, dstLine.toULong(), src, srcLine.toULong(), data.acl.downloadMode())
//}
