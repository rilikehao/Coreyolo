package common

import Device
import ToRGBImage
import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import platform.ffmpeg.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class RtspInput(val url: String) : Video {
    override fun close() = Unit // No close needed

    override fun frames(): Flow<Video.Frame> {
        val formatCtx = cPointer { ptr ->
            withOptions("fflags" to "nobuffer", "rtsp_transport" to "tcp") {
                avformat_open_input(ptr, url, null, it).check("avformat_open_input")
            }
        }
        avformat_find_stream_info(formatCtx, null).check("avformat_find_stream_info")
        var videoStreamIndex = -1
        for (i in 0..<formatCtx.pointed.nb_streams.toInt()) {
            if (formatCtx.pointed.streams!![i]!!.pointed.codecpar!!.pointed.codec_type == AVMEDIA_TYPE_VIDEO) {
                videoStreamIndex = i
                break
            }
        }
        if (videoStreamIndex == -1) throw Error("未找到视频流")
        val codecParams = formatCtx.pointed.streams!![videoStreamIndex]!!.pointed.codecpar
        val timeBase = formatCtx.pointed.streams!![videoStreamIndex]!!.pointed.time_base
        val packet = av_packet_alloc()!!
        val packets = flow {
            while (av_read_frame(formatCtx, packet) != AVERROR_EOF) {
                if (packet.pointed.stream_index == videoStreamIndex) emit(packet as CPointer<AVPacket>?)
            }
        }.onCompletion {
            avformat_close_input(cValuesOf(formatCtx))
            emit(null)
        }
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val device = Device()
        val toRGBImage = ToRGBImage()
        val codec = when (val id = codecParams!!.pointed.codec_id) {
            AV_CODEC_ID_H264 -> avcodec_find_decoder_by_name(Device.H264_DECODER_NAME)
            else -> avcodec_find_decoder(id)
        }.check("avcodec_find_decoder")
        val codecCtx = avcodec_alloc_context3(codec)!!
        avcodec_parameters_to_context(codecCtx, codecParams)
        device.bind(codecCtx.pointed)
        avcodec_open2(codecCtx, codec, null).check("avcodec_open2")
        val frame = av_frame_alloc()!!
        return packets.transform { packet ->
            try {
                avcodec_send_packet(codecCtx, packet).check("avcodec_send_packet")
                while (avcodec_receive_frame(codecCtx, frame) == 0) {
                    val pts = frame.pointed.pts.toDouble() * timeBase.num / timeBase.den
                    val start = Instant.fromEpochMilliseconds(formatCtx.pointed.start_time_realtime / 1000L)
                    val timestamp = start + pts.seconds
                    if (inputFrames == 0L) timestamp0 = timestamp
                    if (maxFrames(timestamp - timestamp0) < inputFrames) continue
                    ++inputFrames
                    emit(Video.Frame(timestamp, toRGBImage(frame.pointed)))
                }
            } finally {
                if (packet != null) av_packet_unref(packet)
            }
        }.onCompletion {
            av_frame_free(cValuesOf(frame))
            avcodec_free_context(cValuesOf(codecCtx))
            toRGBImage.close()
            device.close()
            av_packet_free(cValuesOf(packet))
        }.buffer(Channel.UNLIMITED)
    }

    fun maxFrames(duration: Duration) = (duration * AppConfig.instance.processing.fpsDecode).inWholeSeconds
}
