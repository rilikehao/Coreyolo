import cnames.structs.AVDictionary
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.flow
import platform.ffmpeg.*
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class RtspInput(val url: String) : Video {
    override fun close() = Unit // No close needed

    override fun frames() = flow {
        memScoped {
            var videoStreamIndex = -1
            val options = alloc<CPointerVar<AVDictionary>>()
            av_dict_set(options.ptr, "fflags", "nobuffer", 0)
            av_dict_set(options.ptr, "rtsp_transport", "tcp", 0)
            val formatCtx = alloc<CPointerVar<AVFormatContext>>()
            if (avformat_open_input(formatCtx.ptr, url, null, options.ptr) != 0) throw Error("avformat_open_input 失败")
            try {
                if (avformat_find_stream_info(formatCtx.value, null) < 0) throw Error("avformat_find_stream_info 失败")
                for (i in 0..<formatCtx.pointed!!.nb_streams.toInt()) {
                    val stream = formatCtx.pointed!!.streams!![i]!!
                    if (stream.pointed.codecpar!!.pointed.codec_type == AVMEDIA_TYPE_VIDEO) {
                        videoStreamIndex = i
                        break
                    }
                }
                if (videoStreamIndex == -1) throw Error("未找到视频流")
                val codecParams = formatCtx.pointed!!.streams!![videoStreamIndex]!!.pointed.codecpar!!
                val codec = when (val id = codecParams.pointed.codec_id) {
                    AV_CODEC_ID_H264 -> {
                        val rkmpCodec = avcodec_find_decoder_by_name(H264.DECODER_NAME)
                        println("🔍 尝试使用 RKMPP 解码器: ${rkmpCodec?.pointed?.name?.toKString() ?: "null"}")
                        rkmpCodec ?: avcodec_find_decoder(id)
                    }
                    else -> avcodec_find_decoder(id)
                } ?: throw Error("avcodec_find_decoder 失败")
                val codecCtx = alloc<CPointerVar<AVCodecContext>>().also { it.value = avcodec_alloc_context3(codec) }
                try {
                    avcodec_parameters_to_context(codecCtx.value, codecParams)

                    // 设置 RKMPP 解码器选项（基于源码分析）
                    val options = alloc<CPointerVar<AVDictionary>>()

                    // 基础选项
                    av_dict_set(options.ptr, "low_delay", "1", 0)
                    av_dict_set(options.ptr, "threads", "1", 0)  // RKMPP 必须单线程
                    av_dict_set(options.ptr, "async_depth", "1", 0)

                    // RKMPP 特定选项（来自 rkmppdec.h 第94-101行的定义）
                    av_dict_set(options.ptr, "deint", "1", 0)      // 启用 IEP 去隔行
                    av_dict_set(options.ptr, "fast_parse", "1", 0) // 启用快速解析，提高并行性
                    av_dict_set(options.ptr, "afbc", "off", 0)     // 禁用 AFBC 以简化处理

                    println("🔧 RKMPP 解码器选项已设置")

                    H264.setDecoderHardware(this, codecCtx.pointed!!)
                    if (avcodec_open2(codecCtx.value, codec, options.ptr) < 0) throw Error("avcodec_open2 失败")
                    av_dict_free(options.ptr)
                    println("Decoder: ${codecCtx.pointed!!.codec?.pointed?.name?.toKString()}")
                    println("HW accel: ${codecCtx.pointed!!.hw_device_ctx != null}")
                    ToRGBImage(this, codecCtx.pointed!!).use { toRGBImage ->
                        val packet = alloc<CPointerVar<AVPacket>>().also { it.value = av_packet_alloc() }
                        val frame = alloc<CPointerVar<AVFrame>>().also { it.value = av_frame_alloc() }
                        try {
                            val timeBase = formatCtx.pointed!!.streams!![videoStreamIndex]!!.pointed.time_base
                            var packetCount = 0
                            while (true) {
                                val ret = av_read_frame(formatCtx.value, packet.value)
                                println("Decoder name: ${codec.pointed!!.name?.toKString()}")
                                if (ret < 0) {
                                    if (ret == AVERROR_EOF) break
                                    continue
                                }
                                try {
                                    if (packet.pointed!!.stream_index == videoStreamIndex) {
                                        packetCount++

                                        // 只显示前几个 packet，避免日志泛滥
                                        if (packetCount <= 5) {
                                            println("📦 发送 packet #$packetCount, pts: ${packet.pointed!!.pts}, size: ${packet.pointed!!.size}")
                                        }

                                        // 发送 packet 到解码器
                                        val sendRet = avcodec_send_packet(codecCtx.value, packet.value)
                                        if (packetCount <= 5) {
                                            println("🔄 avcodec_send_packet 返回: $sendRet")
                                        }
                                        when {
                                            sendRet == 0 -> {
                                                // 成功发送，尝试获取帧
                                                while (true) {
                                                    val ret = avcodec_receive_frame(codecCtx.value, frame.value)
                                                    if (ret == 0) {
                                                        println("🎉 成功收到帧: ${frame.pointed!!.width}x${frame.pointed!!.height}, 格式: ${frame.pointed!!.format}, pts: ${frame.pointed!!.pts}")
                                                        println("hello4")
                                                        //H264.transferFromHardware(frame)
                                                        val present = frame.pointed!!.pts.toDouble() * timeBase.num / timeBase.den
                                                        emit(Pair(present.seconds, toRGBImage.invoke(frame)))
                                                    } else if (ret == -11) {
                                                        // 正常：需要更多 packet
                                                        break
                                                    } else if (ret == AVERROR_EOF) {
                                                        println("✅ 解码结束")
                                                        return@flow
                                                    } else {
                                                        println("❌ avcodec_receive_frame 错误: $ret")
                                                        break
                                                    }
                                                }
                                            }
                                            sendRet == -11 -> {
                                                // 缓冲区满，先尝试获取帧
                                                while (true) {
                                                    val ret = avcodec_receive_frame(codecCtx.value, frame.value)
                                                    if (ret == 0) {
                                                        println("🎉 从缓冲区满状态收到帧")
                                                        println("hello4")
                                                        val present = frame.pointed!!.pts.toDouble() * timeBase.num / timeBase.den
                                                        emit(Pair(present.seconds, toRGBImage.invoke(frame)))
                                                    } else {
                                                        break
                                                    }
                                                }
                                            }
                                            else -> {
                                                if (packetCount <= 3) {
                                                    println("❌ avcodec_send_packet 失败: $sendRet")
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    av_packet_unref(packet.value)
                                }
                            }
                        } finally {
                            av_frame_free(frame.ptr)
                            av_packet_free(packet.ptr)
                        }
                    }
                } finally {
                    avcodec_free_context(codecCtx.ptr)
                }
            } finally {
                avformat_close_input(formatCtx.ptr)
            }
        }
    }
}
