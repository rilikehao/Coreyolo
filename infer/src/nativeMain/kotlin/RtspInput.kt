import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class RtspInput(val url: String) : Video {
    override fun close() = Unit // No close needed

    override fun frames() = flow {
        memScoped {
            val deviceRef = memScope.alloc<CPointerVar<AVBufferRef>>()
            Device(deviceRef).use { device ->
                var videoStreamIndex = -1
                val options = alloc<CPointerVar<AVDictionary>>()
                av_dict_set(options.ptr, "fflags", "nobuffer", 0)
                av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
                val formatCtx = alloc<CPointerVar<AVFormatContext>>()
                avformat_open_input(formatCtx.ptr, url, null, options.ptr).check("avformat_open_input")
                av_dict_free(options.ptr)
                try {
                    avformat_find_stream_info(formatCtx.value, null).check("avformat_find_stream_info")
                    for (i in 0..<formatCtx.pointed!!.nb_streams.toInt()) {
                        val stream = formatCtx.pointed!!.streams!![i]!!
                        if (stream.pointed.codecpar!!.pointed.codec_type == AVMEDIA_TYPE_VIDEO) {
                            videoStreamIndex = i
                            break
                        }
                    }
                    if (videoStreamIndex == -1) throw Error("未找到视频流")
                    val codecParams = formatCtx.pointed!!.streams!![videoStreamIndex]!!.pointed.codecpar!!
                    val codec = when (val id = codecParams.pointed.codec_id) {
                        AV_CODEC_ID_H264 -> avcodec_find_decoder_by_name(Device.H264_DECODER_NAME)
                        else -> avcodec_find_decoder(id)
                    } ?: throw Error("avcodec_find_decoder 失败")
                    val codecCtx =
                        alloc<CPointerVar<AVCodecContext>>().also { it.value = avcodec_alloc_context3(codec) }
                    try {
                        avcodec_parameters_to_context(codecCtx.value, codecParams)
                        device.bind(codecCtx.pointed!!)
                        avcodec_open2(codecCtx.value, codec, options.ptr).check("avcodec_open2")
                        ToRGBImage(this, codecCtx.pointed!!).use { toRGBImage ->
                            val packet = alloc<CPointerVar<AVPacket>>().also { it.value = av_packet_alloc() }
                            val frame = alloc<CPointerVar<AVFrame>>().also { it.value = av_frame_alloc() }
                            try {
                                val timeBase = formatCtx.pointed!!.streams!![videoStreamIndex]!!.pointed.time_base
                                while (true) {
                                    val ret = av_read_frame(formatCtx.value, packet.value)
                                    if (ret == AVERROR_EOF) break
                                    if (ret < 0) continue
                                    try {
                                        if (packet.pointed!!.stream_index == videoStreamIndex) {
                                            avcodec_send_packet(codecCtx.value, packet.value)
                                                .check("avcodec_send_packet")
                                            while (avcodec_receive_frame(codecCtx.value, frame.value) == 0) {
                                                val pts = frame.pointed!!.pts
                                                val timestamp = pts.toDouble() * timeBase.num / timeBase.den
                                                emit(Video.Frame(timestamp.seconds, toRGBImage(frame)))
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
                        }
                    } finally {
                        avcodec_free_context(codecCtx.ptr)
                    }
                } finally {
                    avformat_close_input(formatCtx.ptr)
                }
            }
        }
    }
}
