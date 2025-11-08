import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.staticCFunction
import platform.ffmpeg.*

@OptIn(ExperimentalForeignApi::class)
class Device : AutoCloseable {
    companion object {
        const val H264_DECODER_NAME = "h264_ascend"

        const val ENCODER_NAME = "h264_ascend"
        const val ENCODER_FORMAT = AV_PIX_FMT_NV12

        fun encoderOptions() = arrayOf(
            "profile" to "baseline",
            "rc_mode" to "1",
            "movement_scene" to "0",
            "max_bit_rate" to "6000",
        )

        fun transferFrame(frame: CPointer<AVFrame>): CPointer<AVFrame> {
            val swFrame = av_frame_alloc()!!
            swFrame.pointed.width = frame.pointed.width
            swFrame.pointed.height = frame.pointed.height
            swFrame.pointed.format = AV_PIX_FMT_NV12
            av_frame_get_buffer(swFrame, 0)
            av_hwframe_transfer_data(swFrame, frame, 0)
            return swFrame
        }
    }

    val ref: CPointer<AVBufferRef> = cPointer {
        // 使用设备ID参数解决初始化问题
        av_hwdevice_ctx_create(it, AVHWDeviceType.AV_HWDEVICE_TYPE_ASCEND, "device_id=0", null, 0)
            .check("av_hwdevice_ctx_create (device_id=0)")
    }

    override fun close() = av_buffer_unref(cValuesOf(ref))

    fun bind(codecCtx: AVCodecContext) {
        codecCtx.hw_device_ctx = av_buffer_ref(ref)
        codecCtx.get_format = staticCFunction { _: CPointer<*>?, _: CPointer<*>? -> AV_PIX_FMT_NV12 }
    }
}
