package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.util.Clock

/** The cadence rules, with virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordSchedulerTest {

    private val formatter = RecordFormatter.create(DataOutputConfig())

    /** The sent records as (time, X in µm). */
    private class Run(val values: MutableStateFlow<OutputValues>, val sent: MutableList<Pair<Long, Double?>>)

    private fun TestScope.start(cadence: DataOutputCadence, intervalMs: Long = 500L, maxRateHz: Int = 20): Run {
        val values = MutableStateFlow(OutputValues(xAbsolute = 0.0, yAbsolute = 0.0, zAbsolute = 0.0))
        val sent = mutableListOf<Pair<Long, Double?>>()
        val scheduler = RecordScheduler(cadence, intervalMs, maxRateHz, Clock { testScheduler.currentTime })
        backgroundScope.launch {
            scheduler.run(values, formatter::valueTexts) { current -> sent += testScheduler.currentTime to current.xAbsolute }
        }
        runCurrent()
        return Run(values, sent)
    }

    private fun TestScope.at(timeMs: Long) {
        advanceTimeBy(timeMs - testScheduler.currentTime)
        runCurrent()
    }

    private fun Run.expect(vararg records: Pair<Long, Double?>) {
        assertEquals(records.toList(), sent.toList())
    }

    private fun Run.set(x: Double) {
        values.value = values.value.copy(xAbsolute = x)
    }

    @Test
    fun theFixedIntervalSendsOneRecordEachIntervalAndIgnoresTheChanges() = runTest {
        val run = start(DataOutputCadence.FixedInterval, intervalMs = 100L)
        at(50)
        run.set(1000.0)
        at(99)
        assertTrue(run.sent.isEmpty())
        at(100)
        at(250)
        run.set(2000.0)
        at(300)
        run.expect(100L to 1000.0, 200L to 1000.0, 300L to 2000.0)
    }

    @Test
    fun onChangeSendsTheFirstChangeAtOnceAndThenWaitsForTheMinimumTime() = runTest {
        val run = start(DataOutputCadence.OnChange, maxRateHz = 20)
        at(1000)
        // No change: no record, also after a long time.
        assertTrue(run.sent.isEmpty())
        run.set(1000.0)
        runCurrent()
        run.expect(1000L to 1000.0)
        at(1010)
        run.set(2000.0)
        at(1030)
        run.set(3000.0)
        at(1049)
        assertEquals(1, run.sent.size)
        // 20 Hz gives 50 ms. At the end of the time, the newest record goes out.
        at(1050)
        run.expect(1000L to 1000.0, 1050L to 3000.0)
        at(5000)
        assertEquals(2, run.sent.size)
    }

    @Test
    fun aChangeSmallerThanTheLastDecimalSendsNoRecord() = runTest {
        val run = start(DataOutputCadence.OnChange)
        at(10)
        run.set(0.04)
        at(20)
        run.set(0.03)
        at(100)
        assertTrue(run.sent.isEmpty())
        run.set(0.05)
        runCurrent()
        run.expect(100L to 0.05)
    }

    @Test
    fun aChangeThatGoesBackInTheMinimumTimeSendsNoRecord() = runTest {
        val run = start(DataOutputCadence.OnChange)
        at(10)
        run.set(1000.0)
        runCurrent()
        at(20)
        run.set(2000.0)
        at(30)
        run.set(1000.0)
        at(200)
        run.expect(10L to 1000.0)
    }

    @Test
    fun onChangeAndIntervalAlsoSendsARecordAfterTheIntervalWithNoRecord() = runTest {
        val run = start(DataOutputCadence.OnChangeAndInterval, intervalMs = 500L)
        at(499)
        assertTrue(run.sent.isEmpty())
        at(500)
        at(1000)
        run.expect(500L to 0.0, 1000L to 0.0)
        at(1200)
        run.set(1000.0)
        runCurrent()
        // The next interval record is 500 ms after the record of the change.
        at(1699)
        assertEquals(3, run.sent.size)
        at(1700)
        run.expect(500L to 0.0, 1000L to 0.0, 1200L to 1000.0, 1700L to 1000.0)
    }

    @Test
    fun theMinimumTimeUsesTheRateLimit() = runTest {
        val run = start(DataOutputCadence.OnChange, maxRateHz = 3)
        at(0)
        run.set(1000.0)
        runCurrent()
        at(1)
        run.set(2000.0)
        at(332)
        assertEquals(1, run.sent.size)
        at(333)
        run.expect(0L to 1000.0, 333L to 2000.0)
    }

    @Test
    fun anIntervalOutsideTheLimitsIsRefused() {
        assertFailsWith<IllegalArgumentException> { RecordScheduler(DataOutputCadence.FixedInterval, 49L, 20, Clock { 0L }) }
        assertFailsWith<IllegalArgumentException> { RecordScheduler(DataOutputCadence.OnChange, 500L, 21, Clock { 0L }) }
    }
}
