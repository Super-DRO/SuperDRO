package org.superdro.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingBufferTest {

    @Test
    fun anEmptyBufferHasNoItem() {
        val buffer = RingBuffer<Int>(3)
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.size)
        assertNull(buffer.oldestOrNull())
        assertNull(buffer.newestOrNull())
        assertEquals(emptyList(), buffer.toList())
    }

    @Test
    fun itemsKeepTheirOrderUntilTheBufferIsFull() {
        val buffer = RingBuffer<Int>(3)
        buffer.add(1)
        buffer.add(2)
        assertEquals(listOf(1, 2), buffer.toList())
        assertFalse(buffer.isFull)
        buffer.add(3)
        assertTrue(buffer.isFull)
        assertEquals(1, buffer.oldestOrNull())
        assertEquals(3, buffer.newestOrNull())
    }

    @Test
    fun aFullBufferRemovesTheOldestItem() {
        val buffer = RingBuffer<Int>(3)
        (1..7).forEach(buffer::add)
        assertEquals(listOf(5, 6, 7), buffer.toList())
        assertEquals(3, buffer.size)
        assertEquals(5, buffer[0])
        assertEquals(7, buffer[2])
    }

    @Test
    fun theIteratorGivesTheOldestItemFirst() {
        val buffer = RingBuffer<String>(2)
        buffer.add("a")
        buffer.add("b")
        buffer.add("c")
        assertEquals(listOf("b", "c"), buffer.iterator().asSequence().toList())
    }

    @Test
    fun anIndexOutsideTheItemsFails() {
        val buffer = RingBuffer<Int>(4)
        buffer.add(1)
        assertFailsWith<IndexOutOfBoundsException> { buffer[1] }
        assertFailsWith<IndexOutOfBoundsException> { buffer[-1] }
    }

    @Test
    fun clearRemovesAllItems() {
        val buffer = RingBuffer<Int>(2)
        buffer.add(1)
        buffer.add(2)
        buffer.clear()
        assertTrue(buffer.isEmpty)
        buffer.add(3)
        assertEquals(listOf(3), buffer.toList())
    }

    @Test
    fun theCapacityMustBeMoreThanZero() {
        assertFailsWith<IllegalArgumentException> { RingBuffer<Int>(0) }
    }

    @Test
    fun theInputHistorySizeKeepsTheLast2048Samples() {
        val buffer = RingBuffer<Long>(2048)
        (0L until 3000L).forEach(buffer::add)
        assertEquals(2048, buffer.size)
        assertEquals(952L, buffer.oldestOrNull())
        assertEquals(2999L, buffer.newestOrNull())
    }
}
