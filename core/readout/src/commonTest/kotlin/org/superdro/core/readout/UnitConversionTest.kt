package org.superdro.core.readout

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitConversionTest {

    @Test
    fun oneInchIs25400Um() {
        assertEquals(1.0, UnitConversion.umToInch(25400.0))
        assertEquals(25400.0, UnitConversion.inchToUm(1.0))
        assertEquals(44450.0, UnitConversion.inchToUm(1.75))
    }

    @Test
    fun oneMillimeterIs1000Um() {
        assertEquals(12.7, UnitConversion.umToMm(12700.0))
        assertEquals(-3175.0, UnitConversion.mmToUm(-3.175))
    }

    @Test
    fun oneRevolutionIs1296000ArcSeconds() {
        assertEquals(360.0, UnitConversion.arcSecToDegrees(1_296_000.0))
        assertEquals(270000.0, UnitConversion.degreesToArcSec(75.0))
    }
}
