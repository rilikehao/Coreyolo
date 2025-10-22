import kotlinx.cinterop.*
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24

@OptIn(ExperimentalForeignApi::class)
class ToRGBImage : AutoCloseable {
    lateinit var swsCtx: CPointer<SwsContext>

    fun init(codecCtx: AVCodecContext) {
        swsCtx = sws_getContext(
            codecCtx.width, codecCtx.height, codecCtx.pix_fmt,
            codecCtx.width, codecCtx.height, AV_PIX_FMT_RGB24,
            SWS_BILINEAR.toInt(), null, null, null,
        )!!
    }

    override fun close() = sws_freeContext(swsCtx)

    operator fun invoke(frame: AVFrame) =
        CreateImageRGB24(frame.width, frame.height)!!.also { image ->
            memScoped {
                val data = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
                val linesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }
                sws_scale(
                    swsCtx, frame.data, frame.linesize, 0,
                    frame.height, data.ptr, linesize.ptr,
                )
            }
        }
}
