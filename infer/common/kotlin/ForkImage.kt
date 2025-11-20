package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import platform.native.CreateImageCopy
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class ForkImage(val input: Flow<Command.CommandImage>) :
        () -> Pair<Flow<Command.CommandImage>, Flow<Command.CommandImage>> {
    override fun invoke(): Pair<Flow<Command.CommandImage>, Flow<Command.CommandImage>> {
        val dump = Channel<Command.CommandImage>()
        val main = input
            .onEach { dump.send(Command.CommandImage(it.timestamp, CreateImageCopy(it.data)!!)) }
            .onCompletion { dump.close() }
        val side = dump.consumeAsFlow()
        return Pair(main, side)
    }
}
