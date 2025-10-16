class PriorityQueue<T>(selector: (T) -> Comparable<*>? = { it as Comparable<*> }) {
    private val comparator: Comparator<T> = compareBy(selector)
    private val heap = mutableListOf<T>()
    private fun parent(i: Int): Int = (i - 1) / 2
    private fun left(i: Int): Int = 2 * i + 1
    private fun right(i: Int): Int = 2 * i + 2

    fun push(item: T) {
        heap.add(item)
        siftUp(heap.size - 1)
    }

    fun pop(): T? {
        if (heap.isEmpty()) return null

        return if (heap.size == 1) {
            heap.removeAt(0)
        } else {
            val minItem = heap[0]
            heap[0] = heap.removeAt(heap.size - 1)
            siftDown(0)
            minItem
        }
    }

    fun isEmpty(): Boolean = heap.isEmpty()

    fun size(): Int = heap.size

    private fun siftUp(i: Int) {
        var child = i
        while (child > 0) {
            val p = parent(child)
            if (comparator.compare(heap[p], heap[child]) > 0) {
                swap(p, child)
                child = p
            } else {
                break
            }
        }
    }

    private fun siftDown(i: Int) {
        var parent = i
        val size = heap.size
        while (true) {
            var smallest = parent
            val l = left(parent)
            val r = right(parent)

            if (l < size && comparator.compare(heap[smallest], heap[l]) > 0) {
                smallest = l
            }
            if (r < size && comparator.compare(heap[smallest], heap[r]) > 0) {
                smallest = r
            }

            if (smallest != parent) {
                swap(parent, smallest)
                parent = smallest
            } else {
                break
            }
        }
    }

    private fun swap(i: Int, j: Int) {
        val temp = heap[i]
        heap[i] = heap[j]
        heap[j] = temp
    }
}
