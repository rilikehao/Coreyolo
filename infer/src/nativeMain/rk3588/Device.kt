import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import platform.ffmpeg.*

@OptIn(ExperimentalForeignApi::class)
class Device(val ref: CPointerVar<AVBufferRef>) : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264_rkmpp"
        const val H264_ENCODER_NAME = "h264_rkmpp"

        fun setEncoderOptions(options: CPointerVar<AVDictionary>) {
            av_dict_set(options.ptr, "rc_mode", "CQP", 0)
            av_dict_set(options.ptr, "qp_init", "20", 0)
        }
    }

    init {
        av_hwdevice_ctx_create(ref.ptr, AVHWDeviceType.AV_HWDEVICE_TYPE_RKMPP, null, null, 0).check("av_hwdevice_ctx_create")
    }

    override fun close() = av_buffer_unref(ref.ptr)

    fun bind(codecCtx: AVCodecContext) {
        codecCtx.hw_device_ctx = av_buffer_ref(ref.value)
        codecCtx.get_format = staticCFunction { _: CPointer<AVCodecContext>?, _: CPointer<IntVarOf<AVPixelFormat>>? ->
            AV_PIX_FMT_NV12
        }
    }
}

