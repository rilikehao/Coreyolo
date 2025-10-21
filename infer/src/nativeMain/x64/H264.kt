import cnames.structs.AVDictionary
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.ffmpeg.AVBufferRef
import platform.ffmpeg.AVCodecContext
import platform.ffmpeg.AVFrame
import platform.ffmpeg.AVHWDeviceType
import platform.ffmpeg.av_dict_set
import platform.ffmpeg.av_frame_alloc
import platform.ffmpeg.av_frame_free
import platform.ffmpeg.av_hwdevice_ctx_create
import platform.ffmpeg.av_hwframe_transfer_data

@OptIn(ExperimentalForeignApi::class)
object H264 {
    const val DECODER_NAME = "h264"
    const val ENCODER_NAME = "libx264"

    fun setDecoderHardware(memScope: MemScope, codecCtx: AVCodecContext) {}
    fun transferFromHardware(frame: CPointerVar<AVFrame>) {}

    fun setEncoderOptions(options: CPointerVar<AVDictionary>) {
        av_dict_set(options.ptr, "qp", "20", 0)
    }
}
