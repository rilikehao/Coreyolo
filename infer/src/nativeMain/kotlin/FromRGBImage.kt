import cnames.structs.Image
import kotlinx.cinterop.*
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.GetHeight
import platform.native.GetWidth

@OptIn(ExperimentalForeignApi::class)
class FromRGBImage : AutoCloseable {
    lateinit var swsCtx: CPointer<SwsContext>
    var format: Int = AV_PIX_FMT_NONE

    fun init(codecCtx: AVCodecContext) {
        format = codecCtx.pix_fmt
        swsCtx = sws_getContext(
            codecCtx.width, codecCtx.height, AV_PIX_FMT_RGB24,
            codecCtx.width, codecCtx.height, format,
            SWS_BILINEAR.toInt(), null, null, null,
        )!!
    }

    override fun close() = sws_freeContext(swsCtx)

    operator fun invoke(frame: AVFrame, image: CPointer<Image>) {
        frame.width = GetWidth(image)
        frame.height = GetHeight(image)
        frame.format = format
        av_frame_get_buffer(frame.ptr, 0).check("av_frame_get_buffer")
        av_frame_make_writable(frame.ptr).check("av_frame_make_writable")
        memScoped {
            val srcData = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
            val srcLinesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }
            sws_scale(
                swsCtx,
                srcData.ptr, srcLinesize.ptr,
                0, frame.height,
                frame.data, frame.linesize,
            )
        }
    }
}
