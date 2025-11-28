import cnames.structs.EncoderProcess
import co.touchlab.kermit.Logger
import common.Command
import common.Encoder
import common.StringFormat.toString
import common.Utils.check
import common.Utils.roundToEpochMs
import common.Utils.toTimeString
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
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
    val encoderProcess = CompletableDeferred<CPointer<EncoderProcess>>()

    private var swsCtx: CPointer<SwsContext>? = null
    private var width = 0
    private var height = 0
    private var codecpar: CPointer<AVCodecParameters>? = null
    private var codec: CPointer<AVCodec>? = null

    lateinit var timestamp0: Instant

    override fun startTimeRealtime() = timestamp0.roundToEpochMs()

    override fun initStream(formatContext: AVFormatContext): CPointer<AVStream> {
        return avformat_new_stream(formatContext.ptr, codec).check("avformat_new_stream").also {
            avcodec_parameters_copy(it.pointed.codecpar, codecpar)
        }
    }

    override suspend fun invoke(): Flow<CPointer<AVPacket>?> {
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        val packet = av_packet_alloc()!!

        return flow {
            coroutineScope {
                launch {
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
                            if (inputFrames == 0L) {
                                EncoderW(encoderProcess.await(), cValue {
                                    timestamp_ = -1
                                    is_key_frame_ = true
                                    size_ = nv12Size.toLong()
                                    data_ = nv12.reinterpret()
                                })
                            }
                            EncoderW(encoderProcess.await(), cValue {
                                timestamp_ = commandImage.timestamp.toEpochMilliseconds()
                                is_key_frame_ = inputFrames % 12L == 0L
                                size_ = nv12Size.toLong()
                                data_ = nv12.reinterpret()
                            })
                        }
                        DestroyImage(commandImage.data)
                        ++inputFrames
                    }
                    EncoderW(encoderProcess.await(), cValue {
                        timestamp_ = 0
                        is_key_frame_ = false
                        size_ = 0
                        data_ = null
                    })
                }
                while (true) {
                    val timestamp = memScoped {
                        val io = alloc<EncoderIO>().also { it.timestamp_ = 0L }
                        EncoderR0(encoderProcess.await(), io.ptr)
                        if (io.timestamp_ == 0L) break
                        av_new_packet(packet, io.size_.toInt()).check("av_new_packet")
                        io.data_ = packet.pointed.data!!.reinterpret()
                        EncoderR1(encoderProcess.await(), io.ptr)
                        if (io.timestamp_ == -1L) {
                            av_packet_unref(packet)
                            continue
                        }
                        if (io.is_key_frame_) {
                            packet.pointed.flags = packet.pointed.flags.or(AV_PKT_FLAG_KEY)
                        }
                        Instant.fromEpochMilliseconds(io.timestamp_)
                    }
                    if (codecpar == null) {
                        codecpar = avcodec_parameters_alloc().also {
                            it!!.pointed.codec_type = AVMEDIA_TYPE_VIDEO
                            it.pointed.codec_id = codec!!.pointed.id
                            it.pointed.codec_tag = 0U
                            it.pointed.width = width
                            it.pointed.height = height
                            it.pointed.format = AV_PIX_FMT_YUV420P
                        }
                        setupExtraData(packet)
                    }
                    val pts = (timestamp - timestamp0).inWholeMicroseconds * 90 / 1000
                    packet.pointed.pts = pts
                    packet.pointed.dts = pts
                    emit(packet as CPointer<AVPacket>?)
                }
            }
        }.onCompletion {
            if (swsCtx != null) sws_freeContext(swsCtx)
            if (codecpar != null) av_free(codecpar!!.pointed.extradata)
            StopEncoder(encoderProcess.getCompleted())
        }
    }

    private fun setupExtraData(packet: CPointer<AVPacket>) {
        val data = packet.pointed.data ?: return
        val size = packet.pointed.size
        val codecId = codec!!.pointed.id

        // 1. 初始化 BSF 结构 (如果需要的话，或者仅依靠 codecpar)

        // 2. 深度扫描：遍历所有 NALU
        var splitIndex = -1
        var hasHeader = false // [新增] 标记是否找到了参数集
        var i = 0

        println(">>> analyzing first frame NALUs (size: $size)...")

        while (i < size - 5) {
            // 检测起始码前缀长度 (3字节 或 4字节)
            var startCodeLen = 0
            if (data[i] == 0.toUByte() && data[i+1] == 0.toUByte()) { // 注意：这里通常用 toByte() 比较方便，toUByte 也行
                if (data[i+2] == 1.toUByte()) {
                    startCodeLen = 3
                } else if (data[i+2] == 0.toUByte() && data[i+3] == 1.toUByte()) {
                    startCodeLen = 4
                }
            }

            if (startCodeLen > 0) {
                // 读取 NAL Header
                val headerByte = data[i + startCodeLen].toInt()
                var naluType = 0
                var naluName = "UNKNOWN"
                var isVCL = false

                if (codecId == AV_CODEC_ID_HEVC) {
                    // H.265
                    naluType = (headerByte shr 1) and 0x3F
                    isVCL = naluType < 32

                    // [新增] 记录是否包含参数集
                    if (naluType == 32 || naluType == 33 || naluType == 34) hasHeader = true

                    naluName = when(naluType) {
                        32 -> "VPS"
                        33 -> "SPS"
                        34 -> "PPS"
                        35 -> "AUD"
                        39, 40 -> "SEI"
                        19, 20 -> "IDR"
                        21 -> "CRA"
                        else -> "Type($naluType)"
                    }
                } else {
                    // H.264
                    naluType = headerByte and 0x1F
                    isVCL = naluType in 1..5

                    // [新增] 记录是否包含参数集
                    if (naluType == 7 || naluType == 8) hasHeader = true

                    naluName = when(naluType) {
                        7 -> "SPS"
                        8 -> "PPS"
                        6 -> "SEI"
                        5 -> "IDR"
                        1 -> "P/B Slice"
                        else -> "Type($naluType)"
                    }
                }

                println("  Offset $i: Found StartCode($startCodeLen) + $naluName")

                // 如果找到了第一个视频切片 (VCL)，这就是分界线！
                if (isVCL) {
                    splitIndex = i
                    println("  -> Split Point Found! Extradata ends at $splitIndex")
                    break
                }

                i += startCodeLen
            } else {
                i++
            }
        }

        // 3. 执行提取逻辑
        if (splitIndex > 0) {
            // --- 情况 A: 混合包 (Header + Data) ---
            // 提取前面的 Header，保留后面的 Data
            val extradataSize = splitIndex
            val extradata = av_malloc(extradataSize.toULong() + AV_INPUT_BUFFER_PADDING_SIZE.toULong())!!
            memcpy(extradata, data, extradataSize.toULong())

            if (codecpar!!.pointed.extradata != null) av_free(codecpar!!.pointed.extradata)
            codecpar!!.pointed.extradata = extradata.reinterpret()
            codecpar!!.pointed.extradata_size = extradataSize

            println("Manual Extract: Success (Split)! Extradata Size: ${codecpar!!.pointed.extradata_size}")

        } else if (hasHeader) {
            // --- [新增] 情况 B: 纯 Header 包 (Header Only) ---
            // 整个包都是 Extradata，没有图像数据
            println("Manual Extract: Packet is pure Extradata (size: $size). Consuming entirely.")

            val extradata = av_malloc(size.toULong() + AV_INPUT_BUFFER_PADDING_SIZE.toULong())!!
            memcpy(extradata, data, size.toULong())

            if (codecpar!!.pointed.extradata != null) av_free(codecpar!!.pointed.extradata)
            codecpar!!.pointed.extradata = extradata.reinterpret()
            codecpar!!.pointed.extradata_size = size

            println("Manual Extract: Success (Full)! Extradata Size: ${codecpar!!.pointed.extradata_size}")

        } else {
            // --- 情况 C: 既没找到 VCL 也没找到 Header (异常数据) ---
            val hexStr = StringBuilder()
            for (k in 0 until kotlin.math.min(size, 16)) {
                val b = data[k].toInt()
                if(b < 16) hexStr.append("0")
                hexStr.append(b.toString(16).uppercase()).append(" ")
            }
            println("Manual Extract: FAILED. No VCL nor Header found. Header bytes: $hexStr")
        }
    }
}
