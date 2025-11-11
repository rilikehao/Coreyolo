import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AV_PIX_FMT_NONE

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = ""

        const val ENCODER_NAME = ""
        const val ENCODER_FORMAT = AV_PIX_FMT_NONE

        fun encoderOptions() = arrayOf<Pair<String, String>>()
    }

    override fun close() {}

    fun bind(codecCtx: AVCodecContext) {}
}
