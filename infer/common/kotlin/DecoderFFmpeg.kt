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
    val input: Input,
    val packets: Flow<CPointer<AVPacket>?>,
    val changeName: (String) -> String,
    val emitNullWhenFinished: Boolean = true,
) : Decoder {
    var codecCtx: CPointer<AVCodecContext>? = null

    override suspend fun invoke(): Flow<Command.CommandImage> {
        val device = Device()
        val toRGBImage = ToRGBImage()
        val frame = av_frame_alloc()!!
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        return packets.onCompletion {
            if (emitNullWhenFinished) emit(null)
        }.transform { packet ->
            try {
                if (codecCtx == null && packet != null) {
                    val codecId = input.getStream().codecpar!!.pointed.codec_id
                    val name = avcodec_find_decoder(codecId)!!.pointed.name!!.toKString()
                    val codec = avcodec_find_decoder_by_name(changeName(name)).check("avcodec_find_decoder")
                    codecCtx = avcodec_alloc_context3(codec)!!
                    avcodec_parameters_to_context(codecCtx, input.getStream().codecpar)
                    device.bind(codecCtx!!.pointed)
                    avcodec_open2(codecCtx, codec, null).check("avcodec_open2")
                }
                if (codecCtx == null) return@transform
                avcodec_send_packet(codecCtx, packet).check("avcodec_send_packet")
                while (avcodec_receive_frame(codecCtx, frame) == 0) {
                    val base = input.getStream().time_base
                    val pts = frame.pointed.pts.toDouble() * base.num / base.den
                    val start = Instant.fromEpochMilliseconds(input.getFormatCtx().start_time_realtime / 1000L)
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
            if (codecCtx != null) avcodec_free_context(cValuesOf(codecCtx))
            av_frame_free(cValuesOf(frame))
            toRGBImage.close()
            device.close()
        }
    }
}
