package org.superdro.core.store

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * A [StoreIOProvider] in memory for the tests. A file is visible only after its sink closes, as
 * a file of a real file system after the write. [failMoves] and [failWritesOf] make a move or a
 * write fail.
 */
class FakeStoreIOProvider : StoreIOProvider {
    val files: MutableMap<String, ByteArray> = linkedMapOf()

    /** The names that the provider moved, in order: `from -> to`. */
    val moves: MutableList<String> = mutableListOf()

    var failMoves: Boolean = false
    var failWritesOf: String? = null

    override fun exists(storeName: String): Boolean = storeName in files

    override fun sinkFor(storeName: String): RawSink = object : RawSink {
        private val buffer = Buffer()

        override fun write(source: Buffer, byteCount: Long) {
            if (failWritesOf == storeName) throw IOException("The test fails the write of $storeName.")
            buffer.write(source, byteCount)
        }

        override fun flush() = Unit

        override fun close() {
            files[storeName] = buffer.readByteArray()
        }
    }

    override fun sourceFor(storeName: String): RawSource {
        val bytes = files[storeName] ?: throw IOException("No file $storeName.")
        return Buffer().apply { write(bytes) }
    }

    override fun sourceForUri(uri: String): RawSource = sourceFor(uri)

    override fun pathFor(storeName: String): String = "/fake/$storeName"

    override fun delete(storeName: String) {
        files.remove(storeName)
    }

    override fun move(from: String, to: String) {
        if (failMoves) throw IOException("The test fails the move of $from.")
        val bytes = files.remove(from) ?: throw IOException("No file $from.")
        files[to] = bytes
        moves += "$from -> $to"
    }

    fun text(storeName: String): String? = files[storeName]?.decodeToString()

    fun putText(storeName: String, text: String) {
        files[storeName] = text.encodeToByteArray()
    }
}

/** A wall clock for the tests. The time changes only through [advance]. */
class TestWallClock(private var current: Instant = Instant.parse("2026-10-12T08:00:00Z")) : Clock {
    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current += duration
    }
}
