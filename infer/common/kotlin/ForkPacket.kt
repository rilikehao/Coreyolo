package common

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import platform.ffmpeg.AVPacket
import platform.ffmpeg.av_packet_alloc
import platform.ffmpeg.av_packet_ref
import platform.native.CreateImageCopy
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class ForkPacket(val input: Flow<CPointer<AVPacket>?>) :
        () -> Pair<Flow<CPointer<AVPacket>?>, Flow<CPointer<AVPacket>?>> {
    override fun invoke(): Pair<Flow<CPointer<AVPacket>?>, Flow<CPointer<AVPacket>?>> {
        val dump = Channel<CPointer<AVPacket>?>()
        val main = input.onEach { packet ->
            av_packet_alloc()!!.let {
                av_packet_ref(it, packet)
                dump.send(it)
            }
        }.onCompletion { dump.close() }
        val side = dump.consumeAsFlow()
        return Pair(main, side)
    }
}
