import cnames.structs.*
import co.touchlab.kermit.Logger
import common.EncodeH264FFmpeg
import common.Frame
import common.OutputRtsp
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import common.Utils.checkEq0
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.*
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
    val codec = avcodec_find_encoder_by_name("libx265").check("avcodec_find_encoder_by_name")

    class Context(val ctx: OutputRtsp.Context) {
        var acl = SessionACL(0)
        var channelEncode: CPointer<aclvencChannelDesc>? = null
        var channelResize: CPointer<acldvppChannelDesc>? = null
        var swsCtx: CPointer<SwsContext>? = null
        var frameNo = 0

        fun createPacket(timestamp: Instant, image: CPointer<Image>, scope: ProducerScope<OutputRtsp.Input>) {
            CoroutineScope(acl.main).launch {
                acl.setContext()
                try {
                    val width = GetWidth(image)
                    val height = GetHeight(image)
                    if (channelEncode == null) {
                        channelEncode = aclvencCreateChannelDesc()
                        aclvencSetChannelDescThreadId(channelEncode, acl.threadId.await())
                        aclvencSetChannelDescCallback(channelEncode, staticCFunction { input, output, rawData ->
                            rawData!!.asStableRef<(
                                CPointer<acldvppPicDesc>, CPointer<acldvppStreamDesc>,
                            ) -> Unit>().let { ref ->
                                ref.get().also { ref.dispose() }(input!!, output!!)
                            }
                        })
                        aclvencSetChannelDescEnType(channelEncode, H265_MAIN_LEVEL)
                        aclvencSetChannelDescPicFormat(channelEncode, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                        aclvencSetChannelDescPicWidth(channelEncode, width.toUInt())
                        aclvencSetChannelDescPicHeight(channelEncode, height.toUInt())
                        aclvencSetChannelDescKeyFrameInterval(channelEncode, 65536U)
                        aclvencSetChannelDescRcMode(channelEncode, 1U)
                        aclvencSetChannelDescMaxBitRate(channelEncode, 750U)
                        aclvencCreateChannel(channelEncode).checkEq0("aclvencCreateChannel")
                        acl.channelReady.complete(Unit)
                    }
                    val picSize = wStride(width) * hStride(height) * 3 / 2
                    val picDev = cPointer<CPointed> {
                        acldvppMalloc(it.reinterpret(), picSize.toULong()).checkEq0("acldvppMalloc")
                    }
                    val picDesc = acldvppCreatePicDesc()
                    acldvppSetPicDescData(picDesc, picDev)
                    acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                    acldvppSetPicDescWidth(picDesc, width.toUInt())
                    acldvppSetPicDescHeight(picDesc, height.toUInt())
                    acldvppSetPicDescWidthStride(picDesc, wStride(width).toUInt())
                    acldvppSetPicDescHeightStride(picDesc, hStride(height).toUInt())
                    acldvppSetPicDescSize(picDesc, picSize.toUInt())
                    if (swsCtx == null) {
                        swsCtx = sws_getContext(
                            width, height, AV_PIX_FMT_RGB24,
                            width, height, AV_PIX_FMT_NV12,
                            SWS_BILINEAR.toInt(), null, null, null,
                        )
                    }
                    memScoped {
                        val pic = allocArray<UByteVar>(picSize)
                        sws_scale(
                            swsCtx,
                            cValuesOf(Bits(image)),
                            cValuesOf(BytesPerLine(image)),
                            0, height,
                            cValuesOf(pic, pic + height * width),
                            cValuesOf(width, width),
                        )
                        aclrtMemcpy(picDev, picSize.toULong(), pic, picSize.toULong(), acl.uploadMode())
                    }
                    val config = aclvencCreateFrameConfig()
                    aclvencSetFrameConfigEos(config, 0U)
                    val keyFrame = if (frameNo++ % 10 == 0) 1 else 0
                    aclvencSetFrameConfigForceIFrame(config, keyFrame.toUByte())
                    suspend fun run(input: CPointer<acldvppPicDesc>, output: CPointer<acldvppStreamDesc>) {
                        aclvencDestroyFrameConfig(config)
                        val pts = timestamp.toEpochMilliseconds() * 90
                        val dev = acldvppGetPicDescData(input)
                        val streamSize = acldvppGetStreamDescSize(output)
                        val streamDev = acldvppGetStreamDescData(output)
                        av_new_packet(ctx.packet, streamSize.toInt()).checkEq0("av_new_packet")
                        aclrtMemcpy(
                            ctx.packet.pointed.data, streamSize.toULong(),
                            streamDev, streamSize.toULong(),
                            acl.downloadMode(),
                        ).checkEq0("aclrtMemcpy")
                        // no need to acldvppFree(streamDev)
                        // no need to acldvppDestroyStreamDesc(output)
                        acldvppFree(dev).checkEq0("acldvppFree")
                        acldvppDestroyPicDesc(input).checkEq0("acldvppDestroyPicDesc")
                        if (ctx.videoStream == null) {
                            ctx.videoStream =
                                avformat_new_stream(ctx.formatCtx, codec).check("avformat_new_stream")
                            val bsf = av_bsf_get_by_name("extract_extradata")
                                ?: throw Error("av_bsf_get_by_name")
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
                            av_bsf_init(bsfContext).check("av_bsf_init")
                            av_bsf_send_packet(bsfContext, ctx.packet).check("av_bsf_send_packet")
                            av_packet_alloc().let {
                                av_bsf_receive_packet(bsfContext, it).check("av_bsf_receive_packet")
                                av_packet_move_ref(ctx.packet, it)
                                av_packet_free(cValuesOf(it))
                            }
                            avcodec_parameters_copy(ctx.videoStream!!.pointed.codecpar, bsfContext.pointed.par_out)
                            avcodec_parameters_free(cValuesOf(bsfContext.pointed.par_in))
                            av_bsf_free(cValuesOf(bsfContext))
                            ctx.formatCtx.pointed.start_time_realtime = pts / 90L * 1000L
                            withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                                avformat_write_header(ctx.formatCtx, it)
                                    .check("avformat_write_header")
                            }
                        }
                        if (keyFrame == 1) {
                            ctx.packet.pointed.flags = ctx.packet.pointed.flags.or(AV_PKT_FLAG_KEY)
                        }
                        ctx.packet.pointed.pts = pts
                        ctx.packet.pointed.dts = pts
                        scope.send(OutputRtsp.Input(ctx, ctx.packet))
                    }

                    val runACL = { input: CPointer<acldvppPicDesc>, output: CPointer<acldvppStreamDesc> ->
                        runBlocking { withContext(acl.main) { acl.setContext(); run(input, output) } }
                    }
                    val data = StableRef.create(runACL).asCPointer()
                    aclvencSendFrame(channelEncode, picDesc, null, config, data)
                } finally {
                    DestroyImage(image)
                }
            }
        }
    }

    override fun invoke(
        id: String,
        original: OutputRtsp.Context,
        processed: OutputRtsp.Context,
        input: Flow<Frame>
    ): Flow<OutputRtsp.Input> {
        val originalCtx = Context(original)
        val processedCtx = EncodeH264FFmpeg.Context(processed, Device.ENCODER_NAME_VIEW)
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
                originalCtx.createPacket(frame.timestamp, frame.original, this@callbackFlow)
                processedCtx.createFrame(frame.timestamp, frame.processed!!)
                processedCtx.send(processedCtx.frame, Device.encoderOptionsView(), ::send)
            }
            processedCtx.send(null, Device.encoderOptionsView(), ::send)
            processedCtx.close()
            originalCtx.let {
                withContext(it.acl.main) {
                    it.acl.setContext()
                    val config = aclvencCreateFrameConfig()
                    aclvencSetFrameConfigEos(config, 1U)
                    aclvencSetFrameConfigForceIFrame(config, 0U)
                    aclvencSendFrame(it.channelEncode, null, null, config, null)
                    aclvencDestroyFrameConfig(config)
                    if (it.channelResize != null) {
                        acldvppDestroyChannel(it.channelResize)
                        acldvppDestroyChannelDesc(it.channelResize)
                    }
                    if (it.channelEncode != null) {
                        aclvencDestroyChannel(it.channelEncode)
                        aclvencDestroyChannelDesc(it.channelEncode)
                    }
                }
                it.acl.close()
            }
            close()
            awaitClose()
        }
    }
}
