import common.Utils.check
import kotlinx.cinterop.*
import platform.ffmpeg.*
import platform.native.*

@OptIn(ExperimentalForeignApi::class)
class ToRGBImage : AutoCloseable {
    var rkFormat = RK_FORMAT_UNKNOWN

    var srcFd = 0
    var dstFd = 0
    var srcHandle = 0U
    var dstHandle = 0U
    var srcSize = 0UL
    var dstSize = 0UL
    lateinit var srcBuf: CPointer<out CPointed>
    lateinit var dstBuf: CPointer<out CPointed>
    lateinit var src: CValue<rga_buffer_t>
    lateinit var dst: CValue<rga_buffer_t>
    var srcSize0 = 0
    var srcSize1 = 0

    override fun close() {
        releasebuffer_handle(dstHandle)
        dma_buf_free(dstSize, cValuesOf(dstFd), dstBuf)
        releasebuffer_handle(srcHandle)
        dma_buf_free(srcSize, cValuesOf(srcFd), srcBuf)
    }

    operator fun invoke(frame: AVFrame) = CreateImageRGB24(frame.width, frame.height)!!.also { image ->
        if (srcFd == 0) {
            memScoped {
                rkFormat = when (frame.format) {
                    AV_PIX_FMT_RGB24 -> RK_FORMAT_RGB_888
                    AV_PIX_FMT_BGR24 -> RK_FORMAT_BGR_888
                    AV_PIX_FMT_YUYV422 -> RK_FORMAT_YUYV_422
                    AV_PIX_FMT_NV16 -> RK_FORMAT_YCbCr_422_SP
                    AV_PIX_FMT_NV12 -> RK_FORMAT_YCbCr_420_SP
                    else -> throw Error("不支持的格式")
                }
                srcSize0 = frame.linesize[0] * frame.height
                srcSize1 = when (frame.format) {
                    AV_PIX_FMT_RGB24, AV_PIX_FMT_BGR24, AV_PIX_FMT_YUYV422 -> 0
                    AV_PIX_FMT_NV16 -> frame.linesize[1] * frame.height
                    AV_PIX_FMT_NV12 -> frame.linesize[1] * frame.height / 2
                    else -> throw Error("不支持的格式")
                }
                srcSize = (srcSize0 + srcSize1).toULong()
                val srcFdVar = alloc<IntVar>()
                val srcBufVar = alloc<CPointerVar<out CPointed>>()
                dma_buf_alloc(DMA_HEAP_DMA32_UNCACHED_PATH, srcSize, srcFdVar.ptr, srcBufVar.ptr)
                    .check("dma_buf_alloc")
                srcFd = srcFdVar.value
                srcBuf = srcBufVar.value!!
                val srcParam = alloc<im_handle_param_t>()
                srcParam.width = frame.width.toUInt()
                srcParam.height = frame.height.toUInt()
                srcParam.format = rkFormat
                srcHandle = importbuffer_fd(srcFdVar.value, srcParam.ptr)
                src = wrapbuffer_handle_t(
                    srcHandle,
                    frame.width, frame.height,
                    frame.width, frame.height,
                    rkFormat.toInt(),
                )
                dstSize = (BytesPerLine(image) * GetHeight(image)).toULong()
                val dstFdVar = alloc<IntVar>()
                val dstBufVar = alloc<CPointerVar<out CPointed>>()
                dma_buf_alloc(DMA_HEAP_DMA32_UNCACHED_PATH, dstSize, dstFdVar.ptr, dstBufVar.ptr)
                    .check("dma_buf_alloc")
                dstFd = dstFdVar.value
                dstBuf = dstBufVar.value!!
                val dstParam = alloc<im_handle_param_t>()
                dstParam.width = GetWidth(image).toUInt()
                dstParam.height = GetHeight(image).toUInt()
                dstParam.format = RK_FORMAT_RGB_888
                dstHandle = importbuffer_fd(dstFdVar.value, dstParam.ptr)
                dst = wrapbuffer_handle_t(
                    dstHandle,
                    GetWidth(image), GetHeight(image),
                    BytesPerLine(image) / 3, GetHeight(image),
                    RK_FORMAT_RGB_888.toInt(),
                )
            }
        }
        val srcBuf0 = srcBuf.reinterpret<UByteVar>()
        val srcBuf1 = srcBuf0 + srcSize0
        av_image_copy(
            cValuesOf(srcBuf0, srcBuf1), frame.linesize,
            frame.data, frame.linesize,
            frame.format,
            frame.width, frame.height,
        )
        imcvtcolor_t(
            src, dst, rkFormat.toInt(), RK_FORMAT_RGB_888.toInt(),
            IM_COLOR_SPACE_DEFAULT.toInt(), 1,
        ).let { if (it != IM_STATUS_SUCCESS) throw Error("imcvtcolor_t 失败") }
        av_image_copy(
            cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)),
            cValuesOf(dstBuf.reinterpret()), cValuesOf(BytesPerLine(image)),
            AV_PIX_FMT_RGB24,
            frame.width, frame.height,
        )
    }
}
