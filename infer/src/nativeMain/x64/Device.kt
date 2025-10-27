import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val NPU_THREADS = 1
        const val CPU_THREADS = 12
        const val H264_DECODER_NAME = "h264"
        const val H264_ENCODER_NAME = "libx264rgb"
        val encoderOptions = arrayOf("qp" to "20", "preset" to "fast")
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}
