import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264"

        const val ENCODER_NAME = "librav1e"
        const val ENCODER_FORMAT = AV_PIX_FMT_YUV420P

        fun encoderOptions() = arrayOf(
            "speed" to "10",
            "lookahead" to "0",
        )
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}
