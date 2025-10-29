import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val NPU_THREADS = 2
        const val CPU_THREADS = 12
        const val H264_DECODER_NAME = "h264"
        const val H264_ENCODER_NAME = "libx264"
        const val H264_ENCODER_FORMAT = AV_PIX_FMT_YUV420P

        val encoderOptions = arrayOf("qp" to "20", "preset" to "fast")
    }

    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}
