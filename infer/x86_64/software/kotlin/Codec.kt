import common.AppConfig
import common.StringFormat.toString
import kotlinx.cinterop.ExperimentalForeignApi
import platform.ffmpeg.AV_PIX_FMT_YUV420P

object Codec {
    class DecoderVideo(id: Int) : common.DecoderFFmpeg({ it })

    @OptIn(ExperimentalForeignApi::class)
    class EncoderVideoH264(id: String) : common.EncoderFFmpeg(id, "libx264", AV_PIX_FMT_YUV420P) {
        init {
            options = arrayOf(
                "preset" to "veryfast",
                "qp" to AppConfig.instance.processing.qH264.toString(1),
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    class EncoderVideoH265(id: String) : common.EncoderFFmpeg(id, "libx265", AV_PIX_FMT_YUV420P) {
        init {
            options = arrayOf(
                "preset" to "veryfast",
                "qp" to AppConfig.instance.processing.qH265.toString(1),
                "x265-params" to "repeat-headers=1",
            )
        }
    }
}
