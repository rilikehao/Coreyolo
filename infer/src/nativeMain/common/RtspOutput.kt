package common

import Device
import FromRGBImage
import cnames.structs.Image
import co.touchlab.kermit.Logger
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import common.Utils.withOptions
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.pointed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import platform.ffmpeg.*
import platform.native.DestroyImage
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object RtspOutput : suspend (String, String, String, Flow<Video.Frame>) -> Unit {
    override suspend fun invoke(original: String, processed: String, id: String, inputFlow: Flow<Video.Frame>) {
        FromRGBImage().use { fromRGBImage ->
            Context(original, fromRGBImage).use { originalCtx ->
                Context(processed, fromRGBImage).use { processedCtx ->
                    var inputFrames = 0L
                    var outputFrames = 0L
                    var frame0 = TimeSource.Monotonic.markNow()
                    var timestamp0 = Clock.System.now()
                    val frames = inputFlow.map { input ->
                        if (inputFrames++ == 0L) timestamp0 = input.timestamp
                        Logger.i {
                            if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                            val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                            val delayed = frame0.elapsedNow() - (input.timestamp - timestamp0)
                            val delayMs = max(0L, delayed.inWholeMilliseconds)
                            "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
                        }
                        Pair(
                            originalCtx.createFrame(input.timestamp, input.original),
                            processedCtx.createFrame(input.timestamp, input.processed!!),
                        )
                    }.onCompletion {
                        emit(Pair(null, null))
                    }
                    frames.collect { (frameOriginal, frameProcessed) ->
                        originalCtx.send(frameOriginal)
                        processedCtx.send(frameProcessed)
                    }
                }
            }
        }
    }

    class Context(url: String, val fromRGBImage: FromRGBImage) : AutoCloseable {
        val format = cPointer {
            avformat_alloc_output_context2(it, null, "rtsp", url).check("avformat_alloc_output_context2")
        }

        val codec = avcodec_find_encoder_by_name(Device.ENCODER_NAME).check("avcodec_find_encoder_by_name")

        val codecCtx = avcodec_alloc_context3(codec)!!.apply {
            pointed.codec_type = AVMEDIA_TYPE_VIDEO
            pointed.pix_fmt = Device.ENCODER_FORMAT
            pointed.time_base.num = 1
            pointed.time_base.den = 90000
        }

        var videoStream: CPointer<AVStream>? = null

        val frame = av_frame_alloc()!!
        val packet = av_packet_alloc()!!

        override fun close() {
            if (codecCtx.pointed.width != 0) av_write_trailer(format)
            av_packet_free(cValuesOf(packet))
            av_frame_free(cValuesOf(frame))
            avcodec_free_context(cValuesOf(codecCtx))
            avformat_free_context(format)
        }

        fun createFrame(timestamp: Instant, image: CPointer<Image>): CPointer<AVFrame>? {
            frame.pointed.pts = timestamp.toEpochMilliseconds() * 90
            fromRGBImage(frame.pointed, image)
            DestroyImage(image)
            return frame
        }

        fun send(frame: CPointer<AVFrame>?) {
            if (codecCtx.pointed.width == 0 && frame != null) {
                codecCtx.pointed.width = frame.pointed.width
                codecCtx.pointed.height = frame.pointed.height
                withOptions(*Device.encoderOptions()) {
                    avcodec_open2(codecCtx, codec, it).check("avcodec_open2")
                }
                videoStream = avformat_new_stream(format, codec).check("avformat_new_stream")
                avcodec_parameters_from_context(videoStream!!.pointed.codecpar, codecCtx)
                format.pointed.start_time_realtime = frame.pointed.pts / 90L * 1000L
                withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                    avformat_write_header(format, it).check("avformat_write_header")
                }
            }
            avcodec_send_frame(codecCtx, frame).check("avcodec_send_frame")
            if (frame != null) av_frame_unref(frame)
            while (0 <= avcodec_receive_packet(codecCtx, packet)) {
                packet.pointed.stream_index = videoStream!!.pointed.index
                av_interleaved_write_frame(format, packet)
                av_packet_unref(packet)
            }
        }
    }
}
