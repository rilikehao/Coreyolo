import common.AppConfig
import common.Command
import common.Input
import common.StringFormat.toString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AV_PIX_FMT_NONE
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(id: Int, input: Input, packets: Flow<CPointer<AVPacket>?>) :
        DecoderACL(id, input, packets)

    class EncoderVideoH264(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, input, "libx264", AV_PIX_FMT_YUV420P, arrayOf(
                "fflags" to "nobuffer",
                "preset" to "superfast",
                "qp" to AppConfig.instance.processing.qH264.toString(1),
            ), maxBFrames = 0
        )

    class EncoderVideoH265(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(id, input, "libx265", AV_PIX_FMT_NONE, arrayOf())
}
