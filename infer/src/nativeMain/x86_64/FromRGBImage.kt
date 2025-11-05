import cnames.structs.Image
import common.Utils.check
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.ptr
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.GetHeight
import platform.native.GetWidth

@OptIn(ExperimentalForeignApi::class)
class FromRGBImage : AutoCloseable {
    var swsCtx: CPointer<SwsContext>? = null

    override fun close() = sws_freeContext(swsCtx)

    operator fun invoke(frame: AVFrame, image: CPointer<Image>) {
        if (swsCtx == null) {
            swsCtx = sws_getContext(
                GetWidth(image), GetHeight(image), AV_PIX_FMT_RGB24,
                GetWidth(image), GetHeight(image), AV_PIX_FMT_YUV420P,
                SWS_BILINEAR.toInt(), null, null, null,
            )
        }
        frame.width = GetWidth(image)
        frame.height = GetHeight(image)
        frame.format = AV_PIX_FMT_YUV420P
        av_frame_get_buffer(frame.ptr, 0).check("av_frame_get_buffer")
        av_frame_make_writable(frame.ptr).check("av_frame_make_writable")
        sws_scale(
            swsCtx, cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)), 0, GetHeight(image),
            frame.data, frame.linesize,
        )
    }
}
