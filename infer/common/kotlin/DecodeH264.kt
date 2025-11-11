package common

import Device
import ToRGBImage
import common.InputRtsp.maxFrames
import common.Utils.check
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import platform.ffmpeg.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object DecodeH264 : (InputRtsp.Output) -> Flow<Frame> {
    override fun invoke(input: InputRtsp.Output): Flow<Frame> {
        val codecParams = input.stream.codecpar
        val timeBase = input.stream.time_base
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val device = Device()
        val toRGBImage = ToRGBImage()
        val codec = avcodec_find_decoder_by_name(Device.H264_DECODER_NAME)
            .check("avcodec_find_decoder")
        val codecCtx = avcodec_alloc_context3(codec)!!
        avcodec_parameters_to_context(codecCtx, codecParams)
        device.bind(codecCtx.pointed)
        avcodec_open2(codecCtx, codec, null).check("avcodec_open2")
        val frame = av_frame_alloc()!!
        return input.packets.transform { packet ->
            try {
                avcodec_send_packet(codecCtx, packet).check("avcodec_send_packet")
                while (avcodec_receive_frame(codecCtx, frame) == 0) {
                    val pts = frame.pointed.pts.toDouble() * timeBase.num / timeBase.den
                    val start = Instant.fromEpochMilliseconds(input.formatCtx.start_time_realtime / 1000L)
                    val timestamp = start + pts.seconds
                    if (inputFrames == 0L) timestamp0 = timestamp
                    if (maxFrames(timestamp - timestamp0) < inputFrames) continue
                    ++inputFrames
                    val swFrame = Device.transferFrame(frame)
                    emit(Frame(timestamp, toRGBImage(swFrame.pointed), null))
                    av_frame_free(cValuesOf(swFrame))
                }
            } finally {
                if (packet != null) av_packet_unref(packet)
            }
        }.onCompletion {
            av_frame_free(cValuesOf(frame))
            avcodec_free_context(cValuesOf(codecCtx))
            toRGBImage.close()
            device.close()
        }.buffer(Channel.UNLIMITED)
    }
}
