import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.staticCFunction
import platform.ffmpeg.*

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
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
