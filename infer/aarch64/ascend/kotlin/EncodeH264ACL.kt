import cnames.structs.*
import co.touchlab.kermit.Logger
import common.EncodeH264FFmpeg
import common.Frame
import common.OutputRtsp
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import platform.acl.*
import platform.ffmpeg.*
import platform.native.*
import platform.posix.EAGAIN
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.uint8_tVar
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object EncodeH264ACL : (String, OutputRtsp.Context, OutputRtsp.Context, Flow<Frame>) -> Flow<OutputRtsp.Input> {
    val codec = avcodec_find_encoder_by_name(Device.ENCODER_NAME).check("avcodec_find_encoder_by_name")

    class Context(val ctx: OutputRtsp.Context) : AutoCloseable {
        val acl = SessionACL(0)
        override fun close() = acl.close()
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
        var channel: CPointer<aclvencChannelDesc>? = null
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
                    val picDesc = acldvppCreatePicDesc()
                    val frameSize = (width * height * 3).toULong()
                    val dev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).check("acldvppMalloc") }
                    acldvppSetPicDescData(picDesc, dev)
                    acldvppSetPicDescSize(picDesc, frameSize.toUInt())
                    acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_RGB_888)
                    acldvppSetPicDescWidth(picDesc, width.toUInt())
                    acldvppSetPicDescHeight(picDesc, height.toUInt())
                    acldvppSetPicDescWidthStride(picDesc, width.toUInt())
                    acldvppSetPicDescHeightStride(picDesc, height.toUInt())
                    val streamDesc = acldvppCreateStreamDesc()
                    repeat(height) {
                        val srcLine = BytesPerLine(image)
                        val src = Bits(image) + srcLine * it
                        val dstLine = width * 3
                        val dst = dev.reinterpret<UByteVar>() + dstLine * it
                        aclrtMemcpy(dst, dstLine.toULong(), src, srcLine.toULong(), acl.uploadMode()).check("aclrtMemcpy")
                    }
                    aclvencSendFrame(channel, picDesc, streamDesc, null, null).check("aclvencSendFrame")
                    acl.process(-1)
                    val streamDev = acldvppGetStreamDescData(streamDesc)
                    val streamSize = acldvppGetStreamDescSize(streamDesc)
                    av_new_packet(ctx.packet, streamSize.toInt()).check("av_new_packet")
                    aclrtMemcpy(
                        ctx.packet.pointed.data, streamSize.toULong(),
                        streamDev, streamSize.toULong(),
                        acl.downloadMode(),
                    ).check("aclrtMemcpy")
                    acldvppFree(streamDev)
                    acldvppDestroyStreamDesc(streamDesc)
                    acldvppFree(dev)
                    acldvppDestroyPicDesc(picDesc)
                    ctx.packet.pointed.pts = timestamp.toEpochMilliseconds() * 90
                    if (channel == null) {
                        channel = aclvencCreateChannelDesc()
                        aclvencSetChannelDescEnType(channel, H264_BASELINE_LEVEL)
                        aclvencSetChannelDescCallback(channel, null)
                        aclvencSetChannelDescPicFormat(channel, PIXEL_FORMAT_RGB_888)
                        aclvencSetChannelDescPicWidth(channel, width.toUInt())
                        aclvencSetChannelDescPicHeight(channel, height.toUInt())
                        aclvencSetChannelDescRcMode(channel, 1U)
                        aclvencSetChannelDescMaxBitRate(channel, 1000U)
                        aclvencCreateChannel(channel).check("aclvencCreateChannel")
                        ctx.videoStream = avformat_new_stream(ctx.formatCtx, codec).check("avformat_new_stream")
                        val bsf = av_bsf_get_by_name("extract_extradata") ?: throw Error("av_bsf_get_by_name")
                        val bsfContext = cPointer { av_bsf_alloc(bsf, it) }
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
                        av_bsf_receive_packet(bsfContext, ctx.packet).check("av_bsf_receive_packet")
                        avcodec_parameters_copy(ctx.videoStream!!.pointed.codecpar, bsfContext.pointed.par_out)
                        av_bsf_free(cValuesOf(bsfContext))
                        ctx.formatCtx.pointed.start_time_realtime = ctx.packet.pointed.pts / 90L * 1000L
                        withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                            avformat_write_header(ctx.formatCtx, it).check("avformat_write_header")
                        }
                    }
                    emit(OutputRtsp.Input(ctx, ctx.packet))
                } finally {
                    DestroyImage(image)
                }
            }
            originalCtx.createPacket(frame.timestamp, frame.original)
            emit(OutputRtsp.Input(originalCtx.ctx, originalCtx.ctx.packet))
            processedCtx.createPacket(frame.timestamp, frame.processed!!)
            emit(OutputRtsp.Input(originalCtx.ctx, originalCtx.ctx.packet))
        }.onCompletion {
            processedCtx.close()
            originalCtx.close()
        }
    }
}
