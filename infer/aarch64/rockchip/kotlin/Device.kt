import common.AppConfig
import common.StringFormat.toString
import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.staticCFunction
import platform.ffmpeg.*

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264_rkmpp"

        const val ENCODER_NAME_VIEW = "h264_rkmpp"
        const val ENCODER_NAME_STORAGE = "hevc_rkmpp"

        const val ENCODER_FORMAT = AV_PIX_FMT_RGB24

        fun encoderOptionsView() = arrayOf(
            "rc_mode" to "CQP",
            "qp_init" to AppConfig.instance.processing.q.toString(1),
        )

        fun encoderOptionsStorage() = arrayOf(
            "rc_mode" to "CQP",
            "qp_init" to (AppConfig.instance.processing.q + 3.0).toString(1),
        )
    }

    val ref: CPointer<AVBufferRef> = cPointer {
        av_hwdevice_ctx_create(it, AVHWDeviceType.AV_HWDEVICE_TYPE_RKMPP, null, null, 0)
            .check("av_hwdevice_ctx_create")
    }

    override fun close() = av_buffer_unref(cValuesOf(ref))

    fun bind(codecCtx: AVCodecContext) {
        codecCtx.hw_device_ctx = av_buffer_ref(ref)
        codecCtx.get_format = staticCFunction { _, _ -> AV_PIX_FMT_NV12 }
    }
}
