import Utils.cPointer
import Utils.check
import Utils.use
import Utils.withOptions
import co.touchlab.kermit.Logger
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
                codecCtx.pix_fmt = AV_PIX_FMT_RGB24
                codecCtx.max_b_frames = 0
                codecCtx.gop_size = 10
                codecCtx.time_base.num = 1
                codecCtx.time_base.den = 90000
                av_packet_alloc()!!.use({ av_packet_free(it) }) { packet ->
                    av_frame_alloc()!!.use({ av_frame_free(it) }) { frame ->
                        var videoStream: CPointer<AVStream>? = null
                        val manager = Manager(1)
                        receive.map { input ->
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
                            } catch (e: Exception) {
                                DestroyImage(input.image)
                                throw e
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    manager.use { id ->
                                        if (id == null) {
                                            Logger.w { "编码器过载丢帧" }
                                        } else {
                                            frame.pts = input.timestamp.inWholeMicroseconds * 90 / 1000
                                            FromRGBImage(frame, input.image)
                                            avcodec_send_frame(codecCtx.ptr, frame.ptr).check("avcodec_send_frame")
                                            av_frame_unref(frame.ptr)
                                            while (0 <= avcodec_receive_packet(codecCtx.ptr, packet.ptr)) {
                                                packet.stream_index = videoStream!!.pointed.index
                                                av_interleaved_write_frame(formatCtx.ptr, packet.ptr)
                                                av_packet_unref(packet.ptr)
                                            }
                                        }
                                    }
                                } finally {
                                    DestroyImage(input.image)
                                }
                            }
                        }.buffer(1).onEach { it.join() }.onCompletion { av_write_trailer(formatCtx.ptr) }.collect()
                    }
                }
            }
        }
    }
}
