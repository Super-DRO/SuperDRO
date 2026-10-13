package org.superdro.core.store

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered

/** The suffix of the temporary entry of an atomic write. */
internal const val TEMP_SUFFIX: String = ".tmp"

/** The suffix of a file that cannot be decoded, before the time in ms. */
internal const val CORRUPT_SUFFIX: String = ".corrupt-"

/**
 * Writes the entry [name] atomically: [write] writes the full content to `<name>.tmp`, and a move
 * then replaces [name]. A process stop during the
 * write leaves the old entry whole. A failure of [write] or of the move throws, and the old entry
 * does not change.
 */
internal fun StoreIOProvider.writeAtomically(name: String, write: (Sink) -> Unit) {
    val temp = name + TEMP_SUFFIX
    try {
        sinkFor(temp).buffered().use { sink -> write(sink) }
        move(temp, name)
    } catch (error: Throwable) {
        delete(temp)
        throw error
    }
}

/** Reads the entry [name] with [read]. */
internal fun <T> StoreIOProvider.readEntry(name: String, read: (Source) -> T): T =
    sourceFor(name).buffered().use { source -> read(source) }
