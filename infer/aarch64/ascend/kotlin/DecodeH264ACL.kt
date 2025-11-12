import common.Frame
import common.InputRtsp
import common.InputRtsp.maxFrames
import common.Utils.cPointer
import common.Utils.check
import kotlinx.cinterop.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import platform.acl.*
import platform.ffmpeg.av_packet_unref
import platform.native.Bits
import platform.native.BytesPerLine
import platform.native.CreateImageRGB24
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object DecodeH264ACL : (InputRtsp.Output) -> Flow<Frame> {
    const val REORDER_SIZE = 5

    override fun invoke(input: InputRtsp.Output): Flow<Frame> {
        val codecParams = input.stream.codecpar
        val timeBase = input.stream.time_base
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val acl = SessionACL(0)
        val channel = aclvdecCreateChannelDesc()
        aclvdecSetChannelDescChannelId(channel, 0U)
        aclvdecSetChannelDescCallback(channel, null)
        aclvdecSetChannelDescEnType(channel, H264_HIGH_LEVEL)
        aclvdecSetChannelDescOutPicFormat(channel, PIXEL_FORMAT_RGB_888)
        aclvdecCreateChannel(channel)
        val reorder = mutableSetOf<Frame>()
        fun pop() = reorder.minBy { it.timestamp }.also { reorder.remove(it) }
        return input.packets.transform { packet ->
            try {
                acl.setContext()
                val size = packet!!.pointed.size.toULong()
                val dev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), size).check("acldvppMalloc") }
                aclrtMemcpy(dev, size, packet.pointed.data, size, acl.uploadMode()).check("aclrtMemcpy")
                val streamDesc = acldvppCreateStreamDesc()
                acldvppSetStreamDescData(streamDesc, dev)
                acldvppSetStreamDescSize(streamDesc, size.toUInt())
                val frameSize = (codecParams!!.pointed.width * codecParams.pointed.height * 3).toULong()  // RGB
                val frameDev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).check("acldvppMalloc") }
                val picDesc = acldvppCreatePicDesc()
                acldvppSetPicDescData(picDesc, frameDev)
                acldvppSetPicDescSize(picDesc, frameSize.toUInt())
                acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_RGB_888)
                aclvdecSendFrame(channel, streamDesc, picDesc, null, null)
                acl.process(-1)
                val image = CreateImageRGB24(codecParams.pointed.width, codecParams.pointed.height)
                repeat(codecParams.pointed.height) {
                    val dstLine = BytesPerLine(image)
                    val dst = Bits(image) + dstLine * it
                    val srcLine = codecParams.pointed.width * 3
                    val src = frameDev.reinterpret<UByteVar>() + srcLine * it
                    aclrtMemcpy(dst, dstLine.toULong(), src, srcLine.toULong(), acl.downloadMode()).check("aclrtMemcpy")
                }
                acldvppDestroyPicDesc(picDesc)
                acldvppFree(frameDev)
                acldvppDestroyStreamDesc(streamDesc)
                acldvppFree(dev)
                val pts = packet.pointed.pts.toDouble() * timeBase.num / timeBase.den
                val start = Instant.fromEpochMilliseconds(input.formatCtx.start_time_realtime / 1000L)
                val timestamp = start + pts.seconds
                if (inputFrames == 0L) timestamp0 = timestamp
                if (maxFrames(timestamp - timestamp0) < inputFrames) return@transform
                ++inputFrames
                emit(Frame(timestamp, image!!, null))
            } finally {
                if (packet != null) av_packet_unref(packet)
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
