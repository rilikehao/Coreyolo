import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264"
        const val H264_ENCODER_NAME = "libx264"
        val encoderOptions = arrayOf("qp" to "20")
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}
