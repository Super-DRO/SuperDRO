package org.superdro.core.functions.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The AUTO direction rule. */
class AxisHistoryTest {

    private fun history(vararg entries: Pair<Long, Double>) =
        AxisHistory(entries.map { (time, position) -> PositionSample(time, position) })

    @Test
    fun theFirstDifferenceOf5UmOrMoreGivesTheSign() {
        val positive = history(9000L to 0.0, 9200L to 2.0, 9400L to 4.0, 9600L to 6.0, 9800L to 8.0, 10000L to 10.0)
        assertEquals(1, positive.direction(10000L))
        val negative = history(9600L to 10.0, 9800L to 7.0, 10000L to 4.0)
        assertEquals(-1, negative.direction(10000L))
    }

    @Test
    fun lessThan5UmInTheWindowIsNoMotion() {
        assertEquals(0, history(9500L to 0.0, 9800L to 2.0, 10000L to 4.9).direction(10000L))
        assertEquals(1, history(9500L to 0.0, 9800L to 2.0, 10000L to 5.0).direction(10000L))
    }

    @Test
    fun aMotionOlderThan1000MsIsNoMotion() {
        // The 10 µm change is at 8800 ms, out of the window 9000 to 10000 ms.
        val old = history(8700L to 0.0, 8800L to 10.0, 9100L to 10.0, 9500L to 10.0, 9900L to 10.0, 10000L to 10.0)
        assertEquals(0, old.direction(10000L))
    }

    @Test
    fun aGapOfMoreThan500MsStopsTheSearch() {
        assertEquals(0, history(9000L to 0.0, 9600L to 10.0, 10000L to 10.0).direction(10000L))
        assertEquals(1, history(9000L to 0.0, 9400L to 3.0, 9800L to 10.0, 10000L to 10.0).direction(10000L))
    }

    @Test
    fun thePositionAtTheTriggerTimeIsInterpolated() {
        val axis = history(1000L to 0.0, 1100L to 100.0)
        assertEquals(50.0, axis.positionAt(1050L))
        assertEquals(100.0, axis.positionAt(2000L))
        assertNull(axis.positionAt(900L))
        assertEquals(0.0, history(1000L to 0.0, 1600L to 100.0).positionAt(1300L))
        // An entry after the trigger time is not a motion before the trigger.
        assertEquals(0, history(9900L to 0.0, 10000L to 0.0, 10050L to 20.0).direction(10000L))
    }
}
