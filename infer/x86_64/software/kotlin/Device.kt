import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AVCodecContext

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    override fun close() = Unit

    fun bind(codecCtx: AVCodecContext) = Unit
}
