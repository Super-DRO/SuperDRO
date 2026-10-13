package org.superdro.core.functions.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.functions.TestValues.assertDegrees
import org.superdro.core.functions.TestValues.assertMm
import org.superdro.core.functions.TestValues.mm
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType

/**
 * Measure distance and angle, and the caliper copy.
 */
class MeasureTest {

    @Test
    fun thirtyAndFortyGive50MmAnd5313And3687Degrees() {
        val result = MeasureDistanceAngle.measure(mm(30.0), mm(40.0))
        assertMm(50.000, result.distanceUm)
        assertDegrees(53.13, result.lineAngleArcSec, decimals = 2)
        assertDegrees(36.87, result.complementArcSec, decimals = 2)
        assertDegrees(53.13, result.directionArcSec, decimals = 2)
    }

    @Test
    fun minusThirtyAndFortyGiveTheLineAngleAndTheDirection() {
        val result = MeasureDistanceAngle.measure(mm(-30.0), mm(40.0))
        assertMm(50.000, result.distanceUm)
        assertDegrees(-53.13, result.lineAngleArcSec, decimals = 2)
        assertDegrees(126.87, result.directionArcSec, decimals = 2)
        assertDegrees(306.87, MeasureDistanceAngle.measure(mm(30.0), mm(-40.0)).directionArcSec, decimals = 2)
    }

    @Test
    fun theZeroCasesOfTheLineAngle() {
        val origin = MeasureDistanceAngle.measure(0.0, 0.0)
        assertEquals(0.0, origin.distanceUm)
        assertEquals(0.0, origin.lineAngleArcSec)
        assertEquals(0.0, origin.complementArcSec)
        assertEquals(0.0, origin.directionArcSec)
        val onB = MeasureDistanceAngle.measure(0.0, mm(-5.0))
        assertDegrees(90.0, onB.lineAngleArcSec)
        assertDegrees(0.0, onB.complementArcSec)
        assertDegrees(270.0, onB.directionArcSec)
        val onA = MeasureDistanceAngle.measure(mm(-5.0), 0.0)
        assertDegrees(0.0, onA.lineAngleArcSec)
        assertDegrees(90.0, onA.complementArcSec)
        assertDegrees(180.0, onA.directionArcSec)
    }

    @Test
    fun aMillUsesXAndYAndALatheUsesZAndX() {
        assertEquals(AxisId.X to AxisId.Y, MeasureDistanceAngle.axes(MachineType.VerticalMill))
        assertEquals(AxisId.X to AxisId.Y, MeasureDistanceAngle.axes(MachineType.HorizontalMill))
        assertEquals(AxisId.Z to AxisId.X, MeasureDistanceAngle.axes(MachineType.Lathe))
    }

    @Test
    fun aCaliperValueOf25MmGivesX12500InRadiusMode() {
        val copy = CaliperMeasure.copyToX(mm(25.0), xDiameterMode = false, xIncremental = false)
        assertMm(12.500, copy.valueUm)
        assertFalse(copy.incremental)
        val diameter = CaliperMeasure.copyToX(mm(25.0), xDiameterMode = true, xIncremental = true)
        assertMm(25.000, diameter.valueUm)
        assertTrue(diameter.incremental)
    }

    @Test
    fun theButtonShowsWithTheSwitchAndTheCaliper() {
        assertTrue(CaliperMeasure.buttonVisible(showDimensionMeasure = true, caliperEnabled = true))
        assertFalse(CaliperMeasure.buttonVisible(showDimensionMeasure = true, caliperEnabled = false))
        assertFalse(CaliperMeasure.buttonVisible(showDimensionMeasure = false, caliperEnabled = true))
    }
}
