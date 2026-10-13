package org.superdro.core.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.superdro.core.model.AdapterAddress
import org.superdro.core.util.Clock

/**
 * A transport that plays a recorded byte stream with its times. It is a debug class and a test
 * fixture. The writes of the session go to [written], so a
 * test can examine them.
 *
 * The flow [incoming] waits until the time of each chunk after [open], and then gives the chunk.
 * The wait uses the coroutine time, so a test with virtual time plays the stream at once.
 *
 * @param chunks The chunks, for example from [RecordedStream.parse].
 * @param endWithLoss True when the flow ends with [LinkFailure.Lost] after the last chunk. False
 *   when the link stays open with no more data after the last chunk.
 * @param clock The clock that gives the write times in [written]. Null gives the time 0.
 */
class ReplayTransport(
    override val address: AdapterAddress,
    private val chunks: List<RecordedChunk>,
    private val endWithLoss: Boolean = false,
    private val clock: Clock? = null,
) : Transport {

    /** One write of the session. */
    data class Write(val timeMs: Long, val bytes: List<Byte>)

    private val writes = ArrayList<Write>()
    private var opened = false
    private var closed = false

    /** The writes of the session, in their order. */
    val written: List<Write> get() = writes.toList()

    override val incoming: Flow<ByteArray> = flow {
        check(opened) { "Open the replay transport before the read." }
        var elapsed = 0L
        for (chunk in chunks) {
            if (chunk.timeMs > elapsed) {
                delay(chunk.timeMs - elapsed)
                elapsed = chunk.timeMs
            }
            if (closed) return@flow
            emit(chunk.bytes.copyOf())
        }
        if (endWithLoss) throw LinkFailureException(LinkFailure.Lost, "The replay ended.")
        while (!closed) delay(IDLE_WAIT_MS)
    }

    override suspend fun open() {
        if (closed) throw LinkFailureException(LinkFailure.Refused, "The replay transport is closed.")
        opened = true
    }

    override suspend fun write(bytes: ByteArray) {
        if (!opened || closed) throw LinkFailureException(LinkFailure.Lost, "The replay link is not open.")
        writes += Write(clock?.nowMs() ?: 0L, bytes.toList())
    }

    override suspend fun close() {
        closed = true
    }

    private companion object {
        /** The wait of the open link after the last chunk. */
        const val IDLE_WAIT_MS: Long = 1_000L
    }
}
