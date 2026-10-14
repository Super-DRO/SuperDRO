package org.superdro.core.connection

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gives the recorded chunks of one link to [sink] on a dispatcher for I/O, not on the thread of
 * the session. The session puts each chunk in a queue with no wait. One writer coroutine takes the
 * chunks from the queue and gives them to [sink] in batches, at most one batch each [batchMs].
 * Thus the hex text, the file writes, and the update of the record list do not occur for each
 * chunk on the thread of the parse.
 *
 * When the queue is full (the file writes are too slow), the queue drops the new chunk and counts
 * it in [dropped]. The parse does not wait for the record.
 *
 * [close] ends the queue. The writer then gives the chunks in the queue to [sink] and closes it.
 *
 * An exception of [sink] stops the record of the link and goes to [onFailure]. The session treats
 * it as a defect of the session.
 */
internal class QueuedRecordSink(
    private val sink: RecordSink,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val onFailure: (Throwable) -> Unit,
    private val batchMs: Long = BATCH_MS,
    capacity: Int = CAPACITY,
) {
    private val chunks = Channel<RecordedChunk>(capacity)
    private val closed = CompletableDeferred<Unit>()
    private var failed = false

    /** The chunks that the full queue did not keep. Only the session reads and changes it. */
    var dropped: Long = 0L
        private set

    init {
        scope.launch(dispatcher) { run() }
    }

    /** Puts [chunk] in the queue with no wait. */
    fun write(chunk: RecordedChunk) {
        if (chunks.trySend(chunk).isFailure) dropped += 1
    }

    /** Ends the queue. The writer gives the chunks that wait to the sink, then closes it. */
    fun close() {
        chunks.close()
        closed.complete(Unit)
    }

    private suspend fun run() {
        val batch = ArrayList<RecordedChunk>()
        try {
            while (true) {
                val first = chunks.receiveCatching().getOrNull() ?: break
                batch += first
                drainInto(batch)
                give(batch)
                // The next batch comes after the interval, or at once after close().
                withTimeoutOrNull(batchMs) { closed.await() }
            }
        } finally {
            withContext(NonCancellable) {
                drainInto(batch)
                give(batch)
                try {
                    sink.close()
                } catch (error: Exception) {
                    log.w(error) { "The record sink did not close" }
                }
            }
        }
    }

    private fun drainInto(batch: MutableList<RecordedChunk>) {
        while (true) batch += chunks.tryReceive().getOrNull() ?: return
    }

    private fun give(batch: MutableList<RecordedChunk>) {
        if (batch.isEmpty()) return
        try {
            if (!failed) sink.writeAll(batch)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed = true
            log.w(error) { "The record sink failed. The record of this link stops." }
            onFailure(error)
        } finally {
            batch.clear()
        }
    }

    companion object {
        /** The interval of the batches: four updates of the record each second, at most. */
        const val BATCH_MS: Long = 250L

        /** The chunks that wait for the writer. At 1000 chunks each second, this is 4 s of data. */
        const val CAPACITY: Int = 4096

        private val log = Arbor.tag("StreamRecorder")
    }
}
