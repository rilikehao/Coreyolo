import Utils.cPointer
import Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.staticCFunction
import platform.ffmpeg.*

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val NPU_THREADS = 5
        const val CPU_THREADS = 8
        const val H264_DECODER_NAME = "h264_rkmpp"
        const val H264_ENCODER_NAME = "h264_rkmpp"
        val encoderOptions = arrayOf("rc_mode" to "CQP", "qp_init" to "20")
    }

    val ref: CPointer<AVBufferRef> = cPointer {
        av_hwdevice_ctx_create(it, AVHWDeviceType.AV_HWDEVICE_TYPE_RKMPP, null, null, 0)
            .check("av_hwdevice_ctx_create")
    }

    override fun close() = av_buffer_unref(cValuesOf(ref))

    fun bind(codecCtx: AVCodecContext) {
        codecCtx.hw_device_ctx = av_buffer_ref(ref)
        codecCtx.get_format = staticCFunction { _: CPointer<*>?, _: CPointer<*>? -> AV_PIX_FMT_NV12 }
    }
}
