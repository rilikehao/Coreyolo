import StringFormat.toString
import Utils.cPointer
import Utils.check
import Utils.withOptions
import co.touchlab.kermit.Logger
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import platform.ffmpeg.*
import platform.native.DestroyImage
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) : suspend (String, Flow<Video.Frame>) -> Unit {
    override suspend fun invoke(id: String, inputFlow: Flow<Video.Frame>) {
        val formatCtx = cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        var timestamp0 = Duration.ZERO
        val frame = av_frame_alloc()!!
        val frames = inputFlow.map { input ->
            try {
                if (inputFrames++ == 0L) timestamp0 = input.timestamp
                frame.pointed.pts = input.timestamp.inWholeMicroseconds * 90 / 1000
                FromRGBImage(frame.pointed, input.image)
                Logger.i {
                    if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                    val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                    val delayed = frame0.elapsedNow() - (input.timestamp - timestamp0)
                    val delayMs = max(0L, delayed.inWholeMilliseconds)
                    "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
                }
                frame as CPointer<AVFrame>?
            } finally {
                DestroyImage(input.image)
            }
        }.onCompletion {
            emit(null)
        }
        var videoStream: CPointer<AVStream>? = null
        val codec = avcodec_find_encoder_by_name(Device.H264_ENCODER_NAME).check("avcodec_find_encoder_by_name")
        val codecCtx = avcodec_alloc_context3(codec)!!
        codecCtx.pointed.codec_type = AVMEDIA_TYPE_VIDEO
        codecCtx.pointed.pix_fmt = AV_PIX_FMT_RGB24
        codecCtx.pointed.max_b_frames = 0
        codecCtx.pointed.gop_size = 10
        codecCtx.pointed.time_base.num = 1
        codecCtx.pointed.time_base.den = 90000
        val packet = av_packet_alloc()!!
        try {
            frames.collect { frame ->
                if (codecCtx.pointed.width == 0 && frame != null) {
                    codecCtx.pointed.width = frame.pointed.width
                    codecCtx.pointed.height = frame.pointed.height
                    withOptions(*Device.encoderOptions) {
                        avcodec_open2(codecCtx, codec, it).check("avcodec_open2")
                    }
                    videoStream = avformat_new_stream(formatCtx, codec).check("avformat_new_stream")
                    avcodec_parameters_from_context(videoStream.pointed.codecpar, codecCtx)
                    withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                        avformat_write_header(formatCtx, it).check("avformat_write_header")
                    }
                }
                avcodec_send_frame(codecCtx, frame).check("avcodec_send_frame")
                if (frame != null) av_frame_unref(frame)
                while (0 <= avcodec_receive_packet(codecCtx, packet)) {
                    packet.pointed.stream_index = videoStream!!.pointed.index
                    av_interleaved_write_frame(formatCtx, packet)
                    av_packet_unref(packet)
                }
            }
            if (codecCtx.pointed.width != 0) av_write_trailer(formatCtx)
        } finally {
            av_packet_free(cValuesOf(packet))
            avcodec_free_context(cValuesOf(codecCtx))
            av_frame_free(cValuesOf(frame))
            avformat_free_context(formatCtx)
        }
    }
}
