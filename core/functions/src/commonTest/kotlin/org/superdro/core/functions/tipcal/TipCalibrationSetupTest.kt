package org.superdro.core.functions.tipcal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType

/** The readiness check, the ring gauge entry, and the clock order. */
class TipCalibrationSetupTest {

    private val probe = ProbeProfile(id = "probe-1", type = ProbeType.TouchProbe, port = 'p', stylusDiameterUm = 2000.0)

    @Test
    fun theChecksOccurInTheExpectedOrder() {
        assertEquals(TipCalibrationReadiness.ProfileGone, TipCalibrationSetup.readiness(null, false, false, false))
        assertEquals(
            TipCalibrationReadiness.NotCalibratable,
            TipCalibrationSetup.readiness(probe.copy(type = ProbeType.Camera), false, false, false),
        )
        assertEquals(TipCalibrationReadiness.NotConnected, TipCalibrationSetup.readiness(probe, false, false, false))
        assertEquals(TipCalibrationReadiness.NoXyAxes, TipCalibrationSetup.readiness(probe, true, true, false))
        assertEquals(TipCalibrationReadiness.NoXyAxes, TipCalibrationSetup.readiness(probe, true, false, true))
        assertEquals(TipCalibrationReadiness.Ready, TipCalibrationSetup.readiness(probe, true, true, true))
    }

    @Test
    fun onlyATouchProbeOrA2dEdgeFinderWithATriggerSourceIsCalibratable() {
        assertEquals(TipCalibrationReadiness.Ready, TipCalibrationSetup.readiness(probe.copy(type = ProbeType.EdgeFinder2D), true, true, true))
        assertEquals(
            TipCalibrationReadiness.NotCalibratable,
            TipCalibrationSetup.readiness(probe.copy(type = ProbeType.ToolHeightSetter), true, true, true),
        )
        assertEquals(TipCalibrationReadiness.NotCalibratable, TipCalibrationSetup.readiness(probe.copy(port = null), true, true, true))
    }

    @Test
    fun theGaugeEntryChangesMmAndInchToMicrometers() {
        assertEquals(25000.0, TipCalibrationSetup.gaugeDiameterUm(25.0, metric = true))
        assertEquals(25400.0, TipCalibrationSetup.gaugeDiameterUm(1.0, metric = false))
    }

    @Test
    fun aGaugeThatIsNotLargerThanTheNominalTipIsNotUsable() {
        assertTrue(TipCalibrationSetup.isGaugeUsable(25000.0, 2000.0))
        assertFalse(TipCalibrationSetup.isGaugeUsable(2000.0, 2000.0))
        assertFalse(TipCalibrationSetup.isGaugeUsable(1500.0, 2000.0))
        assertFalse(TipCalibrationSetup.isGaugeUsable(0.0, 0.0))
        assertFalse(TipCalibrationSetup.isGaugeUsable(-5.0, 0.0))
        assertFalse(TipCalibrationSetup.isGaugeUsable(Double.NaN, 0.0))
    }

    @Test
    fun theClockOrderIsNineThreeTwelveSix() {
        assertEquals(
            listOf(ClockPosition.Nine, ClockPosition.Three, ClockPosition.Twelve, ClockPosition.Six),
            ClockPosition.procedureOrder,
        )
        assertEquals(listOf(9, 3, 12, 6), ClockPosition.procedureOrder.map { clock -> clock.hour })
    }

    @Test
    fun theRecenterMarkerUsesTheMeanXOfNineAndThreeOClock() {
        val marker = RecenterMarker.of(x9Um = -11506.2, x3Um = 11507.0)
        assertEquals(0.4, marker.centerXUm, 1e-9)
        assertEquals(23013.2, marker.spanUm, 1e-9)
        assertEquals(11506.6, marker.rangeUm, 1e-9)
        assertEquals(2876.65, marker.hashMarkUm, 1e-9)
        assertEquals(99.6, marker.markerOffsetUm(100.0), 1e-9)
        assertEquals(11506.6, marker.markerOffsetUm(50000.0), 1e-9)
        assertEquals(-11506.6, marker.markerOffsetUm(-50000.0), 1e-9)
    }
}
