import kotlinx.cinterop.*
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24

@OptIn(ExperimentalForeignApi::class)
class ToRGBImage(memScope: MemScope, codecCtx: AVCodecContext) : AutoCloseable {
    val swsCtx = memScope.alloc<CPointerVar<SwsContext>>().also {
        it.value = sws_getContext(
            codecCtx.width, codecCtx.height, codecCtx.pix_fmt,
            codecCtx.width, codecCtx.height, AV_PIX_FMT_RGB24,
            SWS_BILINEAR.toInt(), null, null, null,
        )
    }

    override fun close() = sws_freeContext(swsCtx.value)

    operator fun invoke(frame: CPointerVar<AVFrame>) =
        CreateImageRGB24(frame.pointed!!.width, frame.pointed!!.height)!!.also { image ->
            memScoped {
                val data = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
                val linesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }
                sws_scale(
                    swsCtx.value, frame.pointed!!.data, frame.pointed!!.linesize, 0,
                    frame.pointed!!.height, data.ptr, linesize.ptr,
                )
            }
        }
}
