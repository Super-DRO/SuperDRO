package org.superdro.core.protocol

import org.superdro.core.protocol.AdapterMessage.AxisCount
import org.superdro.core.protocol.AdapterMessage.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The time stamps of the adapter messages. */
class TimeBaseTest {

    private val t1 = 50_000L

    @Test
    fun theTimeStampExample() {
        val base = TimeBase()
        assertNull(base.offsetMs)
        assertEquals(t1, base.onTimestamp(1000, t1))
        assertEquals(t1 - 1000, base.offsetMs)
        assertEquals(t1 + 1000, base.onTimestamp(2000, t1 + 300))
        assertEquals(t1 - 1000, base.offsetMs)
    }

    @Test
    fun theDriftRuleSetsANewOffsetAfterMoreThan250Ms() {
        val base = TimeBase()
        base.onTimestamp(1000, t1)
        base.onTimestamp(2000, t1 + 1000)
        // @BB8; (3000) arrives at t1 + 2400. The predicted time is t1 + 2000. The drift is 400 ms.
        assertEquals(t1 + 2400, base.onTimestamp(3000, t1 + 2400))
        assertEquals(t1 + 2400 - 3000, base.offsetMs)
    }

    @Test
    fun aDriftOf250MsOrANegativeDriftKeepsTheOffset() {
        val base = TimeBase()
        base.onTimestamp(1000, t1)
        assertEquals(t1 + 2000, base.onTimestamp(3000, t1 + 2250))
        assertEquals(t1 - 1000, base.offsetMs)
        // The adapter clock is fast: the drift is negative.
        assertEquals(t1 + 4000, base.onTimestamp(5000, t1 + 3500))
        assertEquals(t1 - 1000, base.offsetMs)
    }

    @Test
    fun theOffsetResetsWhenTheAdapterTimeGoesBack() {
        val base = TimeBase()
        base.onTimestamp(1000, t1)
        base.onTimestamp(2000, t1 + 1000)
        // The adapter restarted: k = 100 is less than the last k = 2000.
        assertEquals(t1 + 1100, base.onTimestamp(100, t1 + 1100))
        assertEquals(t1 + 1000, base.offsetMs)
        assertEquals(100L, base.lastAdapterTime)
    }

    @Test
    fun aReadoutGetsTheTimeOfTheLastTimestampElseItsOwnStart() {
        val parser = AdapterParser()
        assertEquals(listOf(AxisCount('X', 1, t1 - 500)), parser.feed("X1;", t1 - 500))
        assertEquals(listOf(Timestamp(1000, t1)), parser.feed("@3E8;", t1))
        assertEquals(listOf(AxisCount('Y', 2, t1)), parser.feed("Y2;", t1 + 40))
        assertEquals(listOf(AxisCount('Z', 3, t1)), parser.feed("Z3;", t1 + 90))
        parser.feed("@7D0;", t1 + 1000)
        assertEquals(listOf(AxisCount('Z', 4, t1 + 1000)), parser.feed("Z4;", t1 + 1010))
    }

    @Test
    fun theResetAtEachLinkAndAtNoDataToConnectedClearsTheOffset() {
        val parser = AdapterParser()
        parser.feed("@3E8;", t1)
        parser.resetTime()
        assertNull(parser.timeBase.offsetMs)
        assertNull(parser.timeBase.adapterTimeMs)
        assertEquals(listOf(AxisCount('X', 1, t1 + 9000)), parser.feed("X1;", t1 + 9000))
        assertEquals(listOf(Timestamp(1000, t1 + 9100)), parser.feed("@3E8;", t1 + 9100))
        assertEquals(t1 + 8100, parser.timeBase.offsetMs)
        // A new link gets a new parser with a new time base.
        assertNull(AdapterParser().timeBase.offsetMs)
    }

    @Test
    fun theWallClockTimeOfARecord() {
        // wallNow − monotonicNow + t
        assertEquals(1_791_676_800_000L - 400L, TimeBase.wallClockMs(1_791_676_800_000L, 60_400L, 60_000L))
    }

    @Test
    fun theLargestTimeStampIs0xFFFFFFFFTimes1000() {
        assertEquals(0xFFFFFFFFL * 1000L, TimeBase.MAX_ADAPTER_TIME)
        assertEquals(250L, TimeBase.DRIFT_LIMIT_MS)
    }
}
