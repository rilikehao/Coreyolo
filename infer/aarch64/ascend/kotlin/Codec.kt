import common.*
import common.StringFormat.toString
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVFormatContext
import platform.ffmpeg.AVPacket
import platform.ffmpeg.AV_PIX_FMT_YUV420P

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(input: Input, packets: Flow<CPointer<AVPacket>?>) :
        DecoderACL(0, input, packets)

    class EncoderVideoH264(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        EncoderACL(id, name, input, "h264")

    class EncoderVideoH264Vod(
        id: String,
        name: CompletableDeferred<String>?,
        input: Flow<Command.CommandImage>,
    ) : Encoder {
        val data = when (AppConfig.instance.processing.vodSoftwareEncode) {
            false -> EncoderACL(id, name, input, "h264")
            true -> EncoderFFmpeg(
                id, name, input, "h264", AV_PIX_FMT_YUV420P, arrayOf(
                    "fflags" to "nobuffer",
                    "preset" to "superfast",
                    "qp" to (AppConfig.instance.processing.qH264 - 1.5).toString(1),
                )
            )
        }

        override fun initStream(formatContext: AVFormatContext) = data.initStream(formatContext)
        override fun startTimeRealtime() = data.startTimeRealtime()
        override suspend fun invoke() = data.invoke()
    }


    class EncoderVideoH265(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        EncoderACL(id, name, input, "h265")
}
