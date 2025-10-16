import kotlinx.coroutines.channels.Channel

class Manager(val size: Int) {
    val availableIds = Channel<Int>(capacity = Channel.UNLIMITED).apply {
        repeat(size) { trySend(it) }
    }

    suspend fun use(block: suspend (Int?) -> Unit) {
        val id = availableIds.tryReceive().getOrNull()
        try {
            block(id)
        } finally {
            id?.let { availableIds.trySend(it) }
        }
    }
}
