package common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
class ForkLabel(val input: Flow<Command>) : () -> Pair<Flow<Command>, Flow<String>> {
    override fun invoke(): Pair<Flow<Command>, Flow<String>> {
        val dump = Channel<String>()
        val main = input.onEach {
            if (it is Command.CommandLabel) dump.send(Detections.dump(it.timestamp, it.data))
        }.onCompletion { dump.close() }
        val side = dump.consumeAsFlow()
        return Pair(main, side)
    }
}
