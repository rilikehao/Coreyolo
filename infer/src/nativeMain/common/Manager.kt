package common

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class Manager(val size: Int) {
    @OptIn(DelicateCoroutinesApi::class)
    val dispatcher = newFixedThreadPoolContext(size + 1, "dispatcher")

    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val availableIds = Channel<Int>(capacity = size).apply { repeat(size) { trySend(it) } }

    suspend fun <T> use(block: (Int) -> T): Deferred<T> {
        val id = availableIds.receive()
        return scope.async {
            try {
                block(id)
            } finally {
                availableIds.trySend(id)
            }
        }
    }

    suspend fun close() { scope.coroutineContext[Job]?.cancelAndJoin() }
}
