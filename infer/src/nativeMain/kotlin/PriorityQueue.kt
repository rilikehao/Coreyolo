class PriorityQueue<T>(val bufferSize: Int, selector: (T) -> Comparable<*>? = { it as Comparable<*> }) {
    val comparator: Comparator<T> = compareBy(selector)
    val heap = mutableListOf<T>()
    fun parent(i: Int): Int = (i - 1) / 2
    fun left(i: Int): Int = 2 * i + 1
    fun right(i: Int): Int = 2 * i + 2

    fun push(item: T) {
        heap.add(item)
        siftUp(heap.size - 1)
    }

    fun pop(): T? {
        if (heap.size <= bufferSize) return null

        return if (heap.size == 1) {
            heap.removeAt(0)
        } else {
            val minItem = heap[0]
            heap[0] = heap.removeAt(heap.size - 1)
            siftDown(0)
            minItem
        }
    }

    fun siftUp(i: Int) {
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

    fun siftDown(i: Int) {
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

    fun swap(i: Int, j: Int) {
        val temp = heap[i]
        heap[i] = heap[j]
        heap[j] = temp
    }
}
