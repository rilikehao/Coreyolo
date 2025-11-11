package common

import FromRGBImage
import co.touchlab.kermit.Logger
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import platform.ffmpeg.AVFormatContext
import platform.ffmpeg.AVStream
import platform.native.DestroyImage
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class,ExperimentalTime::class)
object EncodeH264 : (String, OutputRtsp.Context, OutputRtsp.Context, Flow<Frame>) -> Flow<OutputRtsp.Input> {
    override fun invoke(id: String, original: OutputRtsp.Context, processed: OutputRtsp.Context, input: Flow<Frame>): Flow<OutputRtsp.Input> {
        val fromRGBImage = FromRGBImage()
        input.transform { frame ->
            var inputFrames = 0L
            var outputFrames = 0L
            var frame0 = TimeSource.Monotonic.markNow()
            var timestamp0 = Clock.System.now()
            if (inputFrames++ == 0L) timestamp0 = frame.timestamp
            Logger.i {
                if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                val delayed = frame0.elapsedNow() - (frame.timestamp - timestamp0)
                val delayMs = max(0L, delayed.inWholeMilliseconds)
                "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
            }
            original.frame.pointed.pts = frame.timestamp.toEpochMilliseconds() * 90
            fromRGBImage(original.frame.pointed, frame.original)
            DestroyImage(frame.original)
            avcodec_send_frame(codecCtx, frame).check("avcodec_send_frame")
            if (frame != null) av_frame_unref(frame)
            while (0 <= avcodec_receive_packet(codecCtx, packet)) {
                packet.pointed.stream_index = videoStream!!.pointed.index
                av_interleaved_write_frame(format, packet)
                av_packet_unref(packet)
            }

            emit(OutputRtsp.Input(original, ))
            Pair(
                originalCtx.createFrame(input.timestamp, input.original),
                processedCtx.createFrame(input.timestamp, input.processed!!),
            )


        }.onCompletion {
            emit(Pair(null, null))
        }.onCompletion {
            fromRGBImage.close()
        }
    }
}
