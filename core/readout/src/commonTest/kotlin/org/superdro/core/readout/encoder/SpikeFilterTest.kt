package org.superdro.core.readout.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The spike filter. */
class SpikeFilterTest {

    @Test
    fun theThresholdIsAQuarterOfTheResolutionAndAtLeastOne() {
        assertEquals(1270L, SpikeFilter.thresholdFor(5080.0))
        assertEquals(6350L, SpikeFilter.thresholdFor(25400.0))
        assertEquals(250L, SpikeFilter.thresholdFor(1000.0))
        assertEquals(1L, SpikeFilter.thresholdFor(3.0))
        assertEquals(1L, SpikeFilter.thresholdFor(7.9))
        assertEquals(1L, SpikeFilter.thresholdFor(1.0))
    }

    @Test
    fun theFirstSamplePasses() {
        assertEquals(1234L, SpikeFilter(1270L).filter(1234L))
    }

    @Test
    fun aOneCountChangeDoesNotChangeTheOutput() {
        val filter = SpikeFilter(1270L)
        val out = listOf(1000L, 1001L, 1002L, 1003L).map { count -> filter.filter(count) }
        assertEquals(listOf(1000L, 1000L, 1002L, 1002L), out)
        assertEquals(1002L, filter.filter(1001L))
        assertEquals(1002L, filter.filter(1003L))
    }

    @Test
    fun oneJumpLargerThanTheThresholdIsHeldForOneSampleAndTheNextSamplePasses() {
        val filter = SpikeFilter(1270L)
        listOf(1000L, 1001L, 1002L, 1003L).forEach { count -> filter.filter(count) }
        assertEquals(1002L, filter.filter(5000L), "the spike is held")
        assertTrue(filter.pending)
        assertEquals(1004L, filter.filter(1004L), "the next sample passes")
        assertFalse(filter.pending)
    }

    @Test
    fun aRealJumpShowsWhenTheNextSampleConfirmsIt() {
        val filter = SpikeFilter(1270L)
        filter.filter(1000L)
        assertEquals(1000L, filter.filter(5000L))
        assertEquals(5001L, filter.filter(5001L))
    }
}
