package org.superdro.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** The tests of the keys of the lazy lists. */
class LazyKeysTest {

    @Test
    fun equalItemsGetDifferentKeysAndTheKeysAreUnique() {
        val keys = uniqueKeys(listOf("a", "b", "a", "1:a", "a")) { item -> item }
        assertEquals(listOf("0:a", "0:b", "1:a", "0:1:a", "2:a"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun anItemKeepsItsKeyWhenADifferentItemBeforeItGoesAway() {
        val before = uniqueKeys(listOf("x", "y", "z")) { item -> item }
        val after = uniqueKeys(listOf("y", "z")) { item -> item }
        assertEquals(before.drop(1), after)
    }
}
