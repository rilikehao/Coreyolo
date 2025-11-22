import common.AppConfig
import common.Command
import common.Input
import common.StringFormat.toString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(id: Int, input: Input, packets: Flow<CPointer<AVPacket>?>) :
        common.DecoderFFmpeg(input, packets, { it })

    class EncoderVideoH264(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, name, input, "libx264", AV_PIX_FMT_YUV420P, arrayOf(
                "fflags" to "nobuffer",
                "preset" to "veryfast",
                "qp" to AppConfig.instance.processing.qH264.toString(1),
            ), maxBFrames = 0
        )

    class EncoderVideoH265(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, name, input, "libx265", AV_PIX_FMT_YUV420P, arrayOf(
                "preset" to "veryfast",
                "qp" to AppConfig.instance.processing.qH265.toString(1),
                "x265-params" to "repeat-headers=1",
            )
        )
}
