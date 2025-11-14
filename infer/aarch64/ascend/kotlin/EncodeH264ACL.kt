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
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    val aclEncode = SessionACL(0)
    val codec = avcodec_find_encoder_by_name(Device.ENCODER_NAME).check("avcodec_find_encoder_by_name")

    data class Data(
        val aclEncode: SessionACL,
        val thisData: ProducerScope<OutputRtsp.Input>,
        val ctx: OutputRtsp.Context,
        val pts: Long,
        val width: Int,
        val height: Int,
    )

    class Context(val ctx: OutputRtsp.Context) {
//        val acl = SessionACL(0)
        var channel: CPointer<aclvencChannelDesc>? = null
        val configResize = acldvppCreateResizeConfig()
        val channelResize = acldvppCreateChannelDesc()

        init {
            acldvppSetResizeConfigInterpolation(configResize, 0U)
            acldvppCreateChannel(channelResize)
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
        return callbackFlow {
            input.collect { frame ->
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
                        val width = GetWidth(image)
                        val height = GetHeight(image)
                        aclEncode!!.setContext()
                        if (channel == null) {
                            aclEncode!!.setContext()
                            channel = aclvencCreateChannelDesc()
                            aclvencSetChannelDescThreadId(channel, aclEncode!!.threadId)
                            aclvencSetChannelDescCallback(channel, staticCFunction { input, output, rawData ->
                                val data = rawData!!.asStableRef<Data>().let { ref ->
                                    ref.get().also { ref.dispose() }
                                }
                                data.aclEncode.setContext()
                                data.thisData.apply {
                                    val dev = acldvppGetPicDescData(input)
                                    val streamSize = acldvppGetStreamDescSize(output)
                                    val streamDev = acldvppGetStreamDescData(output)
                                    av_new_packet(data.ctx.packet, streamSize.toInt()).checkEq0("av_new_packet")
                                    aclrtMemcpy(
                                        data.ctx.packet.pointed.data, streamSize.toULong(),
                                        streamDev, streamSize.toULong(),
                                        data.aclEncode.downloadMode(),
                                    ).checkEq0("aclrtMemcpy")
                                    // no need to acldvppFree(streamDev)
                                    acldvppFree(dev).checkEq0("acldvppFree")
                                    acldvppDestroyStreamDesc(output).checkEq0("acldvppDestroyStreamDesc")
                                    acldvppDestroyPicDesc(input).checkEq0("acldvppDestroyPicDesc")
                                    if (data.ctx.videoStream == null) {
                                        data.ctx.videoStream = avformat_new_stream(
                                            data.ctx.formatCtx,
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
                                            it.width = data.width
                                            it.height = data.height
                                            it.format = AV_PIX_FMT_YUV420P
                                        }
                                        av_bsf_init(bsfContext).checkEq0("av_bsf_init")
                                        av_bsf_send_packet(bsfContext, data.ctx.packet).checkEq0("av_bsf_send_packet")
                                        av_packet_alloc().let {
                                            av_bsf_receive_packet(bsfContext, it).checkEq0("av_bsf_receive_packet")
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
                                            avformat_write_header(
                                                data.ctx.formatCtx,
                                                it
                                            ).checkEq0("avformat_write_header")
                                        }
                                    }
                                    data.ctx.packet.pointed.pts = data.pts
                                    data.ctx.packet.pointed.dts = data.pts
                                    trySend(OutputRtsp.Input(data.ctx, data.ctx.packet))
                                }
                            })
                            aclvencSetChannelDescEnType(channel, H264_BASELINE_LEVEL)
                            aclvencSetChannelDescPicFormat(channel, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                            aclvencSetChannelDescPicWidth(channel, width.toUInt())
                            aclvencSetChannelDescPicHeight(channel, height.toUInt())
                            aclvencSetChannelDescKeyFrameInterval(channel, 10U)
                            aclvencSetChannelDescRcMode(channel, 1U)
                            aclvencSetChannelDescMaxBitRate(channel, 1000U)
                            aclvencCreateChannel(channel).checkEq0("aclvencCreateChannel")
                        }
                        // acl.setContext()
                        val rgbSize = (wStride(width) * hStride(height) * 3).toULong()
                        val rgbDev = cPointer<CPointed> {
                            acldvppMalloc(it.reinterpret(), rgbSize).checkEq0("acldvppMalloc")
                        }
                        val rgbDesc = acldvppCreatePicDesc()
                        acldvppSetPicDescData(rgbDesc, rgbDev)
                        acldvppSetPicDescFormat(rgbDesc, PIXEL_FORMAT_RGB_888)
                        acldvppSetPicDescWidth(rgbDesc, width.toUInt())
                        acldvppSetPicDescHeight(rgbDesc, height.toUInt())
                        acldvppSetPicDescWidthStride(rgbDesc, wStride(width).toUInt())
                        acldvppSetPicDescHeightStride(rgbDesc, hStride(height).toUInt())
                        acldvppSetPicDescSize(rgbDesc, rgbSize.toUInt())
                        repeat(height) {
                            val dstLine = wStride(width) * 3
                            val dst = rgbDev.reinterpret<UByteVar>() + dstLine * it
                            val srcLine = BytesPerLine(image)
                            val src = Bits(image) + srcLine * it
                            aclrtMemcpy(dst, dstLine.toULong(), src, srcLine.toULong(), aclEncode!!.uploadMode())
                        }
                        val picSize = (wStride(width) * hStride(height) * 3 / 2).toULong()
                        val picDev = cPointer<CPointed> {
                            acldvppMalloc(it.reinterpret(), picSize).checkEq0("acldvppMalloc")
                        }
                        val picDesc = acldvppCreatePicDesc()
                        acldvppSetPicDescData(picDesc, picDev)
                        acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                        acldvppSetPicDescWidth(picDesc, width.toUInt())
                        acldvppSetPicDescHeight(picDesc, height.toUInt())
                        acldvppSetPicDescWidthStride(picDesc, wStride(width).toUInt())
                        acldvppSetPicDescHeightStride(picDesc, hStride(height).toUInt())
                        acldvppSetPicDescSize(picDesc, picSize.toUInt())
                        acldvppVpcResizeAsync(channelResize, rgbDesc, picDesc, configResize, null)
                            .checkEq0("acldvppVpcResizeAsync")
                        aclrtSynchronizeStream(null)
                        aclEncode!!.setContext()
                        val streamDesc = acldvppCreateStreamDesc()
                        val pts = timestamp.toEpochMilliseconds() * 90
                        val data = Data(aclEncode!!, this@callbackFlow, ctx, pts, width, height)
                        val config = aclvencCreateFrameConfig()
                        aclvencSetFrameConfigEos(config, 0U)
                        aclvencSetFrameConfigForceIFrame(config, 0U)
                        aclvencSendFrame(channel, picDesc, streamDesc, config, StableRef.create(data).asCPointer())
                            .checkEq0("aclvencSendFrame")
                    } finally {
                        DestroyImage(image)
                    }
                }
                // originalCtx.createPacket(frame.timestamp, frame.original)
                processedCtx.createPacket(frame.timestamp, frame.processed!!)
            }
            val config = aclvencCreateFrameConfig()
            aclvencSetFrameConfigEos(config, 1U)
            aclvencSetFrameConfigForceIFrame(config, 0U)
            listOf(originalCtx, processedCtx).forEach {
                aclEncode.setContext()
                aclvencSendFrame(it.channel, null, null, config, null)
                acldvppDestroyChannel(it.channelResize)
                acldvppDestroyChannelDesc(it.channelResize)
                aclvencDestroyChannel(it.channel)
                aclvencDestroyChannelDesc(it.channel)
//                it.acl.close()
            }
            close()
            awaitClose()
        }
    }
}
