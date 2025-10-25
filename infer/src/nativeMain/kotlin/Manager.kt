import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class Manager<T, R>(val size: Int, val onDrop: (T) -> R) {
    val availableIds = Channel<Int>(capacity = size).apply {
        repeat(size) { trySend(it) }
    }

    fun use(input: T, block: (Int) -> R) = when (val id = availableIds.tryReceive().getOrNull()) {
        null -> CompletableDeferred(onDrop(input))
        else -> CoroutineScope(Dispatchers.IO).async {
            try {
                block(id)
            } finally {
                availableIds.trySend(id)
            }
        }
    }
}
