import common.AppConfig
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AV_PIX_FMT_RGB24

object Codec {
    class DecoderVideo(id: Int) : common.DecoderFFmpeg(::rkmpp) {
        companion object {
            fun rkmpp(name: String) = "${name}_rkmpp"
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    class EncoderVideoH264(id: String) : common.EncoderFFmpeg(id, "h264_rkmpp") {
        init {
            options = arrayOf(
                "rc_mode" to "CQP",
                "qp_init" to AppConfig.instance.processing.qH264.toString(1),
            )
            format = AV_PIX_FMT_RGB24
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    class EncoderVideoH265(id: String) : common.EncoderFFmpeg(id, "hevc_rkmpp") {
        init {
            options = arrayOf(
                "rc_mode" to "CQP",
                "qp_init" to AppConfig.instance.processing.qH265.toString(1),
            )
            format = AV_PIX_FMT_RGB24
        }
    }
}
