import cnames.structs.AVDictionary
import cnames.structs.Image
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class)
class RtspOutput(val url: String) {
    suspend fun runReceive(receive: Flow<Pair<Duration, CPointer<Image>>>) = memScoped {
        val options = alloc<CPointerVar<AVDictionary>>()
        av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
        av_dict_set(options.ptr, "tune", "zerolatency", 0)

        val formatCtx = alloc<CPointerVar<AVFormatContext>>()
        if (avformat_alloc_output_context2(formatCtx.ptr, null, "rtsp", url) < 0)
            throw Error("avformat_alloc_output_context2 失败")
        try {
            val codec = avcodec_find_encoder(AV_CODEC_ID_H264)
                ?: throw Error("avcodec_find_encoder H264 失败")
            val codecCtx = alloc<CPointerVar<AVCodecContext>>().also {
                it.value = avcodec_alloc_context3(codec)
            }
            try {
                val ctx = codecCtx.value!!.pointed
                ctx.codec_type = AVMEDIA_TYPE_VIDEO
                ctx.width = 1920
                ctx.height = 1080
                ctx.pix_fmt = AV_PIX_FMT_YUV420P
                ctx.gop_size = 10
                ctx.max_b_frames = 1

                if (avcodec_open2(codecCtx.value, codec, null) < 0)
                    throw Error("avcodec_open2 失败")

                val videoStream = avformat_new_stream(formatCtx.value, codec)
                    ?: throw Error("avformat_new_stream 失败")
                avcodec_parameters_from_context(videoStream.pointed.codecpar, codecCtx.value)
                // time_base handled by avformat parameters from context

                val pbPtr = alloc<CPointerVar<AVIOContext>>()
                if (avio_open(pbPtr.ptr, url, AVIO_FLAG_WRITE) < 0)
                    throw Error("avio_open 失败")
                formatCtx.value!!.pointed.pb = pbPtr.value
                try {
                    if (avformat_write_header(formatCtx.value, options.ptr) < 0)
                        throw Error("avformat_write_header 失败")

                    val swsCtx = alloc<CPointerVar<SwsContext>>().also {
                        it.value = sws_getContext(
                            codecCtx.value!!.pointed.width,
                            codecCtx.value!!.pointed.height,
                            AV_PIX_FMT_RGB24,
                            codecCtx.value!!.pointed.width,
                            codecCtx.value!!.pointed.height,
                            AV_PIX_FMT_YUV420P,
                            SWS_BILINEAR.toInt(),
                            null, null, null,
                        )
                    }
                    try {
                        receive.collect { (pts, frame) ->
                            toOutput(pts, frame, formatCtx.value!!, videoStream, codecCtx.value!!, swsCtx.value!!)
                        }
                    } finally {
                        sws_freeContext(swsCtx.value)
                        av_write_trailer(formatCtx.value)
                    }
                } finally {
                    avio_closep(pbPtr.ptr)
                }
            } finally {
                avcodec_free_context(codecCtx.ptr)
            }
        } finally {
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

                if (av_frame_get_buffer(f.ptr, 0) < 0)
                    throw Error("av_frame_get_buffer 失败")

                if (av_frame_make_writable(f.ptr) < 0)
                    throw Error("av_frame_make_writable 失败")

                val srcData = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
                val srcLinesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }

                sws_scale(
                    swsCtx,
                    srcData.ptr, srcLinesize.ptr,
                    0, 1080,
                    f.data, f.linesize
                )

                f.pts = (pts.inWholeMilliseconds * 25 / 1000)
            }

            if (avcodec_send_frame(codecCtx, frame.value) < 0) return@memScoped

            while (0 <= avcodec_receive_packet(codecCtx, packet.value)) {
                packet.value!!.pointed.let { pkt ->
                    pkt.stream_index = videoStream.pointed.index
                    // av_packet_rescale_ts(pkt.ptr, codecCtx.pointed.time_base, videoStream.pointed.time_base)
                }

                av_interleaved_write_frame(formatCtx, packet.value)
                av_packet_unref(packet.value)
            }
        } finally {
            av_frame_free(frame.ptr)
            av_packet_free(packet.ptr)
        }
    }
}
