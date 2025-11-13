import common.Frame
import common.InputRtsp
import common.InputRtsp.maxFrames
import common.Utils
import common.Utils.cPointer
import common.Utils.checkEq0
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.acl.*
import platform.ffmpeg.*
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
        val swsCtx: Utils.Ref<CPointer<SwsContext>?>,
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
        val swsCtx = Utils.Ref<CPointer<SwsContext>?>(null)
        val acl = SessionACL(0)
        acl.setContext()
        val channel = aclvdecCreateChannelDesc()
        aclvdecSetChannelDescChannelId(channel, id.toUInt())
        aclvdecSetChannelDescThreadId(channel, acl.threadId)
        aclvdecSetChannelDescCallback(channel, staticCFunction { input, output, rawData ->
            val data = rawData!!.asStableRef<Data>().let { ref ->
                ref.get().also { ref.dispose() }
            }
            data.acl.setContext()
            data.thisData.apply {
                val dev = acldvppGetStreamDescData(input)!!
                val frameDev = acldvppGetPicDescData(output)!!
                val frameSize = acldvppGetPicDescSize(output)
                if (data.keep) {
                    val image = memScoped {
                        val swFrameDev = allocArray<UByteVar>(frameSize.toInt())
                        aclrtMemcpy(
                            swFrameDev, frameSize.toULong(),
                            frameDev, frameSize.toULong(),
                            data.acl.downloadMode(),
                        ).checkEq0("aclrtMemcpy")
                        CreateImageRGB24(data.codecParams.width, data.codecParams.height)!!.also { image ->
                            if (data.swsCtx.value == null) {
                                data.swsCtx.value = sws_getContext(
                                    data.codecParams.width, data.codecParams.height, AV_PIX_FMT_NV12,
                                    data.codecParams.width, data.codecParams.height, AV_PIX_FMT_RGB24,
                                    SWS_BILINEAR.toInt(), null, null, null,
                                )
                            }
                            cValuesOf(data.codecParams.width, data.codecParams.width)
                            sws_scale(
                                data.swsCtx.value,
                                cValuesOf(swFrameDev, swFrameDev + data.codecParams.height * data.codecParams.width),
                                cValuesOf(data.codecParams.width, data.codecParams.width),
                                0, data.codecParams.height,
                                cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)),
                            )
                        }
                    }
                    trySend(Frame(data.timestamp, image, null))
                }
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
                val picSize = (wStride(codecParams.width) * hStride(codecParams.height) * 3 / 2).toULong()  // NV12
                val picDev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), picSize).checkEq0("acldvppMalloc") }
                val picDesc = acldvppCreatePicDesc()
                acldvppSetPicDescData(picDesc, picDev)
                acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                acldvppSetPicDescWidth(picDesc, codecParams.width.toUInt())
                acldvppSetPicDescHeight(picDesc, codecParams.height.toUInt())
                acldvppSetPicDescWidthStride(picDesc, wStride(codecParams.width).toUInt())
                acldvppSetPicDescHeightStride(picDesc, hStride(codecParams.height).toUInt())
                acldvppSetPicDescSize(picDesc, picSize.toUInt())
                val data = Data(acl, swsCtx, codecParams, this, timestamp, keep)
                aclvdecSendFrame(channel, streamDesc, picDesc, null, StableRef.create(data).asCPointer())
                av_packet_unref(packet)
            }
            val streamDesc = acldvppCreateStreamDesc()
            acldvppSetStreamDescEos(streamDesc, 1U)
            aclvdecSendFrame(channel, streamDesc, null, null, null)
            aclvdecDestroyChannel(channel)
            aclvdecDestroyChannelDesc(channel)
            acl.close()
            if (swsCtx.value != null) sws_freeContext(swsCtx.value)
            close()
            awaitClose()
        }.transform { frame ->
            reorder.add(frame)
            while (REORDER_SIZE < reorder.size) emit(pop())
        }.onCompletion {
            while (!reorder.isEmpty()) emit(pop())
        }.buffer(Channel.UNLIMITED)
    }
}
