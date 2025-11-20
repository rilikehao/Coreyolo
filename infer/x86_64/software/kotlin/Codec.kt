import common.AppConfig
import common.Command
import common.InputRtsp
import common.StringFormat.toString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(id: Int, inputRtsp: InputRtsp, input: Flow<CPointer<AVPacket>?>) :
        common.DecoderFFmpeg(inputRtsp, input, { it })

    class EncoderVideoH264(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, input, "libx264", AV_PIX_FMT_YUV420P, arrayOf(
                "preset" to "veryfast",
                "qp" to AppConfig.instance.processing.qH264.toString(1),
            )
        )


    class EncoderVideoH265(id: String, input: Flow<Command.CommandImage>) :
        common.EncoderFFmpeg(
            id, input, "libx265", AV_PIX_FMT_YUV420P, arrayOf(
                "preset" to "veryfast",
                "qp" to AppConfig.instance.processing.qH265.toString(1),
                "x265-params" to "repeat-headers=1",
            )
        )
}
