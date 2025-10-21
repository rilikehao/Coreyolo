import cnames.structs.AVDictionary
import cnames.structs.Image
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import platform.native.*
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) {
    suspend fun runReceive(receive: Flow<Pair<Duration, CPointer<Image>>>) = memScoped {
        val options = alloc<CPointerVar<AVDictionary>>()
        av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
        av_dict_set(options.ptr, "tune", "zerolatency", 0)
        val formatCtx = alloc<CPointerVar<AVFormatContext>>()
        if (avformat_alloc_output_context2(formatCtx.ptr, null, "rtsp", url) < 0) {
            throw Error("avformat_alloc_output_context2 失败")
        }
        try {
            val codec = avcodec_find_encoder_by_name(H264.ENCODER_NAME)
                ?: throw Error("avcodec_find_encoder H264 失败")
            val codecCtx = alloc<CPointerVar<AVCodecContext>>().also { it.value = avcodec_alloc_context3(codec) }
            try {
                val ctx = codecCtx.value!!.pointed
                ctx.codec_type = AVMEDIA_TYPE_VIDEO
                ctx.pix_fmt = AV_PIX_FMT_YUV420P
                ctx.max_b_frames = 0
                ctx.gop_size = 10
                ctx.time_base.num = 1
                ctx.time_base.den = 90000
                ctx.thread_type = FF_THREAD_SLICE
                ctx.thread_count = 0
                var videoStream: CPointer<AVStream>? = null
                val swsCtx = alloc<CPointerVar<SwsContext>>()
                try {
                    receive.collect { (pts, frame) ->
                        try {
                            if (ctx.width == 0) {
                                ctx.width = GetWidth(frame)
                                ctx.height = GetHeight(frame)
                                val codecOptions = alloc<CPointerVar<AVDictionary>>()
                                H264.setEncoderOptions(codecOptions)
                                if (avcodec_open2(codecCtx.value, codec, codecOptions.ptr) < 0) {
                                    throw Error("avcodec_open2 失败")
                                }
                                av_dict_free(codecOptions.ptr)
                                videoStream = avformat_new_stream(formatCtx.value, codec)
                                    ?: throw Error("avformat_new_stream 失败")
                                avcodec_parameters_from_context(videoStream.pointed.codecpar, codecCtx.value)
                                if (avformat_write_header(formatCtx.value, options.ptr) < 0) {
                                    throw Error("avformat_write_header 失败")
                                }
                                swsCtx.value = sws_getContext(
                                    ctx.width, ctx.height, AV_PIX_FMT_RGB24,
                                    ctx.width, ctx.height, AV_PIX_FMT_YUV420P,
                                    SWS_BILINEAR.toInt(),
                                    null, null, null,
                                )
                            }
                            toOutput(pts, frame, formatCtx.value!!, videoStream!!, codecCtx.value!!, swsCtx.value!!)
                        } finally {
                            DestroyImage(frame)
                        }
                    }
                    av_write_trailer(formatCtx.value)
                } finally {
                    if (swsCtx.value != nativeNullPtr) sws_freeContext(swsCtx.value)
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
    }

    private fun toOutput(
        pts: Duration,
        image: CPointer<Image>,
        formatCtx: CPointer<AVFormatContext>,
        videoStream: CPointer<AVStream>,
        codecCtx: CPointer<AVCodecContext>,
        swsCtx: CPointer<SwsContext>
    ) = memScoped {
        val frame = alloc<CPointerVar<AVFrame>>().also { it.value = av_frame_alloc() }
        val packet = alloc<CPointerVar<AVPacket>>().also { it.value = av_packet_alloc() }
        try {
            frame.value!!.pointed.let { f ->
                f.width = codecCtx.pointed.width
                f.height = codecCtx.pointed.height
                f.format = codecCtx.pointed.pix_fmt
                if (av_frame_get_buffer(f.ptr, 0) < 0) throw Error("av_frame_get_buffer 失败")
                if (av_frame_make_writable(f.ptr) < 0) throw Error("av_frame_make_writable 失败")
                val srcData = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
                val srcLinesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }
                sws_scale(
                    swsCtx,
                    srcData.ptr, srcLinesize.ptr,
                    0, f.height,
                    f.data, f.linesize
                )
                f.pts = pts.inWholeMicroseconds * 90 / 1000
            }
            if (avcodec_send_frame(codecCtx, frame.value) < 0) throw Error("avcodec_send_frame 失败")
            while (0 <= avcodec_receive_packet(codecCtx, packet.value)) {
                packet.value!!.pointed.stream_index = videoStream.pointed.index
                av_interleaved_write_frame(formatCtx, packet.value)
                av_packet_unref(packet.value)
            }
        } finally {
            av_packet_free(packet.ptr)
            av_frame_free(frame.ptr)
        }
    }
}
