import common.Utils.cPointer
import common.Utils.check
import common.AppConfig
import kotlinx.cinterop.*
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

    // 硬编码设备ID为0，解决设备初始化问题
    private val deviceId = 0
    
    val ref: CPointer<AVBufferRef> = cPointer {
        // 使用正确的设备ID和配置参数
        val deviceString = "device_id=$deviceId".cstr.ptr
        av_hwdevice_ctx_create(it, AVHWDeviceType.AV_HWDEVICE_TYPE_ASCEND, deviceString, null, 0)
            .check("av_hwdevice_ctx_create (device_id=$deviceId)")
    }

    override fun close() = av_buffer_unref(cValuesOf(ref))

    // 创建硬件帧上下文以解决内存分配问题
    private val hwFramesContext: CPointer<AVBufferRef> = cPointer {
        val framesConfig = alloc<AVHWFramesContext>()
        framesConfig.sw_format = AV_PIX_FMT_NV12
        framesConfig.format = AV_PIX_FMT_ASCEND
        framesConfig.device_ref = av_buffer_ref(ref)
        av_hwframe_ctx_create(it, AV_PIX_FMT_ASCEND, null, framesConfig.ptr, 0)
            .check("av_hwframe_ctx_create")
    }

    fun bind(codecCtx: AVCodecContext) {
        // 绑定设备上下文
        codecCtx.hw_device_ctx = av_buffer_ref(ref)
        // 绑定帧上下文以支持硬件加速解码
        codecCtx.hw_frames_ctx = av_buffer_ref(hwFramesContext)
        // 设置像素格式回调
        codecCtx.get_format = staticCFunction { _: CPointer<*>?, _: CPointer<*>? -> AV_PIX_FMT_ASCEND }
    }
    
    override fun close() {
        av_buffer_unref(cValuesOf(hwFramesContext))
        av_buffer_unref(cValuesOf(ref))
    }
}
