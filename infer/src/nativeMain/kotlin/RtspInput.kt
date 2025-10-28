import Utils.cPointer
import Utils.check
import Utils.use
import Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class RtspInput(val url: String) : Video {
    override fun close() = Unit // No close needed

    override fun frames() = flow {
        Device().use { device ->
            var videoStreamIndex = -1
            var inputFrames = 0L
            var timestamp0 = Duration.ZERO
            cPointer { ptr ->
                withOptions("fflags" to "nobuffer", "rtsp_transport" to "tcp") {
                    avformat_open_input(ptr, url, null, it).check("avformat_open_input")
                }
            }.use({ avformat_close_input(cValuesOf(it)) }) { formatCtx ->
                avformat_find_stream_info(formatCtx.ptr, null).check("avformat_find_stream_info")
                for (i in 0..<formatCtx.nb_streams.toInt()) {
                    if (formatCtx.streams!![i]!!.pointed.codecpar!!.pointed.codec_type == AVMEDIA_TYPE_VIDEO) {
                        videoStreamIndex = i
                        break
                    }
                }
                if (videoStreamIndex == -1) throw Error("未找到视频流")
                val codecParams = formatCtx.streams!![videoStreamIndex]!!.pointed.codecpar!!
                val codec = when (val id = codecParams.pointed.codec_id) {
                    AV_CODEC_ID_H264 -> avcodec_find_decoder_by_name(Device.H264_DECODER_NAME)
                    else -> avcodec_find_decoder(id)
                }.check("avcodec_find_decoder")
                avcodec_alloc_context3(codec)!!.use({ avcodec_free_context(cValuesOf(it)) }) { codecCtx ->
                    avcodec_parameters_to_context(codecCtx.ptr, codecParams)
                    device.bind(codecCtx)
                    avcodec_open2(codecCtx.ptr, codec, null).check("avcodec_open2")
                    ToRGBImage().use { toRGBImage ->
                        av_packet_alloc()!!.use({ av_packet_free(cValuesOf(it)) }) { packet ->
                            av_frame_alloc()!!.use({ av_frame_free(cValuesOf(it)) }) { frame ->
                                val timeBase = formatCtx.streams!![videoStreamIndex]!!.pointed.time_base
                                while (true) {
                                    val ret = av_read_frame(formatCtx.ptr, packet.ptr)
                                    if (ret < 0 && ret != AVERROR_EOF) continue
                                    try {
                                        if (packet.stream_index == videoStreamIndex) {
                                            val pPacket = if (ret == AVERROR_EOF) null else packet.ptr
                                            avcodec_send_packet(codecCtx.ptr, pPacket).check("avcodec_send_packet")
                                            while (avcodec_receive_frame(codecCtx.ptr, frame.ptr) == 0) {
                                                val pts = frame.pts.toDouble() * timeBase.num / timeBase.den
                                                val timestamp = pts.seconds
                                                if (inputFrames == 0L) timestamp0 = timestamp
                                                if (maxFrames(timestamp - timestamp0) < inputFrames) continue
                                                ++inputFrames
                                                emit(Video.Frame(timestamp, toRGBImage(frame)))
                                            }
                                        }
                                    } finally {
                                        av_packet_unref(packet.ptr)
                                    }
                                    if (ret == AVERROR_EOF) break
                                }
                            }
                        }
                    }
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsDecode).inWholeSeconds
}
