import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

class PriorityQueueAgent<T>(val queue: PriorityQueue<T>) {
    sealed class Operation<T>
    class Send<T>(val item: T) : Operation<T>()
    class Receive<T>(val deferred: CompletableDeferred<T>) : Operation<T>()

    val ch = Channel<Operation<T>>(Channel.UNLIMITED)
    var wait: Receive<T>? = null

    suspend fun run(destroy: (T) -> Unit) {
        try {
            for (op in ch) {
                when (op) {
                    is Send<T> -> {
                        queue.push(op.item)
                        if (wait != null) queue.pop()?.let { value -> wait!!.deferred.complete(value); wait = null }
                    }

                    is Receive<T> -> {
                        when (val value = queue.pop()) {
                            null -> wait = op
                            else -> op.deferred.complete(value)
                        }
                    }
                }
            }
        } finally {
            ch.close()
            queue.heap.forEach { destroy(it) }
        }
    }

    suspend fun send(item: T) = ch.send(Send(item))

    suspend fun receive() = CompletableDeferred<T>().let {
        ch.send(Receive(it))
        it.await()
    }
}
