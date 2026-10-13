package org.superdro.core.util

/**
 * A ring buffer with a fixed [capacity]. When the buffer is full, [add] removes the oldest item.
 *
 * The input history of each adapter input keeps the last 2048 samples in a buffer of this type.
 * The class is not thread-safe. One session uses it from one coroutine.
 *
 * Index 0 is the oldest item. Index `size - 1` is the newest item.
 */
class RingBuffer<T>(val capacity: Int) : Iterable<T> {

    init {
        require(capacity > 0) { "The capacity must be more than 0, but it is $capacity." }
    }

    private val items = arrayOfNulls<Any?>(capacity)
    private var start = 0

    /** The number of items in the buffer, from 0 to [capacity]. */
    var size: Int = 0
        private set

    /** True when the buffer has no item. */
    val isEmpty: Boolean get() = size == 0

    /** True when the buffer has [capacity] items. The next [add] then removes the oldest item. */
    val isFull: Boolean get() = size == capacity

    /** Adds [item] as the newest item. When the buffer is full, the oldest item goes away. */
    fun add(item: T) {
        val end = (start + size) % capacity
        items[end] = item
        if (size == capacity) {
            start = (start + 1) % capacity
        } else {
            size += 1
        }
    }

    /** Gives the item at [index]: 0 is the oldest item, `size - 1` is the newest item. */
    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("The index $index is not in 0 until $size.")
        }
        return items[(start + index) % capacity] as T
    }

    /** Gives the oldest item, or null when the buffer is empty. */
    fun oldestOrNull(): T? = if (isEmpty) null else get(0)

    /** Gives the newest item, or null when the buffer is empty. */
    fun newestOrNull(): T? = if (isEmpty) null else get(size - 1)

    /** Removes all items. */
    fun clear() {
        items.fill(null)
        start = 0
        size = 0
    }

    /** Gives a list of the items, from the oldest to the newest. */
    fun toList(): List<T> = List(size) { index -> get(index) }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var next = 0
        override fun hasNext(): Boolean = next < size
        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return get(next++)
        }
    }
}
