import kotlinx.cinterop.*
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24

//@OptIn(ExperimentalForeignApi::class)
//class ToRGBImage(memScope: MemScope, codecCtx: AVCodecContext) : AutoCloseable {
//    val hwDeviceCtx = memScope.alloc<CPointerVar<AVBufferRef>>()
//
//    val srcCtx = memScope.alloc<CPointerVar<AVFilterContext>>()
//    val uploadCtx = memScope.alloc<CPointerVar<AVFilterContext>>()
//    val scaleRkrgaCtx = memScope.alloc<CPointerVar<AVFilterContext>>()
//    val hwmapCtx = memScope.alloc<CPointerVar<AVFilterContext>>()
//    val sinkCtx = memScope.alloc<CPointerVar<AVFilterContext>>()
//
//    val filterGraph = memScope.alloc<CPointerVar<AVFilterGraph>>().also {
//        it.value = avfilter_graph_alloc()
//    }
//
//    init {
//        if (av_hwdevice_ctx_create(hwDeviceCtx.ptr, av_hwdevice_find_type_by_name("rkmpp"), "hw", null, 0) < 0) {
//            throw Error("av_hwdevice_ctx_create 失败")
//        }
//        val src = avfilter_get_by_name("buffer")
//        val hwupload = avfilter_get_by_name("hwupload")
//        val scaleRkrga = avfilter_get_by_name("scale_rkrga")
//        val hwmap = avfilter_get_by_name("hwmap")
//        val sink = avfilter_get_by_name("buffersink")
//        avfilter_graph_create_filter(
//            srcCtx.ptr, src, "src",
//            "video_size=${codecCtx.width}x${codecCtx.height}:pix_fmt=${codecCtx.pix_fmt}:time_base=1/90000",
//            null, filterGraph.value,
//        )
//        avfilter_graph_create_filter(
//            uploadCtx.ptr, hwupload, "upload", null, null, filterGraph.value,
//        )
//        uploadCtx.value!!.pointed.hw_device_ctx = av_buffer_ref(hwDeviceCtx.value)
//        avfilter_graph_create_filter(
//            scaleRkrgaCtx.ptr, scaleRkrga, "scale_rkrga", "format=rgb24", null, filterGraph.value,
//        )
//        avfilter_graph_create_filter(
//            hwmapCtx.ptr, hwmap, "hwmap", "mode=read", null, filterGraph.value,
//        )
//        hwmapCtx.value!!.pointed.hw_device_ctx = av_buffer_ref(hwDeviceCtx.value)
//        avfilter_graph_create_filter(
//            sinkCtx.ptr, sink, "sink", null, null, filterGraph.value,
//        )
//        avfilter_link(srcCtx.value, 0u, uploadCtx.value, 0u)
//        avfilter_link(uploadCtx.value, 0u, scaleRkrgaCtx.value, 0u)
//        avfilter_link(scaleRkrgaCtx.value, 0u, hwmapCtx.value, 0u)
//        avfilter_link(hwmapCtx.value, 0u, sinkCtx.value, 0u)
//        avfilter_graph_config(filterGraph.value, null)
//        av_buffer_unref(hwDeviceCtx.ptr)
//        println("init")
//    }
//
//    override fun close() {
//        avfilter_graph_free(filterGraph.ptr)
//    }
//
//    fun invoke(frame: CPointerVar<AVFrame>): CPointer<cnames.structs.Image> {
//        println("invoke")
//        return CreateImageRGB24(frame.pointed!!.width, frame.pointed!!.height)!!.also { image ->
//            memScoped {
//                println("av_buffersrc_add_frame")
//                av_buffersrc_add_frame(srcCtx.value, frame.value)
//                val frame0 = alloc<CPointerVar<AVFrame>>().also { it.value = av_frame_alloc() }
//                println("av_buffersink_get_frame")
//                av_buffersink_get_frame(sinkCtx.value, frame0.value)
//                println("av_buffersink_get_frame")
//                try {
//                    val data = alloc<CPointerVar<UByteVar>>().also { it.value = Bits(image) }
//                    val linesize = alloc<IntVar>().also { it.value = BytesPerLine(image) }
//                    println("copy")
//                    av_image_copy(
//                        data.ptr, linesize.ptr,
//                        frame0.pointed!!.data, frame0.pointed!!.linesize,
//                        AV_PIX_FMT_RGB24,
//                        frame0.pointed!!.width, frame0.pointed!!.height,
//                    )
//                    println("copy done")
//                } finally {
//                    av_frame_free(frame0.ptr)
//                }
//            }
//        }
//    }
//}

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
