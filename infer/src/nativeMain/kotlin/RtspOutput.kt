import StringFormat.toString
import Utils.cPointer
import Utils.check
import Utils.use
import Utils.withOptions
import co.touchlab.kermit.Logger
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import platform.native.DestroyImage
import platform.native.GetHeight
import platform.native.GetWidth
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) {
    suspend fun runReceive(receive: Flow<Video.Frame>) = memScoped {
        cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }.use({ avformat_free_context(it.ptr.pointed.value) }) { formatCtx ->
            val codec = avcodec_find_encoder_by_name(Device.H264_ENCODER_NAME).check("avcodec_find_encoder_by_name")
            avcodec_alloc_context3(codec)!!.use({ avcodec_free_context(it) }) { codecCtx ->
                codecCtx.codec_type = AVMEDIA_TYPE_VIDEO
                codecCtx.pix_fmt = AV_PIX_FMT_RGB24
                codecCtx.max_b_frames = 0
                codecCtx.gop_size = 10
                codecCtx.time_base.num = 1
                codecCtx.time_base.den = 90000
                av_packet_alloc()!!.use({ av_packet_free(it) }) { packet ->
                    av_frame_alloc()!!.use({ av_frame_free(it) }) { frame ->
                        var videoStream: CPointer<AVStream>? = null
                        var inputFrames = 0L
                        var outputFrames = 0L
                        var frame0: TimeSource.Monotonic.ValueTimeMark? = null
                        var timestamp0 = Duration.ZERO
                        receive.collect { input ->
                            try {
                                if (codecCtx.width == 0) {
                                    codecCtx.width = GetWidth(input.image)
                                    codecCtx.height = GetHeight(input.image)
                                    withOptions(*Device.encoderOptions) {
                                        avcodec_open2(codecCtx.ptr, codec, it).check("avcodec_open2")
                                    }
                                    videoStream = avformat_new_stream(formatCtx.ptr, codec)
                                        .check("avformat_new_stream")
                                    avcodec_parameters_from_context(videoStream.pointed.codecpar, codecCtx.ptr)
                                    withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                                        avformat_write_header(formatCtx.ptr, it).check("avformat_write_header")
                                    }
                                }
                                if (inputFrames == 0L) timestamp0 = input.timestamp
                                if (maxFrames(input.timestamp - timestamp0) < inputFrames) return@collect
                                ++inputFrames
                                frame.pts = input.timestamp.inWholeMicroseconds * 90 / 1000
                                FromRGBImage(frame, input.image)
                                avcodec_send_frame(codecCtx.ptr, frame.ptr).check("avcodec_send_frame")
                                av_frame_unref(frame.ptr)
                                Logger.i {
                                    val text = StringBuilder()
                                    if (frame0 == null) {
                                        frame0 = TimeSource.Monotonic.markNow()
                                    } else {
                                        val fps = 1.seconds / (frame0.elapsedNow() / (++outputFrames).toDouble())
                                        text.append("编码后的每秒帧数: ${fps.toString(2)} ")
                                        val delayed = frame0.elapsedNow() - (input.timestamp - timestamp0)
                                        if (delayed.isPositive()) text.append("额外延迟: $delayed ")
                                    }
                                    text.toString()
                                }
                                while (0 <= avcodec_receive_packet(codecCtx.ptr, packet.ptr)) {
                                    packet.stream_index = videoStream!!.pointed.index
                                    av_interleaved_write_frame(formatCtx.ptr, packet.ptr)
                                    av_packet_unref(packet.ptr)
                                }
                            } finally {
                                DestroyImage(input.image)
                            }
                        }
                        av_write_trailer(formatCtx.ptr)
                    }
                }
            }
        }
    }

    fun maxFrames(duration: Duration) = (duration * AppArguments.instance.encodeFps).inWholeSeconds
}
