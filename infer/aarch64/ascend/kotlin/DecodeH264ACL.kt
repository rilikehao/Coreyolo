import common.Frame
import common.InputRtsp
import common.InputRtsp.maxFrames
import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
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
object DecodeH264ACL : (InputRtsp.Output) -> Flow<Frame> {
    const val REORDER_SIZE = 5

    data class Data(
        val thisData: ProducerScope<Frame>,
        val codecParams: AVCodecParameters,
        val timestamp: Instant,
    )

    override fun invoke(input: InputRtsp.Output): Flow<Frame> {
        val codecParams = input.stream.codecpar!!.pointed
        val timeBase = input.stream.time_base
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val acl = SessionACL(0)
        val channel = aclvdecCreateChannelDesc()
        aclvdecSetChannelDescChannelId(channel, 0U)
        aclvdecSetChannelDescCallback(channel, staticCFunction { input, output, rawData ->
            val dev = acldvppGetStreamDescData(input)
            val frameDev = acldvppGetPicDescData(output)!!
            val data = rawData!!.asStableRef<Data>().get()
            data.thisData.apply {
                val image = CreateImageRGB24(data.codecParams.width, data.codecParams.height)
                aclrtMemcpy2d(
                    Bits(image), BytesPerLine(image).toULong(),
                    frameDev.reinterpret<UByteVar>(), (data.codecParams.width * 3).toULong(),
                    (data.codecParams.width * 3).toULong(),
                    data.codecParams.height.toULong(),
                    acl.downloadMode(),
                ).check("aclrtMemcpy2d")
                acldvppFree(frameDev)
                acldvppFree(dev)
                acldvppDestroyPicDesc(output)
                acldvppDestroyStreamDesc(input)
                trySend(Frame(data.timestamp, image!!, null))
            }
        })
        aclvdecSetChannelDescEnType(channel, H264_HIGH_LEVEL)
        aclvdecSetChannelDescOutPicFormat(channel, PIXEL_FORMAT_RGB_888)
        aclvdecCreateChannel(channel)
        val reorder = mutableSetOf<Frame>()
        fun pop() = reorder.minBy { it.timestamp }.also { reorder.remove(it) }
        return input.packets.flatMapConcat { packet ->
            callbackFlow {
                try {
                    val pts = packet!!.pointed.pts.toDouble() * timeBase.num / timeBase.den
                    val start = Instant.fromEpochMilliseconds(input.formatCtx.start_time_realtime / 1000L)
                    val timestamp = start + pts.seconds
                    if (inputFrames == 0L) timestamp0 = timestamp
                    if (maxFrames(timestamp - timestamp0) < inputFrames) return@callbackFlow
                    ++inputFrames
                    acl.setContext()
                    val size = packet.pointed.size.toULong()
                    val dev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), size).check("acldvppMalloc") }
                    aclrtMemcpy(dev, size, packet.pointed.data, size, acl.uploadMode()).check("aclrtMemcpy")
                    val streamDesc = acldvppCreateStreamDesc()
                    acldvppSetStreamDescData(streamDesc, dev)
                    acldvppSetStreamDescSize(streamDesc, size.toUInt())
                    val frameSize = (codecParams.width * codecParams.height * 3).toULong()  // RGB
                    val frameDev =
                        cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).check("acldvppMalloc") }
                    val picDesc = acldvppCreatePicDesc()
                    acldvppSetPicDescData(picDesc, frameDev)
                    acldvppSetPicDescSize(picDesc, frameSize.toUInt())
                    acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_RGB_888)
                    val data = Data(this, codecParams, timestamp)
                    aclvdecSendFrame(channel, streamDesc, picDesc, null, StableRef.create(data).asCPointer())
                    acl.process(-1)
                } finally {
                    if (packet != null) av_packet_unref(packet)
                }
            }
        }.onCompletion {
            aclvdecDestroyChannel(channel)
            aclvdecDestroyChannelDesc(channel)
            acl.close()
        }.transform { frame ->
            reorder.add(frame)
            while (REORDER_SIZE < reorder.size) emit(pop())
        }.onCompletion {
            while (!reorder.isEmpty()) emit(pop())
        }.buffer(Channel.UNLIMITED)
    }
}
