import common.AppConfig
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264"

        const val ENCODER_NAME_VIEW = "libx264"
        const val ENCODER_NAME_STORAGE = "libx264"
        const val ENCODER_FORMAT = AV_PIX_FMT_YUV420P

        fun encoderOptionsView() = arrayOf(
            "preset" to "superfast",
            "qp" to (AppConfig.instance.processing.qH264 - 2.0).toString(1),
        )

        fun encoderOptionsStorage() = arrayOf(
            "preset" to "veryfast",
            "qp" to AppConfig.instance.processing.qH264.toString(1),
        )
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}

