package common

import Device
import FromRGBImage
import cnames.structs.Image
import co.touchlab.kermit.Logger
import common.StringFormat.toString
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import platform.ffmpeg.*
import platform.native.DestroyImage
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object EncodeH264FFmpeg : (String, OutputRtsp.Context, OutputRtsp.Context, Flow<Frame>) -> Flow<OutputRtsp.Input> {
    class Context(val ctx: OutputRtsp.Context) : AutoCloseable {
        val fromRGBImage = FromRGBImage()

        val codec = avcodec_find_encoder_by_name(Device.ENCODER_NAME).check("avcodec_find_encoder_by_name")

        val codecCtx = avcodec_alloc_context3(codec)!!.apply {
            pointed.codec_type = AVMEDIA_TYPE_VIDEO
            pointed.pix_fmt = Device.ENCODER_FORMAT
            pointed.time_base.num = 1
            pointed.time_base.den = 90000
        }

        val frame = av_frame_alloc()!!

        override fun close() {
            av_frame_free(cValuesOf(frame))
            avcodec_free_context(cValuesOf(codecCtx))
            fromRGBImage.close()
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
            fun Context.createFrame(timestamp: Instant, image: CPointer<Image>) {
                this.frame.pointed.pts = timestamp.toEpochMilliseconds() * 90
                fromRGBImage(this.frame.pointed, image)
                DestroyImage(image)
            }
            originalCtx.createFrame(frame.timestamp, frame.original)
            processedCtx.createFrame(frame.timestamp, frame.processed!!)
            emit(Pair(originalCtx.frame as CPointer<AVFrame>?, processedCtx.frame as CPointer<AVFrame>?))
        }.onCompletion {
            emit(Pair(null, null))
        }.transform { (originalFrame, processedFrame) ->
            suspend fun Context.send(frame: CPointer<AVFrame>?) {
                if (codecCtx.pointed.width == 0 && frame != null) {
                    codecCtx.pointed.width = frame.pointed.width
                    codecCtx.pointed.height = frame.pointed.height
                    withOptions(*Device.encoderOptions()) {
                        avcodec_open2(codecCtx, codec, it).check("avcodec_open2")
                    }
                    ctx.videoStream = avformat_new_stream(ctx.formatCtx, codec).check("avformat_new_stream")
                    avcodec_parameters_from_context(ctx.videoStream!!.pointed.codecpar, codecCtx)
                    ctx.formatCtx.pointed.start_time_realtime = frame.pointed.pts / 90L * 1000L
                    withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                        avformat_write_header(ctx.formatCtx, it).check("avformat_write_header")
                    }
                }
                avcodec_send_frame(codecCtx, frame).check("avcodec_send_frame")
                if (frame != null) av_frame_unref(frame)
                while (0 <= avcodec_receive_packet(codecCtx, ctx.packet)) {
                    emit(OutputRtsp.Input(ctx, ctx.packet))
                }
            }
            originalCtx.send(originalFrame)
            processedCtx.send(processedFrame)
        }.onCompletion {
            processedCtx.close()
            originalCtx.close()
        }
    }
}
