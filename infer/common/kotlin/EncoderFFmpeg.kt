package common

import FromRGBImage
import co.touchlab.kermit.Logger
import common.StringFormat.toString
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import platform.ffmpeg.*
import platform.native.DestroyImage
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
open class EncoderFFmpeg(val id: String, name: String, format: Int) : Encoder {
    val codec = avcodec_find_encoder_by_name(name).check("avcodec_find_encoder_by_name")
    val codecCtx = avcodec_alloc_context3(codec)!!.apply {
        pointed.flags = pointed.flags or AV_CODEC_FLAG_GLOBAL_HEADER
        pointed.codec_type = AVMEDIA_TYPE_VIDEO
        pointed.pix_fmt = format
        pointed.time_base.num = 1
        pointed.time_base.den = 90000
        pointed.max_b_frames = 0
        pointed.gop_size = 10
    }

    lateinit var options: Array<Pair<String, String>>
    lateinit var timestamp0: Instant

    override fun startTimeRealtime() = timestamp0.toEpochMilliseconds()

    override fun initStream(formatContext: AVFormatContext) =
        avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_from_context(it.pointed.codecpar, codecCtx)
        }

    override fun invoke(input: Flow<Command.CommandImage>): Flow<CPointer<AVPacket>> {
        val fromRGBImage = FromRGBImage()
        val frame = av_frame_alloc()!!
        val packet = av_packet_alloc()!!
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()

        return input.map { commandImage ->
            if (inputFrames++ == 0L) timestamp0 = commandImage.timestamp
            Logger.i {
                if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                val delayed = frame0.elapsedNow() - (commandImage.timestamp - timestamp0)
                val delayMs = max(0L, delayed.inWholeMilliseconds)
                "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
            }
            frame.pointed.pts = (commandImage.timestamp - timestamp0).inWholeMicroseconds * 90 / 1000
            fromRGBImage(frame.pointed, commandImage.data)
            DestroyImage(commandImage.data)
            frame as CPointer<AVFrame>?
        }.onCompletion { emit(null) }.transform { frame ->
            if (codecCtx.pointed.width == 0 && frame != null) {
                codecCtx.pointed.width = frame.pointed.width
                codecCtx.pointed.height = frame.pointed.height
                withOptions(*options) {
                    avcodec_open2(codecCtx, codec, it).check("avcodec_open2")
                }
            }
            avcodec_send_frame(codecCtx, frame).check("avcodec_send_frame")
            if (frame != null) av_frame_unref(frame)
            while (0 <= avcodec_receive_packet(codecCtx, packet)) {
                emit(packet)
            }
        }.onCompletion {
            av_packet_free(cValuesOf(packet))
            av_frame_free(cValuesOf(frame))
            avcodec_free_context(cValuesOf(codecCtx))
            fromRGBImage.close()
        }
    }
}
