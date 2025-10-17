import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class RtspInput(val url: String) : Video {
    override fun close() = Unit // No close needed

    override fun frames() = flow {
        memScoped {
            var videoStreamIndex = -1
            val options = alloc<CPointerVar<AVDictionary>>()
            av_dict_set(options.ptr, "fflags", "nobuffer", 0)
            av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
            val formatCtx = alloc<CPointerVar<AVFormatContext>>()
            val timeBegin = Clock.System.now()
            if (avformat_open_input(formatCtx.ptr, url, null, options.ptr) != 0) throw Error("avformat_open_input 失败")
            try {
                if (avformat_find_stream_info(formatCtx.value, null) < 0) throw Error("avformat_find_stream_info 失败")
                for (i in 0..<formatCtx.pointed!!.nb_streams.toInt()) {
                    val stream = formatCtx.pointed!!.streams!![i]!!
                    if (stream.pointed.codecpar!!.pointed.codec_type == AVMEDIA_TYPE_VIDEO) {
                        videoStreamIndex = i
                        break
                    }
                }
                if (videoStreamIndex == -1) throw Error("未找到视频流")
                val codecParams = formatCtx.pointed!!.streams!![videoStreamIndex]!!.pointed.codecpar!!
                val codec = avcodec_find_decoder(codecParams.pointed.codec_id)
                    ?: throw Error("avcodec_find_decoder 失败")
                val codecCtx = alloc<CPointerVar<AVCodecContext>>().also { it.value = avcodec_alloc_context3(codec) }
                try {
                    avcodec_parameters_to_context(codecCtx.value, codecParams)
                    if (avcodec_open2(codecCtx.value, codec, null) < 0) throw Error("avcodec_open2 失败")

                    val hwDeviceCtx = codecCtx.pointed?.hw_device_ctx
                    if (hwDeviceCtx != null) {
                        println("FFmpeg 使用硬件加速解码")
                    } else {
                        println("FFmpeg 使用软件解码")
                    }
                    val swsCtx = alloc<CPointerVar<SwsContext>>().also {
                        it.value = sws_getContext(
                            codecParams.pointed.width,
                            codecParams.pointed.height,
                            codecCtx.pointed!!.pix_fmt,
                            codecParams.pointed.width,
                            codecParams.pointed.height,
                            AV_PIX_FMT_RGB24,
                            SWS_BILINEAR.toInt(),
                            null, null, null,
                        )
                    }
                    try {
                        val packet = alloc<CPointerVar<AVPacket>>().also { it.value = av_packet_alloc() }
                        val frame = alloc<CPointerVar<AVFrame>>().also { it.value = av_frame_alloc() }
                        try {
                            val timeBase = formatCtx.pointed!!.streams!![videoStreamIndex]!!.pointed.time_base
                            while (true) {
                                val ret = av_read_frame(formatCtx.value, packet.value)
                                if (ret < 0) {
                                    if (ret == AVERROR_EOF) break
                                    continue
                                }
                                try {
                                    if (packet.pointed!!.stream_index == videoStreamIndex) {
                                        if (avcodec_send_packet(codecCtx.value, packet.value) < 0) continue
                                        while (0 <= avcodec_receive_frame(codecCtx.value, frame.value)) {
                                            val present = frame.pointed!!.pts.toDouble() * timeBase.num / timeBase.den
                                            emit(Pair(timeBegin + present.seconds, toImage(frame, swsCtx)))
                                        }
                                    }
                                } finally {
                                    av_packet_unref(packet.value)
                                }
                            }
                        } finally {
                            av_frame_free(frame.ptr)
                            av_packet_free(packet.ptr)
                        }
                    } finally {
                        sws_freeContext(swsCtx.value)
                    }
                } finally {
                    avcodec_free_context(codecCtx.ptr)
                }
            } finally {
                avformat_close_input(formatCtx.ptr)
            }
        }
    }

    fun toImage(frame: CPointerVar<AVFrame>, swsCtx: CPointerVar<SwsContext>) = memScoped {
        CreateImageRGB24(frame.pointed!!.width, frame.pointed!!.height)!!.also { image ->
            val data = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
            val linesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }
            sws_scale(
                swsCtx.value,
                frame.pointed!!.data, frame.pointed!!.linesize,
                0, frame.pointed!!.height,
                data.ptr, linesize.ptr,
            )
        }
    }
}
