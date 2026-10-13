package org.superdro.core.dataoutput

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.util.Clock

/**
 * Decides when the output sends a record. The output makes
 * and counts each record also when no client is connected.
 *
 * - [DataOutputCadence.FixedInterval]: one record each [intervalMs] (a fixed delay after each
 *   record). The first record goes out one interval after the start. A value change sends no
 *   record.
 * - [DataOutputCadence.OnChange]: a record when a value text changes ([RecordFormatter.valueTexts];
 *   the time stamp is not part of the check). Thus a change smaller than the last decimal sends
 *   no record. The first change after the start goes out at once. The minimum time between two
 *   records is `1000 / maxRateHz` ms (integer division). A change in that time waits, and at the
 *   end of that time the newest record goes out.
 * - [DataOutputCadence.OnChangeAndInterval]: the same, and also a record with the current values
 *   when no record went out for [intervalMs]. The first record of this kind goes out [intervalMs]
 *   after the start, if no change occurred before.
 *
 * The values at the start are the reference of the first change check.
 *
 * @param clock The monotonic clock of the delays. A test with virtual time gives
 *   `Clock { testScheduler.currentTime }`.
 */
class RecordScheduler(
    private val cadence: DataOutputCadence,
    private val intervalMs: Long,
    maxRateHz: Int,
    private val clock: Clock,
) {
    /** The minimum time between two records of a change, in ms. */
    val minimumGapMs: Long = DataOutputLimits.minimumGapMs(maxRateHz)

    init {
        require(intervalMs in DataOutputLimits.INTERVAL_MS) { "The interval $intervalMs ms is not in ${DataOutputLimits.INTERVAL_MS}." }
    }

    /**
     * Sends the records until the caller cancels the coroutine.
     *
     * @param values The current values.
     * @param texts Gives the value texts of the change check.
     * @param send Makes and sends one record of the values that it gets.
     */
    suspend fun run(
        values: StateFlow<OutputValues>,
        texts: (OutputValues) -> List<String?>,
        send: suspend (OutputValues) -> Unit,
    ) {
        when (cadence) {
            DataOutputCadence.FixedInterval -> while (true) {
                delay(intervalMs)
                send(values.value)
            }
            DataOutputCadence.OnChange -> onChange(values, texts, send, withInterval = false)
            DataOutputCadence.OnChangeAndInterval -> onChange(values, texts, send, withInterval = true)
        }
    }

    private suspend fun onChange(
        values: StateFlow<OutputValues>,
        texts: (OutputValues) -> List<String?>,
        send: suspend (OutputValues) -> Unit,
        withInterval: Boolean,
    ) = coroutineScope {
        val startAt = clock.nowMs()
        var lastTexts: List<String?> = texts(values.value)
        var lastSentAt: Long? = null
        val updates = Channel<Unit>(Channel.CONFLATED)
        val collector = launch { values.collect { updates.trySend(Unit) } }
        try {
            while (true) {
                val updated = if (withInterval) {
                    val waitMs = (lastSentAt ?: startAt) + intervalMs - clock.nowMs()
                    waitMs > 0 && withTimeoutOrNull(waitMs) { updates.receive() } != null
                } else {
                    updates.receive()
                    true
                }
                if (!updated) {
                    // No record went out for the interval: send the current values.
                    val current = values.value
                    send(current)
                    lastTexts = texts(current)
                    lastSentAt = clock.nowMs()
                    continue
                }
                if (texts(values.value) == lastTexts) continue
                val sentAt = lastSentAt
                if (sentAt != null) {
                    val waitMs = sentAt + minimumGapMs - clock.nowMs()
                    if (waitMs > 0) delay(waitMs)
                }
                // The newest values at the end of the minimum time. Remove the signal first, so
                // that a later update gives a new signal.
                updates.tryReceive()
                val newest = values.value
                val newestTexts = texts(newest)
                if (newestTexts == lastTexts) continue
                send(newest)
                lastTexts = newestTexts
                lastSentAt = clock.nowMs()
            }
        } finally {
            collector.cancel()
        }
    }
}
