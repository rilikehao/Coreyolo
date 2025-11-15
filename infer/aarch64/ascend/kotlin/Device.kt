import common.AppConfig
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AV_PIX_FMT_RGB24

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264"

        const val ENCODER_NAME = "librav1e"
        const val ENCODER_FORMAT = AV_PIX_FMT_RGB24

        fun encoderOptions() = arrayOf(
            "speed" to "10",
            "lookahead" to "0",
            "qp" to AppConfig.instance.processing.q.toString(1),
        )
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}
