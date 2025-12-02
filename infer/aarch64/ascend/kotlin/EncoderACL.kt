import cnames.structs.EncoderProcess
import co.touchlab.kermit.Logger
import common.Command
import common.Encoder
import common.StringFormat.toString
import common.Utils.check
import common.Utils.roundToEpochMs
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import platform.ffmpeg.*
import platform.native.*
import platform.posix.memcpy
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
open class EncoderACL(
    val id: String,
    val suggestedName: CompletableDeferred<String>?,
    val input: Flow<Command.CommandImage>,
    val encodeType: String,
) : Encoder {
    private var swsCtx: CPointer<SwsContext>? = null
    private var width = 0
    private var height = 0
    private var codecpar: CPointer<AVCodecParameters>? = null
    private var codec: CPointer<AVCodec>? = null
    private var lastDts: Long = -1L

    lateinit var timestamp0: Instant

    override fun startTimeRealtime() = timestamp0.roundToEpochMs()

    override fun initStream(formatContext: AVFormatContext): CPointer<AVStream> {
        return avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_copy(it.pointed.codecpar, codecpar)
        }
    }

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        val encoderProcess = CompletableDeferred<CPointer<EncoderProcess>>()

        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        val packet = av_packet_alloc()!!

        return flow {
            coroutineScope {
                launch {
                    try {
                        try {
                            input.collect { commandImage ->
                                if (inputFrames == 0L) {
                                    timestamp0 = commandImage.timestamp
                                    val name = timestamp0.toTimeString()
                                    suggestedName?.complete(name)
                                    width = GetWidth(commandImage.data)
                                    height = GetHeight(commandImage.data)
                                    encoderProcess.complete(StartEncoder(0, 0, encodeType, width, height)!!)
                                    swsCtx = sws_getContext(
                                        width, height, AV_PIX_FMT_RGB24,
                                        width, height, AV_PIX_FMT_NV12,
                                        SWS_BILINEAR.toInt(), null, null, null,
                                    )
                                    codec = when (encodeType) {
                                        "h264" -> AV_CODEC_ID_H264
                                        "hevc" -> AV_CODEC_ID_HEVC
                                        else -> throw Error("不支持的视频编码")
                                    }.let { avcodec_find_encoder(it) }
                                }
                                Logger.i {
                                    if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                                    val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                                    val delayed = frame0.elapsedNow() - (commandImage.timestamp - timestamp0)
                                    val delayMs = max(0L, delayed.inWholeMilliseconds)
                                    "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
                                }
                                val wstride = (width + 15) / 16 * 16
                                val hstride = (height + 1) / 2 * 2
                                val nv12Size = wstride * hstride * 3 / 2
                                memScoped {
                                    val nv12 = allocArray<UByteVar>(nv12Size)
                                    sws_scale(
                                        swsCtx,
                                        cValuesOf(Bits(commandImage.data)),
                                        cValuesOf(BytesPerLine(commandImage.data)),
                                        0, height,
                                        cValuesOf(nv12, nv12 + wstride * hstride),
                                        cValuesOf(wstride, wstride),
                                    )
                                    EncoderW(encoderProcess.await(), cValue {
                                        timestamp_ = commandImage.timestamp.toEpochMilliseconds()
                                        is_key_frame_ = inputFrames % 12L == 0L
                                        size_ = nv12Size.toLong()
                                        data_ = nv12.reinterpret()
                                    }).let { if (!it) throw IllegalStateException() }
                                }
                                ++inputFrames
                                DestroyImage(commandImage.data)
                            }
                        } catch (_: CancellationException) {
                        }
                        if (encoderProcess.isCompleted) {
                            EncoderW(encoderProcess.getCompleted(), cValue {
                                timestamp_ = 0
                                is_key_frame_ = false
                                size_ = 0
                                data_ = null
                            })
                        }
                    } catch (_: IllegalStateException) {
                    }
                }

                try {
                    while (true) {
                        val timestamp = memScoped {
                            val io = alloc<EncoderIO>().also { it.timestamp_ = 0L }
                            EncoderR0(encoderProcess.await(), io.ptr)
                            if (io.timestamp_ == 0L) break

                            av_new_packet(packet, io.size_.toInt()).check("av_new_packet")
                            io.data_ = packet.pointed.data!!.reinterpret()
                            EncoderR1(encoderProcess.await(), io.ptr)

                            if (codecpar == null) {
                                codecpar = avcodec_parameters_alloc().also {
                                    it!!.pointed.codec_type = AVMEDIA_TYPE_VIDEO
                                    it.pointed.codec_id = codec!!.pointed.id
                                    it.pointed.codec_tag = 0U
                                    it.pointed.width = width
                                    it.pointed.height = height
                                    it.pointed.format = AV_PIX_FMT_YUV420P
                                }
                            }

                            if (codecpar!!.pointed.extradata == null) {
                                val dataPtr = packet.pointed.data!!
                                val size = packet.pointed.size
                                val isHevc = (codec!!.pointed.id == AV_CODEC_ID_HEVC)

                                val splitIndex = findHeaderEndOffset(dataPtr, size, isHevc)

                                if (splitIndex > 0) {
                                    Logger.i { "Extradata detected! Size: $splitIndex bytes" }
                                    val extradata =
                                        av_malloc(splitIndex.toULong() + AV_INPUT_BUFFER_PADDING_SIZE.toULong())!!
                                    memcpy(extradata, dataPtr, splitIndex.toULong())
                                    codecpar!!.pointed.extradata = extradata.reinterpret()
                                    codecpar!!.pointed.extradata_size = splitIndex
                                } else {
                                    Logger.w { "No extradata found in first packet (SplitIndex=$splitIndex)" }
                                }
                            }

                            if (io.is_key_frame_) {
                                packet.pointed.flags = packet.pointed.flags.or(AV_PKT_FLAG_KEY)
                            }
                            Instant.fromEpochMilliseconds(io.timestamp_)
                        }

                        var pts = (timestamp - timestamp0).inWholeMicroseconds * 90 / 1000
                        if (pts <= lastDts) {
                            pts = lastDts + 1
                        }
                        lastDts = pts

                        packet.pointed.pts = pts
                        packet.pointed.dts = pts

                        emit(packet as CPointer<AVPacket>?)
                    }
                } finally {
                    if (encoderProcess.isCompleted) StopEncoder(encoderProcess.getCompleted())
                }
            }
        }.onCompletion {
            if (swsCtx != null) sws_freeContext(swsCtx)
            if (codecpar != null) {
                if (codecpar!!.pointed.extradata != null) av_free(codecpar!!.pointed.extradata)
                av_free(codecpar)
            }
        }
    }

    /**
     * 严格扫描 NALU，返回 Header (VPS/SPS/PPS/SEI) 结束的位置。
     * 如果整个包都是 Header，返回 size。
     * 如果没有 Header，返回 0。
     */
    private fun findHeaderEndOffset(data: CPointer<UByteVar>, size: Int, isHevc: Boolean): Int {
        var i = 0
        var lastHeaderEnd = 0
        var foundAnyHeader = false

        while (i < size - 4) {
            // 1. 查找 Start Code (00 00 01 或 00 00 00 01)
            var startCodeLen = 0
            if (data[i] == 0.toUByte() && data[i + 1] == 0.toUByte()) {
                if (data[i + 2] == 1.toUByte()) {
                    startCodeLen = 3
                } else if (data[i + 2] == 0.toUByte() && data[i + 3] == 1.toUByte()) {
                    startCodeLen = 4
                }
            }

            if (startCodeLen > 0) {
                // 读取 NAL Header 字节
                val headerByte = data[i + startCodeLen].toInt()
                val naluType: Int
                val isVcl: Boolean

                if (isHevc) {
                    // H.265: Type = (byte & 0x7E) >> 1
                    naluType = (headerByte and 0x7E) shr 1
                    // VCL 范围: 0-31. Header: 32(VPS), 33(SPS), 34(PPS), 39/40(SEI)
                    isVcl = naluType < 32
                } else {
                    // H.264: Type = byte & 0x1F
                    naluType = headerByte and 0x1F
                    // VCL 范围: 1-5. Header: 7(SPS), 8(PPS), 6(SEI)
                    isVcl = naluType in 1..5
                }

                if (isVcl) {
                    // 找到了第一帧图像数据 (IDR/Slice)，立刻停止！
                    // 分界点就是当前 NALU 的起始位置 (i)
                    return i
                } else {
                    // 这是一个 Header，继续找下一个
                    foundAnyHeader = true
                    // 暂时假设这个 Header 之后全是 Header，直到发现 VCL
                    // 如果整个包跑完了都没发现 VCL，那整个包都是 Extradata
                    lastHeaderEnd = size
                }

                // 跳过 StartCode 继续扫描
                i += startCodeLen
            } else {
                i++
            }
        }

        return if (foundAnyHeader) lastHeaderEnd else 0
    }
}
