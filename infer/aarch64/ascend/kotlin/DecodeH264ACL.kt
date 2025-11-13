import cnames.structs.acldvppChannelDesc
import cnames.structs.acldvppResizeConfig
import common.Frame
import common.InputRtsp
import common.InputRtsp.maxFrames
import common.Utils.cPointer
import common.Utils.checkEq0
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.acl.*
import platform.ffmpeg.AVCodecParameters
import platform.ffmpeg.av_packet_unref
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
object DecodeH264ACL : (Int, InputRtsp.Output) -> Flow<Frame> {
    const val REORDER_SIZE = 5

    data class Data(
        val acl: SessionACL,
        val aclResize: SessionACL,
        val channelResize: CPointer<acldvppChannelDesc>,
        val configResize: CPointer<acldvppResizeConfig>,
        val codecParams: AVCodecParameters,
        val thisData: ProducerScope<Frame>,
        val timestamp: Instant,
        val keep: Boolean,
    )

    override fun invoke(id: Int, input: InputRtsp.Output): Flow<Frame> {
        val codecParams = input.stream.codecpar!!.pointed
        val timeBase = input.stream.time_base
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val aclResize = SessionACL(0)
        aclResize.setContext()
        val configResize = acldvppCreateResizeConfig()!!
        acldvppSetResizeConfigInterpolation(configResize, 0U)
        val channelResize = acldvppCreateChannelDesc()!!
        acldvppCreateChannel(channelResize)
        val acl = SessionACL(0)
        acl.setContext()
        val channel = aclvdecCreateChannelDesc()
        aclvdecSetChannelDescChannelId(channel, id.toUInt())
        aclvdecSetChannelDescThreadId(channel, acl.threadId)
        aclvdecSetChannelDescCallback(channel, staticCFunction { input, output, rawData ->
            val data = rawData!!.asStableRef<Data>().let { ref ->
                ref.get().also { ref.dispose() }
            }
            data.thisData.apply {
                val dev = acldvppGetStreamDescData(input)!!
                val frameDev = acldvppGetPicDescData(output)!!
                if (data.keep) {
                    data.aclResize.setContext()
                    val stream = memScoped {
                        alloc<aclrtStreamVar>().also { aclrtCreateStream(it.ptr).checkEq0("aclrtCreateStream") }.value
                    }
                    val rgbSize = (wStride(data.codecParams) * hStride(data.codecParams) * 3).toULong()  // RGB
                    val rgbDev = cPointer<CPointed> {
                        acldvppMalloc(it.reinterpret(), rgbSize).checkEq0("acldvppMalloc")
                    }
                    val rgbDesc = acldvppCreatePicDesc()
                    acldvppSetPicDescData(rgbDesc, rgbDev)
                    acldvppSetPicDescFormat(rgbDesc, PIXEL_FORMAT_RGB_888)
                    acldvppSetPicDescWidth(rgbDesc, data.codecParams.width.toUInt())
                    acldvppSetPicDescHeight(rgbDesc, data.codecParams.height.toUInt())
                    acldvppSetPicDescWidthStride(rgbDesc, wStride(data.codecParams).toUInt())
                    acldvppSetPicDescHeightStride(rgbDesc, hStride(data.codecParams).toUInt())
                    acldvppSetPicDescSize(rgbDesc, rgbSize.toUInt())
                    acldvppVpcResizeAsync(data.channelResize, output, rgbDesc, data.configResize, stream)
                        .checkEq0("acldvppVpcResizeAsync")
                    println("before")
                    aclrtSynchronizeStream(stream)
                    println("after")
                    aclrtDestroyStream(stream).checkEq0("aclrtDestroyStream")
                    val image = CreateImageRGB24(data.codecParams.width, data.codecParams.height)
                    repeat(data.codecParams.height) {
                        val dstLine = BytesPerLine(image)
                        val dst = Bits(image) + dstLine * it
                        val srcLine = data.codecParams.width * 3
                        val src = rgbDev.reinterpret<UByteVar>() + srcLine * it
                        aclrtMemcpy(dst, dstLine.toULong(), src, srcLine.toULong(), data.acl.downloadMode())
                    }
                    trySend(Frame(data.timestamp, image!!, null))
                    acldvppDestroyPicDesc(rgbDesc).checkEq0("acldvppDestroyPicDesc")
                    acldvppFree(rgbDev).checkEq0("acldvppFree")
                }
                data.acl.setContext()
                acldvppFree(frameDev).checkEq0("acldvppFree")
                acldvppFree(dev).checkEq0("acldvppFree")
                acldvppDestroyPicDesc(output).checkEq0("acldvppDestroyPicDesc")
                acldvppDestroyStreamDesc(input).checkEq0("acldvppDestroyStreamDesc")
            }
        })
        aclvdecSetChannelDescEnType(channel, H264_HIGH_LEVEL)
        aclvdecSetChannelDescOutPicFormat(channel, PIXEL_FORMAT_RGB_888)
        aclvdecCreateChannel(channel)
        val reorder = mutableSetOf<Frame>()
        fun pop() = reorder.minBy { it.timestamp }.also { reorder.remove(it) }
        return callbackFlow {
            input.packets.collect { packet ->
                val pts = packet!!.pointed.pts.toDouble() * timeBase.num / timeBase.den
                val start = Instant.fromEpochMilliseconds(input.formatCtx.start_time_realtime / 1000L)
                val timestamp = start + pts.seconds
                if (inputFrames == 0L) timestamp0 = timestamp
                val keep = inputFrames <= maxFrames(timestamp - timestamp0)
                if (keep) ++inputFrames
                acl.setContext()
                val size = packet.pointed.size.toULong()
                val dev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), size).checkEq0("acldvppMalloc") }
                aclrtMemcpy(dev, size, packet.pointed.data, size, acl.uploadMode()).checkEq0("aclrtMemcpy")
                val streamDesc = acldvppCreateStreamDesc()
                acldvppSetStreamDescData(streamDesc, dev)
                acldvppSetStreamDescSize(streamDesc, size.toUInt())
                val frameSize = (wStride(codecParams) * hStride(codecParams) * 3 / 2).toULong()  // NV12
                val frameDev =
                    cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).checkEq0("acldvppMalloc") }
                val picDesc = acldvppCreatePicDesc()
                acldvppSetPicDescData(picDesc, frameDev)
                acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                acldvppSetPicDescWidth(picDesc, codecParams.width.toUInt())
                acldvppSetPicDescHeight(picDesc, codecParams.height.toUInt())
                acldvppSetPicDescWidthStride(picDesc, wStride(codecParams).toUInt())
                acldvppSetPicDescHeightStride(picDesc, hStride(codecParams).toUInt())
                acldvppSetPicDescSize(picDesc, frameSize.toUInt())
                val data = Data(acl, aclResize, channelResize, configResize, codecParams, this, timestamp, keep)
                aclvdecSendFrame(channel, streamDesc, picDesc, null, StableRef.create(data).asCPointer())
                av_packet_unref(packet)
            }
            val streamDesc = acldvppCreateStreamDesc()
            acldvppSetStreamDescEos(streamDesc, 1U)
            aclvdecSendFrame(channel, streamDesc, null, null, null)
            aclvdecDestroyChannel(channel)
            aclvdecDestroyChannelDesc(channel)
            acl.close()
            close()
            awaitClose()
        }.transform { frame ->
            reorder.add(frame)
            while (REORDER_SIZE < reorder.size) emit(pop())
        }.onCompletion {
            while (!reorder.isEmpty()) emit(pop())
        }.buffer(Channel.UNLIMITED)
    }

    fun wStride(codecParams: AVCodecParameters) = (codecParams.width + 15) / 16 * 16
    fun hStride(codecParams: AVCodecParameters) = (codecParams.height + 1) / 2 * 2
}
