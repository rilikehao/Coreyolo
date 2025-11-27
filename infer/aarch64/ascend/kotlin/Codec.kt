import common.AppConfig
import common.Command
import common.Input
import common.StringFormat.toString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AV_PIX_FMT_NONE
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(input: Input, packets: Flow<CPointer<AVPacket>?>) :
        DecoderACL(0, input, packets)

    class EncoderVideoH264(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        EncoderACL(id, name, input, "h264")

    class EncoderVideoH264Fast(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, name, input, "libx264", AV_PIX_FMT_YUV420P, arrayOf(
                "fflags" to "nobuffer",
                "preset" to "ultrafast",
                "qp" to (AppConfig.instance.processing.qH264 - 4.0).toString(1),
            )
        )

    class EncoderVideoH265(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(id, name, input, "libx265", AV_PIX_FMT_NONE, arrayOf())
}
