package common

import co.touchlab.kermit.Logger
import common.StringFormat.toString
import common.Utils.check
import common.Utils.roundToEpochMs
import common.Utils.toTimeString
import common.Utils.withOptions
import cnames.structs.EncoderProcess
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import platform.ffmpeg.*
import platform.native.*
import kotlin.math.max
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
open class EncoderACL(
    val id: String,
    val suggestedName: CompletableDeferred<String>?,
    val input: Flow<Command.CommandImage>,
    private val encodeType: String,
    format: Int,
    val options: Array<Pair<String, String>>,
) : Encoder {
    companion object {
        const val ID_SIZE = 16
        val availableIds = Channel<Int>(capacity = ID_SIZE).apply { repeat(ID_SIZE) { trySend(it) } }
    }

    private var encoderProcess: CPointer<EncoderProcess>? = null
    private var swsCtx: CPointer<SwsContext>? = null
    private var width = 0
    private var height = 0
    private var formatContext: AVFormatContext? = null
    private var videoStream: CPointer<AVStream>? = null
    private var bsfContext: CPointer<AVBSFContext>? = null
    private var codec: CPointer<AVCodec>? = null

    lateinit var timestamp0: Instant

    override fun startTimeRealtime() = timestamp0.roundToEpochMs()

    override fun initStream(formatContext: AVFormatContext): CPointer<AVStream> {
        this.formatContext = formatContext
        // Create a temporary stream, will be replaced when we have extradata from BSF
        return avformat_new_stream(formatContext.ptr, null).check("avformat_new_stream")
    }

    override fun invoke(): Flow<CPointer<AVPacket>?> {
        var inputFrames = 0L
        var outputFrames = 0L
        var frame0 = TimeSource.Monotonic.markNow()
        val id = availableIds.tryReceive().getOrNull() ?: 0

        return input.map { commandImage ->
            if (inputFrames++ == 0L) {
                timestamp0 = commandImage.timestamp
                val name = timestamp0.toTimeString()
                suggestedName?.complete(name)

                // Initialize encoder on first frame
                width = GetWidth(commandImage.data)
                height = GetHeight(commandImage.data)
                initializeEncoder(id, encodeType)
            }

            Logger.i {
                if (outputFrames == 0L) frame0 = TimeSource.Monotonic.markNow()
                val fps = (1.seconds / frame0.elapsedNow() * (++outputFrames)).toString(2)
                val delayed = frame0.elapsedNow() - (commandImage.timestamp - timestamp0)
                val delayMs = max(0L, delayed.inWholeMilliseconds)
                "[$id] 编码 FPS: $fps, 额外延迟 / ms: $delayMs."
            }

            encodeFrame(commandImage)
        }.onCompletion {
            sendEofPacket()
        }.transform { packet ->
            packet?.let { emit(it) }
        }.onCompletion {
            cleanup(id)
        }
    }

    private fun initializeEncoder(id: Int, encodeType: String) {
        val encodeName = if (encodeType.contains("264")) "h264" else "hevc"

        // Start encoder process
        encoderProcess = StartEncoder(0, id, encodeName, width, height)

        // Setup SWS context for RGB24 to NV12 conversion
        swsCtx = sws_getContext(
            width, height, AV_PIX_FMT_RGB24,
            width, height, AV_PIX_FMT_NV12,
            SWS_BILINEAR.toInt(), null, null, null,
        )

        // Setup codec for BSF
        codec = if (encodeName == "h264") {
            avcodec_find_encoder(AV_CODEC_ID_H264)
        } else {
            avcodec_find_encoder(AV_CODEC_ID_HEVC)
        }?.check("avcodec_find_encoder")
    }

    private fun encodeFrame(commandImage: Command.CommandImage): CPointer<AVPacket>? {
        val image = commandImage.data
        val timestamp = commandImage.timestamp

        // Convert RGB24 to NV12
        val nv12Size = width * height * 3 / 2

        memScoped {
            val yPlane = allocArray<ByteVar>(width * height)
            val uvPlane = allocArray<ByteVar>(width * height / 2)

            sws_scale(
                swsCtx,
                cValuesOf(Bits(image)?.reinterpret<UByteVar>()), cValuesOf(BytesPerLine(image)), 0, height,
                cValuesOf(yPlane.getPointer(this@memScoped).reinterpret<UByteVar>(), uvPlane.getPointer(this@memScoped).reinterpret<UByteVar>()), cValuesOf(width, width)
            )

            // Create contiguous NV12 buffer
            val nv12Data = allocArray<ByteVar>(nv12Size)
            for (i in 0 until width * height) {
                nv12Data[i] = yPlane[i]
            }
            for (i in 0 until width * height / 2) {
                nv12Data[width * height + i] = uvPlane[i]
            }

            // Send frame to encoder process
            val encoderIO = alloc<EncoderIO>().apply {
                timestamp_ = timestamp.toEpochMilliseconds()
                size_ = nv12Size.toLong()
                data_ = nv12Data.getPointer(this@memScoped)
            }

            EncoderW(encoderProcess!!, encoderIO.ptr)
        }

        // Read encoded data from encoder
        return readEncodedPacket(timestamp)
    }

    private fun readEncodedPacket(timestamp: Instant): CPointer<AVPacket>? {
        memScoped {
            val encoderIO = alloc<EncoderIO>()
            EncoderR(encoderProcess!!, encoderIO.ptr)

            if (encoderIO.timestamp_ == 0L) return null

            val packet = av_packet_alloc()!!
            av_new_packet(packet, encoderIO.size_.toInt()).check("av_new_packet")

            packet.pointed.data?.let { dst ->
            val dataPtr = encoderIO.data_?.reinterpret<UByteVar>()
            for (i in 0 until encoderIO.size_.toInt()) {
                dst[i] = dataPtr?.get(i) ?: 0U
            }
        }

            // Apply BSF if this is the first keyframe
            if (bsfContext == null && packet.pointed.flags.and(AV_PKT_FLAG_KEY) != 0) {
                setupBSF(packet)
                val processedPacket = applyBSF(packet)
                av_packet_free(cValuesOf(packet))
                return processedPacket
            } else if (bsfContext != null) {
                return applyBSF(packet)
            }

            // Set timestamps
            val pts = (timestamp - timestamp0).inWholeMicroseconds * 90 / 1000
            packet.pointed.pts = pts
            packet.pointed.dts = pts

            return packet
        }
    }

    private fun setupBSF(firstKeyframe: CPointer<AVPacket>) {
        val bsf = av_bsf_get_by_name("extract_extradata")
            ?: throw Error("av_bsf_get_by_name failed")

        bsfContext = nativeHeap.alloc<CPointerVar<AVBSFContext>>().also {
            av_bsf_alloc(bsf, it.ptr)
        }.value

        bsfContext!!.pointed.par_in!!.pointed.let {
            it.codec_type = AVMEDIA_TYPE_VIDEO
            it.codec_id = codec!!.pointed.id
            it.codec_tag = 0U
            it.width = width
            it.height = height
            it.format = AV_PIX_FMT_YUV420P
        }

        av_bsf_init(bsfContext).check("av_bsf_init")

        // Send first keyframe to BSF to extract extradata
        av_bsf_send_packet(bsfContext, firstKeyframe).check("av_bsf_send_packet")

        val outputPacket = av_packet_alloc()!!
        if (av_bsf_receive_packet(bsfContext, outputPacket) >= 0) {
            // Create video stream with extradata
            videoStream = avformat_new_stream(formatContext!!.ptr, codec).check("avformat_new_stream")
            avcodec_parameters_copy(videoStream!!.pointed.codecpar, bsfContext!!.pointed.par_out)

            formatContext!!.let { ctx ->
            memScoped {
                val ctxPtr = ctx.ptr
                ctxPtr.pointed.start_time_realtime = timestamp0.roundToEpochMs() * 1000L
            }
        }

            withOptions("tune" to "zerolatency", "rtsp_transport" to "tcp") {
                avformat_write_header(formatContext!!.ptr, it).check("avformat_write_header")
            }
        }

        av_packet_free(cValuesOf(outputPacket))
    }

    private fun applyBSF(packet: CPointer<AVPacket>): CPointer<AVPacket>? {
        av_bsf_send_packet(bsfContext, packet).check("av_bsf_send_packet")

        val outputPacket = av_packet_alloc()!!
        return if (av_bsf_receive_packet(bsfContext, outputPacket) >= 0) {
            outputPacket
        } else {
            av_packet_free(cValuesOf(outputPacket))
            null
        }
    }

    private fun sendEofPacket() {
        memScoped {
            val encoderIO = alloc<EncoderIO>().apply {
                timestamp_ = 0
                size_ = 0
                data_ = null
            }
            EncoderW(encoderProcess!!, encoderIO.ptr)
        }

        // Read remaining packets
        while (true) {
            val packet = readEncodedPacket(Instant.fromEpochMilliseconds(0)) ?: break
            // Handle remaining packets if needed
        }
    }

    private fun cleanup(id: Int) {
        swsCtx?.let { sws_freeContext(it) }
        bsfContext?.let { av_bsf_free(cValuesOf(it)) }

        encoderProcess?.let {
            StopEncoder(it)
        }

        availableIds.trySend(id)

        // Launch delay in background
        kotlinx.coroutines.GlobalScope.launch {
            delay(1000)
        }
    }
}