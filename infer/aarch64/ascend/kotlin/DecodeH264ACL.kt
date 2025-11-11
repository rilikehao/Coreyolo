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
import platform.ffmpeg.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
object DecodeH264ACL : (InputRtsp.Output) -> Flow<Frame> {
    override fun invoke(input: InputRtsp.Output): Flow<Frame> {
        val codecParams = input.stream.codecpar
        val timeBase = input.stream.time_base
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        aclInit(null).check("aclInit")
        val device = 0
        aclrtSetDevice(device).check("aclrtSetDevice")
        val context = cPointer<CPointed> {
            aclrtCreateContext(it.reinterpret(), device).check("aclrtCreateContext")
        }
        val stream = cPointer<CPointed> {
            aclrtCreateStream(it.reinterpret()).check("aclrtCreateStream")
        }
        val runMode = memScoped {
            alloc<aclrtRunMode.Var>().also { aclrtGetRunMode(it.ptr) }.value
        }
        val channel = aclvdecCreateChannelDesc()
        aclvdecSetChannelDescChannelId(channel, 0U)
        aclvdecSetChannelDescCallback(channel, staticCFunction { input, output, data ->

        })
        // 0：H265 main level
        // 1：H264 baseline level
        // 2：H264 main level
        // 3：H264 high level
        aclvdecSetChannelDescEnType(channel, 3U)
        // 1：YUV420 semi-planner（nv12）; 2：YVU420 semi-planner（nv21）
        val kPixel = 1U
        aclvdecSetChannelDescOutPicFormat(channel, kPixel)
        aclvdecCreateChannel(channel)
        return input.packets.transform { packet ->
            try {
                val size = packet!!.pointed.size.toULong()
                val dev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), size).check("acldvppMalloc") }
                val mode = when (runMode) {
                    aclrtRunMode.ACL_HOST -> aclrtMemcpyKind.ACL_MEMCPY_HOST_TO_DEVICE
                    else -> aclrtMemcpyKind.ACL_MEMCPY_DEVICE_TO_DEVICE
                }
                aclrtMemcpy(dev, size, packet.pointed.data, size, mode).check("aclrtMemcpy")
                val streamDesc = acldvppCreateStreamDesc()
                acldvppSetStreamDescData(streamDesc, dev)
                acldvppSetStreamDescSize(streamDesc, size.toUInt())
                val frameSize = (codecParams!!.pointed.width * codecParams.pointed.height * 3 / 2).toULong()  // NV12
                val frameDev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), frameSize).check("acldvppMalloc") }
                val picDesc = acldvppCreatePicDesc()
                acldvppSetPicDescData(picDesc, frameDev)
                acldvppSetPicDescSize(picDesc, frameSize.toUInt())
                acldvppSetPicDescFormat(picDesc, kPixel)
                aclvdecSendFrame(channel, streamDesc, picDesc, null, null)
                aclrtProcessReport(-1)
                acldvppDestroyStreamDesc(streamDesc)
                acldvppFree(frameDev)
                acldvppFree(dev)
                val pts = packet.pointed.pts.toDouble() * timeBase.num / timeBase.den
                val start = Instant.fromEpochMilliseconds(input.formatCtx.start_time_realtime / 1000L)
                val timestamp = start + pts.seconds
                if (inputFrames == 0L) timestamp0 = timestamp
                if (maxFrames(timestamp - timestamp0) < inputFrames) return@transform
                ++inputFrames
                emit(Frame(timestamp, toRGBImage, null))
            } finally {
                if (packet != null) av_packet_unref(packet)
            }
        }.onCompletion {
            aclvdecDestroyChannel(channel)
            aclvdecDestroyChannelDesc(channel)
            aclrtDestroyStream(stream)
            aclrtDestroyContext(context)
            aclrtResetDevice(device)
            aclFinalize()
        }.buffer(Channel.UNLIMITED)
    }
}
