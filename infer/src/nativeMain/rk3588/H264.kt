import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import platform.ffmpeg.*

@OptIn(ExperimentalForeignApi::class)
object H264 {
    const val DECODER_NAME = "h264"
    const val ENCODER_NAME = "h264_rkmpp"

    fun setDecoderHardware(memScope: MemScope, codecCtx: AVCodecContext) {
        println("🚀 开始模拟 FFmpeg -hwaccel rkmpp 的完整流程")

        // === 步骤 3: 硬件设备初始化阶段 ===
        // 模拟 ffmpeg_dec.c:hw_device_setup_for_decode

        // 3.1: 模拟 if (ist->hwaccel_id == HWACCEL_GENERIC)
        //        type = ist->hwaccel_device_type;  // AV_HWDEVICE_TYPE_RKMPP

        // 3.2: 模拟 err = hw_device_init_from_type(type, ist->hwaccel_device, &dev)
        val deviceRef = memScope.alloc<CPointerVar<AVBufferRef>>()
        val deviceType = AVHWDeviceType.AV_HWDEVICE_TYPE_RKMPP
        val device = null  // ist->hwaccel_device = null (默认设备)

        val err = av_hwdevice_ctx_create(deviceRef.ptr, deviceType, device, null, 0)
        if (err < 0 || deviceRef.value == null) {
            println("❌ hw_device_init_from_type 失败: $err")
            return
        }
        println("✅ hw_device_init_from_type 成功")

        // 3.3: 模拟 ist->dec_ctx->hw_device_ctx = av_buffer_ref(dev->device_ref)
        codecCtx.hw_device_ctx = av_buffer_ref(deviceRef.value)
        println("✅ hw_device_ctx 已设置")

        // === 步骤 4: get_format 回调设置 ===
        // 模拟 ffmpeg_dec.c 中的 get_format 逻辑

        // 4.1: 查找硬件配置
        val codec = avcodec_find_decoder_by_name(DECODER_NAME)
        var hwPixFmt: AVPixelFormat? = null

        for (i in 0..10) {
            val config = avcodec_get_hw_config(codec, i)
            if (config == null) break

            // 4.2: 模拟 if (config && config->device_type == ist->hwaccel_device_type)
            if (config.pointed.device_type == deviceType) {
                // 4.3: 模拟 d->hwaccel_pix_fmt = *p
                val formats = config.pointed.pix_fmt
                if (formats != AV_PIX_FMT_NONE) {
                    hwPixFmt = formats
                    println("✅ 找到硬件像素格式: ${av_get_pix_fmt_name(formats)?.toKString()}")
                    break
                }
            }
        }

        // 4.4: 设置 get_format 回调（避免捕获）
        if (hwPixFmt != null) {
            codecCtx.get_format = staticCFunction { _: CPointer<AVCodecContext>?, _: CPointer<IntVarOf<AVPixelFormat>>? ->
                AV_PIX_FMT_DRM_PRIME
            }
            println("✅ get_format 回调已设置，格式: $hwPixFmt")
        }

        // === 额外配置 ===
        codecCtx.extra_hw_frames = 8

        println("🎉 完整的 FFmpeg -hwaccel rkmpp 流程模拟完成！")
    }

    fun transferFromHardware(frame: CPointerVar<AVFrame>) {
        val swFrame = av_frame_alloc()!!
        swFrame.pointed.width = frame.pointed!!.width
        swFrame.pointed.height = frame.pointed!!.height
        av_hwframe_transfer_data(swFrame, frame.value, 0)
        av_frame_free(frame.ptr)
        frame.value = swFrame
    }

    fun setEncoderOptions(options: CPointerVar<AVDictionary>) {
        av_dict_set(options.ptr, "rc_mode", "CQP", 0)
        av_dict_set(options.ptr, "qp_init", "20", 0)
    }
}
