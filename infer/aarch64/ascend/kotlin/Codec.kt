import common.AppConfig
import common.Command
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AV_PIX_FMT_NONE
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(id: Int) : DecoderACL(id)

    class EncoderVideoH264(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, input, "libx264", AV_PIX_FMT_YUV420P, arrayOf(
                "preset" to "superfast",
                "qp" to AppConfig.instance.processing.qH264.toString(1),
                "tune" to "zerolatency",
            )
        )

    class EncoderVideoH265(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(id, input, "libx265", AV_PIX_FMT_NONE, arrayOf())
}
