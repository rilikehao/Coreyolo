package common

import Device
import ToRGBImage
import common.Utils.check
import kotlinx.cinterop.*
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
open class DecoderFFmpeg(
    val inputRtsp: InputRtsp,
    val input: Flow<CPointer<AVPacket>?>,
    val changeName: (String) -> String,
) : Decoder {
    override suspend fun invoke(): Flow<Command.CommandImage> {
        val device = Device()
        val toRGBImage = ToRGBImage()
        val name = avcodec_find_decoder(inputRtsp.stream.codecpar!!.pointed.codec_id)!!.pointed.name!!.toKString()
        val codec = avcodec_find_decoder_by_name(changeName(name)).check("avcodec_find_decoder")
        val codecCtx = avcodec_alloc_context3(codec)!!
        avcodec_parameters_to_context(codecCtx, inputRtsp.stream.codecpar)
        device.bind(codecCtx.pointed)
        avcodec_open2(codecCtx, codec, null).check("avcodec_open2")
        val frame = av_frame_alloc()!!
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        return input.onCompletion {
            emit(null)
        }.transform { packet ->
            try {
                avcodec_send_packet(codecCtx, packet).check("avcodec_send_packet")
                while (avcodec_receive_frame(codecCtx, frame) == 0) {
                    val base = inputRtsp.stream.time_base
                    val pts = frame.pointed.pts.toDouble() * base.num / base.den
                    val start = Instant.fromEpochMilliseconds(inputRtsp.formatCtx.start_time_realtime / 1000L)
                    val timestamp = start + pts.seconds
                    if (inputFrames == 0L) timestamp0 = timestamp
                    if (Decoder.maxFrames(timestamp - timestamp0) < inputFrames) continue
                    ++inputFrames
                    emit(Command.CommandImage(timestamp, toRGBImage(frame.pointed)))
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
