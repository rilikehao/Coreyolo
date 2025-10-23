import Utils.cPointer
import Utils.check
import Utils.use
import Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import platform.native.DestroyImage
import platform.native.GetHeight
import platform.native.GetWidth

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) {
    suspend fun runReceive(receive: Flow<Video.Frame>) = memScoped {
        cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }.use({ avformat_free_context(it.ptr.pointed.value) }) { formatCtx ->
            val codec = avcodec_find_encoder_by_name(Device.H264_ENCODER_NAME).check("avcodec_find_encoder_by_name")
            avcodec_alloc_context3(codec)!!.use({ avcodec_free_context(it) }) { codecCtx ->
                codecCtx.codec_type = AVMEDIA_TYPE_VIDEO
                codecCtx.pix_fmt = AV_PIX_FMT_YUV420P
                codecCtx.max_b_frames = 0
                codecCtx.gop_size = 10
                codecCtx.time_base.num = 1
                codecCtx.time_base.den = 90000
                FromRGBImage().use { fromRGBImage ->
                    av_packet_alloc()!!.use({ av_packet_free(it) }) { packet ->
                        av_frame_alloc()!!.use({ av_frame_free(it) }) { frame ->
                            var videoStream: CPointer<AVStream>? = null
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
                                        fromRGBImage.init(codecCtx)
                                    }
                                    frame.pts = input.timestamp.inWholeMicroseconds * 90 / 1000
                                    fromRGBImage(frame, input.image)
                                    avcodec_send_frame(codecCtx.ptr, frame.ptr).check("avcodec_send_frame")
                                    av_frame_unref(frame.ptr)
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
    }
}
