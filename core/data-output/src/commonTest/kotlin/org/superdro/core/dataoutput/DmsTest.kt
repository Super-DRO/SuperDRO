package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals

/** The DMS text with the carry of the seconds. */
class DmsTest {

    @Test
    fun theDmsTextHasDegreesMinutesAndSeconds() {
        assertEquals("45d30m15.5000s", OutputUnits.dms(163815.5, 4))
        assertEquals("-45d30m15.5000s", OutputUnits.dms(-163815.5, 4))
        assertEquals("0d0m0.00s", OutputUnits.dms(0.0, 2))
        assertEquals("360d0m0s", OutputUnits.dms(1_296_000.0, 0))
    }

    @Test
    fun theSecondsCarryIntoTheMinutesAndTheDegrees() {
        // The result is never 0d59m60.00s for this value.
        assertEquals("1d0m0.00s", OutputUnits.dms(3599.999, 2))
        assertEquals("0d2m0.0s", OutputUnits.dms(119.96, 1))
        assertEquals("-1d0m0.00s", OutputUnits.dms(-3599.999, 2))
        assertEquals("0d59m59.99s", OutputUnits.dms(3599.99, 2))
    }
}
