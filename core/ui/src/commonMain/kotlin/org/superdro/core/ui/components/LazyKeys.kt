package org.superdro.core.ui.components

/**
 * Gives one unique key for each item of [items], for a lazy list. The key of an item is
 * `"<n>:<base>"`: [base] is the base key of the item, and n is the number of earlier items with
 * the same base key. Thus two equal items get different keys, and an item keeps its key when a
 * different item before it changes. The keys are strings, so Android can save them.
 */
fun <T> uniqueKeys(items: List<T>, base: (T) -> String): List<String> {
    val counts = HashMap<String, Int>()
    return items.map { item ->
        val key = base(item)
        val count = counts[key] ?: 0
        counts[key] = count + 1
        "$count:$key"
    }
}
