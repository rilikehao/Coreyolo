import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import platform.native.DestroyImage
import platform.native.GetHeight
import platform.native.GetWidth

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) {
    suspend fun runReceive(receive: Flow<Video.Frame>) = memScoped {
        val options = alloc<CPointerVar<AVDictionary>>()
        av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
        av_dict_set(options.ptr, "tune", "zerolatency", 0)
        try {
            val formatCtx = alloc<CPointerVar<AVFormatContext>>()
            avformat_alloc_output_context2(formatCtx.ptr, null, "rtsp", url).check("avformat_alloc_output_context2")
            try {
                val codec = avcodec_find_encoder_by_name(Device.H264_ENCODER_NAME).check("avcodec_find_encoder_by_name")
                val codecCtx = alloc<CPointerVar<AVCodecContext>>().also { it.value = avcodec_alloc_context3(codec) }
                try {
                    val ctx = codecCtx.value!!.pointed
                    ctx.codec_type = AVMEDIA_TYPE_VIDEO
                    ctx.pix_fmt = AV_PIX_FMT_YUV420P
                    ctx.max_b_frames = 0
                    ctx.gop_size = 10
                    ctx.time_base.num = 1
                    ctx.time_base.den = 90000
                    var videoStream: CPointer<AVStream>? = null
                    FromRGBImage().use { fromRGBImage ->
                        val frame = alloc<CPointerVar<AVFrame>>().also { it.value = av_frame_alloc() }
                        val packet = alloc<CPointerVar<AVPacket>>().also { it.value = av_packet_alloc() }
                        try {
                            receive.collect { input ->
                                try {
                                    if (ctx.width == 0) {
                                        ctx.width = GetWidth(input.image)
                                        ctx.height = GetHeight(input.image)
                                        val codecOptions = alloc<CPointerVar<AVDictionary>>()
                                        try {
                                            Device.setEncoderOptions(codecOptions)
                                            avcodec_open2(codecCtx.value, codec, codecOptions.ptr)
                                                .check("avcodec_open2")
                                        } finally {
                                            av_dict_free(codecOptions.ptr)
                                        }
                                        videoStream = avformat_new_stream(formatCtx.value, codec)
                                            .check("avformat_new_stream")
                                        avcodec_parameters_from_context(videoStream.pointed.codecpar, codecCtx.value)
                                        avformat_write_header(
                                            formatCtx.value,
                                            options.ptr
                                        ).check("avformat_write_header")
                                        fromRGBImage.init(codecCtx.pointed!!)
                                    }
                                    frame.pointed!!.pts = input.timestamp.inWholeMicroseconds * 90 / 1000
                                    fromRGBImage(frame.pointed!!, input.image)
                                    avcodec_send_frame(codecCtx.value, frame.value).check("avcodec_send_frame")
                                    av_frame_unref(frame.value)
                                    while (0 <= avcodec_receive_packet(codecCtx.value, packet.value)) {
                                        packet.value!!.pointed.stream_index = videoStream!!.pointed.index
                                        av_interleaved_write_frame(formatCtx.value, packet.value)
                                        av_packet_unref(packet.value)
                                    }
                                } finally {
                                    DestroyImage(input.image)
                                }
                            }
                            av_write_trailer(formatCtx.value)
                        } finally {
                            av_packet_free(packet.ptr)
                            av_frame_free(frame.ptr)
                        }
                    }
                } finally {
                    avcodec_free_context(codecCtx.ptr)
                }
            } finally {
                if (formatCtx.value!!.pointed.pb != null) {
                    val pb = alloc<CPointerVar<AVIOContext>>().also { it.value = formatCtx.value!!.pointed.pb }
                    avio_closep(pb.ptr)
                    formatCtx.value!!.pointed.pb = null
                }
                avformat_free_context(formatCtx.value)
            }
        } finally {
            av_dict_free(options.ptr)
        }
    }
}
