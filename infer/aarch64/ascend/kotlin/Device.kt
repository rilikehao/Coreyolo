import common.AppConfig
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264"

        const val ENCODER_NAME_VIEW = "librav1e"
        const val ENCODER_NAME_STORAGE = "librav1e"
        const val ENCODER_FORMAT = AV_PIX_FMT_YUV420P

        fun encoderOptionsView() = arrayOf(
            "rav1e-params" to "speed=10",
            "qp" to (AppConfig.instance.processing.q + 4.0).toString(1),
            "lookahead" to "0",
        )

        fun encoderOptionsStorage() = arrayOf(
            "rav1e-params" to "speed=10",
            "qp" to (AppConfig.instance.processing.q + 4.0).toString(1),
        )
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}

