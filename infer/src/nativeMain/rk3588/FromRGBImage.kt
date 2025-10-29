import Utils.check
import cnames.structs.Image
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
object FromRGBImage : AutoCloseable {
    override fun close() {}

    operator fun invoke(frame: AVFrame, image: CPointer<Image>) {
        frame.width = GetWidth(image)
        frame.height = GetHeight(image)
        frame.format = AV_PIX_FMT_RGB24
        av_frame_get_buffer(frame.ptr, 0).check("av_frame_get_buffer")
        av_frame_make_writable(frame.ptr).check("av_frame_make_writable")
        av_image_copy(
            frame.data, frame.linesize,
            cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)),
            AV_PIX_FMT_RGB24, frame.width, frame.height,
        )
    }
}
