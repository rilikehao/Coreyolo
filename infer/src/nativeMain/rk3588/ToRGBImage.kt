import Utils.check
import kotlinx.cinterop.*
import platform.ffmpeg.*
import platform.native.*

@OptIn(ExperimentalForeignApi::class)
class ToRGBImage : AutoCloseable {
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

    fun init(codecCtx: AVCodecContext) = memScoped {
        srcSize0 = codecCtx.width * codecCtx.height
        srcSize1 = codecCtx.width * codecCtx.height
        srcSize = (srcSize0 + srcSize1).toULong()
        val srcFdVar = alloc<IntVar>()
        val srcBufVar = alloc<CPointerVar<out CPointed>>()
        dma_buf_alloc(DMA_HEAP_DMA32_UNCACHED_PATH, srcSize, srcFdVar.ptr, srcBufVar.ptr)
            .check("dma_buf_alloc")
        srcFd = srcFdVar.value
        srcBuf = srcBufVar.value!!
        val srcParam = alloc<im_handle_param_t>()
        srcParam.width = codecCtx.width.toUInt()
        srcParam.height = codecCtx.height.toUInt()
        srcParam.format = RK_FORMAT_YCbCr_420_SP
        srcHandle = importbuffer_fd(srcFdVar.value, srcParam.ptr)
        src = wrapbuffer_handle_t(
            srcHandle,
            codecCtx.width, codecCtx.height,
            codecCtx.width, codecCtx.height,
            RK_FORMAT_YCbCr_420_SP.toInt(),
        )
        val image = CreateImageRGB24(codecCtx.width, codecCtx.height)!!
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
        DestroyImage(image)
    }

    override fun close() {
        releasebuffer_handle(dstHandle)
        dma_buf_free(dstSize, cValuesOf(dstFd), dstBuf)
        releasebuffer_handle(srcHandle)
        dma_buf_free(srcSize, cValuesOf(srcFd), srcBuf)
    }

    operator fun invoke(frame: AVFrame) = CreateImageRGB24(frame.width, frame.height)!!.also { image ->
        val srcBuf0 = srcBuf.reinterpret<UByteVar>()
        val srcBuf1 = srcBuf0 + srcSize0
        av_image_copy(
            cValuesOf(srcBuf0, srcBuf1), frame.linesize,
            frame.data, frame.linesize,
            AV_PIX_FMT_NV12,
            frame.width, frame.height,
        )
        imcvtcolor_t(
            src, dst, RK_FORMAT_YCbCr_420_SP.toInt(), RK_FORMAT_RGB_888.toInt(),
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
