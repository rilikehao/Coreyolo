import common.AppConfig
import common.Command
import common.InputRtsp
import common.StringFormat.toString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AV_PIX_FMT_RGB24

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(id: Int, inputRtsp: InputRtsp, input: Flow<CPointer<AVPacket>?>) :
        common.DecoderFFmpeg(inputRtsp, input, { "${it}_rkmpp" })

    class EncoderVideoH264(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, input, "h264_rkmpp", AV_PIX_FMT_RGB24, arrayOf(
                "rc_mode" to "CQP",
                "qp_init" to AppConfig.instance.processing.qH264.toString(1),
            )
        )

    class EncoderVideoH265(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, input, "hevc_rkmpp", AV_PIX_FMT_RGB24, arrayOf(
                "rc_mode" to "CQP",
                "qp_init" to AppConfig.instance.processing.qH265.toString(1),
            )
        )
}
