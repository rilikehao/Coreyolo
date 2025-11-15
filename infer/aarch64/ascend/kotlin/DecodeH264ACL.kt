import cnames.structs.acldvppPicDesc
import cnames.structs.acldvppStreamDesc
import cnames.structs.aclvdecChannelDesc
import common.Frame
import common.InputRtsp
import common.InputRtsp.maxFrames
import common.Utils
import common.Utils.cPointer
import common.Utils.checkEq0
import kotlinx.cinterop.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
object DecodeH264ACL : suspend (Int, InputRtsp.Output) -> Flow<Frame> {
    const val REORDER_SIZE = 5

    override suspend fun invoke(id: Int, input: InputRtsp.Output): Flow<Frame> {
        val codecParams = input.stream.codecpar!!.pointed
        val timeBase = input.stream.time_base
        var inputFrames = 0L
        var timestamp0 = Clock.System.now()
        val swsCtx = Utils.Ref<CPointer<SwsContext>?>(null)
        val acl = SessionACL(0)
        var channel: CPointer<aclvdecChannelDesc>? = null
        withContext(acl.main) {
            acl.setContext()
            channel = aclvdecCreateChannelDesc()
            aclvdecSetChannelDescChannelId(channel, id.toUInt())
            aclvdecSetChannelDescThreadId(channel, acl.threadId.await())
            aclvdecSetChannelDescCallback(channel, staticCFunction { input, output, rawData ->
                rawData!!.asStableRef<(
                    CPointer<acldvppStreamDesc>, CPointer<acldvppPicDesc>,
                ) -> Unit>().let { ref ->
                    ref.get().also { ref.dispose() }(input!!, output!!)
                }
            })
            aclvdecSetChannelDescEnType(channel, H264_HIGH_LEVEL)
            aclvdecSetChannelDescOutPicFormat(channel, PIXEL_FORMAT_RGB_888)
            aclvdecCreateChannel(channel)
            acl.channelReady.complete(Unit)
        }
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
                suspend fun run(input: CPointer<acldvppStreamDesc>, output: CPointer<acldvppPicDesc>) {
                    val dev = acldvppGetStreamDescData(input)!!
                    val frameDev = acldvppGetPicDescData(output)!!
                    val frameSize = acldvppGetPicDescSize(output)
                    if (keep) {
                        val image = memScoped {
                            val swFrameDev = allocArray<UByteVar>(frameSize.toInt())
                            aclrtMemcpy(
                                swFrameDev, frameSize.toULong(),
                                frameDev, frameSize.toULong(),
                                acl.downloadMode(),
                            ).checkEq0("aclrtMemcpy")
                            CreateImageRGB24(codecParams.width, codecParams.height)!!.also { image ->
                                if (swsCtx.value == null) {
                                    swsCtx.value = sws_getContext(
                                        codecParams.width, codecParams.height, AV_PIX_FMT_NV12,
                                        codecParams.width, codecParams.height, AV_PIX_FMT_RGB24,
                                        SWS_BILINEAR.toInt(), null, null, null,
                                    )
                                }
                                cValuesOf(codecParams.width, codecParams.width)
                                sws_scale(
                                    swsCtx.value,
                                    cValuesOf(
                                        swFrameDev,
                                        swFrameDev + codecParams.height * codecParams.width
                                    ),
                                    cValuesOf(codecParams.width, codecParams.width),
                                    0, codecParams.height,
                                    cValuesOf(Bits(image)), cValuesOf(BytesPerLine(image)),
                                )
                            }
                        }
                        send(Frame(timestamp, image, null))
                    }
                    acldvppFree(frameDev).checkEq0("acldvppFree")
                    acldvppFree(dev).checkEq0("acldvppFree")
                    acldvppDestroyPicDesc(output).checkEq0("acldvppDestroyPicDesc")
                    acldvppDestroyStreamDesc(input).checkEq0("acldvppDestroyStreamDesc")
                }

                val runACL = { input: CPointer<acldvppStreamDesc>, output: CPointer<acldvppPicDesc> ->
                    runBlocking { withContext(acl.main) { acl.setContext(); run(input, output) } }
                }
                val data = StableRef.create(runACL).asCPointer()
                withContext(acl.main) {
                    acl.setContext()
                    val size = packet.pointed.size.toULong()
                    val dev = cPointer<CPointed> { acldvppMalloc(it.reinterpret(), size).checkEq0("acldvppMalloc") }
                    aclrtMemcpy(dev, size, packet.pointed.data, size, acl.uploadMode()).checkEq0("aclrtMemcpy")
                    val streamDesc = acldvppCreateStreamDesc()
                    acldvppSetStreamDescData(streamDesc, dev)
                    acldvppSetStreamDescSize(streamDesc, size.toUInt())
                    val picSize = (wStride(codecParams.width) * hStride(codecParams.height) * 3 / 2).toULong()  // NV12
                    val picDev =
                        cPointer<CPointed> { acldvppMalloc(it.reinterpret(), picSize).checkEq0("acldvppMalloc") }
                    val picDesc = acldvppCreatePicDesc()
                    acldvppSetPicDescData(picDesc, picDev)
                    acldvppSetPicDescFormat(picDesc, PIXEL_FORMAT_YUV_SEMIPLANAR_420)
                    acldvppSetPicDescWidth(picDesc, codecParams.width.toUInt())
                    acldvppSetPicDescHeight(picDesc, codecParams.height.toUInt())
                    acldvppSetPicDescWidthStride(picDesc, wStride(codecParams.width).toUInt())
                    acldvppSetPicDescHeightStride(picDesc, hStride(codecParams.height).toUInt())
                    acldvppSetPicDescSize(picDesc, picSize.toUInt())
                    aclvdecSendFrame(channel, streamDesc, picDesc, null, data)
                }
                av_packet_unref(packet)
            }
            withContext(acl.main) {
                acl.setContext()
                val streamDesc = acldvppCreateStreamDesc()
                acldvppSetStreamDescEos(streamDesc, 1U)
                aclvdecSendFrame(channel, streamDesc, null, null, null)
                aclvdecDestroyChannel(channel)
                aclvdecDestroyChannelDesc(channel)
            }
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
