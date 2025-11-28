import common.Command
import common.Input
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import platform.ffmpeg.AVPacket

@OptIn(ExperimentalForeignApi::class)
object Codec {
    class DecoderVideo(input: Input, packets: Flow<CPointer<AVPacket>?>) :
        DecoderACL(0, input, packets)

    class EncoderVideoH264(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        EncoderACL(id, name, input, "h264")

    class EncoderVideoH265(id: String, name: CompletableDeferred<String>?, input: Flow<Command.CommandImage>) :
        EncoderACL(id, name, input, "hevc")
}
