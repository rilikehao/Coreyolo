import cnames.structs.AVDictionary
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.ffmpeg.AVBufferRef
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AVFrame
import platform.ffmpeg.av_dict_set

@OptIn(ExperimentalForeignApi::class)
class Device(val ref: CPointerVar<AVBufferRef>) : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264"
        const val H264_ENCODER_NAME = "libx264"

        fun setEncoderOptions(options: CPointerVar<AVDictionary>) {
            av_dict_set(options.ptr, "qp", "20", 0)
        }
    }

    override fun close() {}

    fun bind(codecCtx: AVCodecContext) {}

    fun transferOut(frame: CPointerVar<AVFrame>) {}
}
