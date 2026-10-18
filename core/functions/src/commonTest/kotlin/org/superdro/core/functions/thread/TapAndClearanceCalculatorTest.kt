package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapAndClearanceCalculatorTest {

    @Test
    fun m3By0Point5Gives2Point5MmCutTapAnd2Point8MmFormTap() {
        val summary = TapAndClearanceCalculator.summary(
            majorDiameterUm = 3000.0,
            pitchUm = 500.0,
            includedAngleDeg = 60.0,
            metric = true,
            minorLimitsUm = 2459.0 to 2599.0,
            fullThreadDepthUm = 6000.0,
        )

        assertEquals(2512.8607, summary.cutTap.theoreticalDiameterUm, 0.01)
        assertEquals("2.5 mm", summary.cutTap.nearestDrills.closest.label)
        assertEquals(2500.0, summary.cutTap.nearestDrills.closest.diameterUm, 1e-6)
        assertEquals(76.98, summary.cutTap.closestActualEngagementPercent, 0.05)
        assertEquals(true, summary.cutTap.closestWithinMinorLimits)

        val formTap = assertNotNull(summary.formTap)
        assertEquals(2779.0, formTap.theoreticalDiameterUm, 0.01)
        assertEquals("2.8 mm", formTap.nearestDrills.closest.label)
        assertEquals(2800.0, formTap.nearestDrills.closest.diameterUm, 1e-6)

        assertEquals(3200.0, summary.clearance.closeFitUm, 1e-6)
        assertEquals(3400.0, summary.clearance.normalFitUm, 1e-6)
        assertEquals(3600.0, summary.clearance.looseFitUm, 1e-6)
        assertEquals(3300.0, summary.clearance.countersink90DiameterUm, 1e-6)
    }

    @Test
    fun m6By1Point0Gives5MmCutTapAnd5Point6MmFormTap() {
        val summary = TapAndClearanceCalculator.summary(
            majorDiameterUm = 6000.0,
            pitchUm = 1000.0,
            includedAngleDeg = 60.0,
            metric = true,
            minorLimitsUm = 4917.0 to 5153.0,
            fullThreadDepthUm = 12000.0,
        )

        assertEquals(5025.7214, summary.cutTap.theoreticalDiameterUm, 0.01)
        assertEquals("5.0 mm", summary.cutTap.nearestDrills.closest.label)
        assertEquals(5000.0, summary.cutTap.nearestDrills.closest.diameterUm, 1e-6)
        assertEquals(76.98, summary.cutTap.closestActualEngagementPercent, 0.05)
        assertEquals(true, summary.cutTap.closestWithinMinorLimits)

        val formTap = assertNotNull(summary.formTap)
        assertEquals(5558.0, formTap.theoreticalDiameterUm, 0.01)
        assertEquals("5.6 mm", formTap.nearestDrills.closest.label)

        assertEquals(6400.0, summary.clearance.closeFitUm, 1e-6)
        assertEquals(6600.0, summary.clearance.normalFitUm, 1e-6)
        assertEquals(7000.0, summary.clearance.looseFitUm, 1e-6)
        assertEquals(6300.0, summary.clearance.countersink90DiameterUm, 1e-6)
    }

    @Test
    fun m10By1Point5Gives8Point5MmCutTapAt77PercentAnd9Point3MmFormTap() {
        val summary = TapAndClearanceCalculator.summary(
            majorDiameterUm = 10000.0,
            pitchUm = 1500.0,
            includedAngleDeg = 60.0,
            metric = true,
            minorLimitsUm = 8376.2 to 8676.2,
            fullThreadDepthUm = 15000.0,
        )

        assertEquals(8538.582, summary.cutTap.theoreticalDiameterUm, 0.01)
        assertEquals("8.5 mm", summary.cutTap.nearestDrills.closest.label)
        assertEquals(8500.0, summary.cutTap.nearestDrills.closest.diameterUm, 1e-6)
        assertEquals(76.98, summary.cutTap.closestActualEngagementPercent, 0.05)
        assertEquals(true, summary.cutTap.closestWithinMinorLimits)

        val formTap = assertNotNull(summary.formTap)
        assertEquals(9337.0, formTap.theoreticalDiameterUm, 0.01)
        assertEquals("9.3 mm", formTap.nearestDrills.closest.label)
        assertEquals(9300.0, formTap.nearestDrills.closest.diameterUm, 1e-6)
        assertEquals(68.63, formTap.closestActualEngagementPercent, 0.05)

        assertEquals(10500.0, summary.clearance.closeFitUm, 1e-6)
        assertEquals(11000.0, summary.clearance.normalFitUm, 1e-6)
        assertEquals(12000.0, summary.clearance.looseFitUm, 1e-6)
        assertEquals(10300.0, summary.clearance.countersink90DiameterUm, 1e-6)

        val blind = assertNotNull(summary.blindHole)
        assertEquals(15000.0, blind.fullThreadDepthUm, 1e-6)
        assertEquals(21000.0, blind.bottomingMinDrillDepthUm, 1e-6)
        assertEquals(24000.0, blind.plugMinDrillDepthUm, 1e-6)
        assertEquals(30750.0, blind.taperMinDrillDepthUm, 1e-6)
    }

    @Test
    fun quarter20UncGivesNumber7CutTapAndNumber1FormTap() {
        val summary = TapAndClearanceCalculator.summary(
            majorDiameterUm = 6350.0,
            pitchUm = 1270.0,
            includedAngleDeg = 60.0,
            metric = false,
            minorLimitsUm = (0.1959 * 25400.0) to (0.2074 * 25400.0),
        )

        assertEquals(5112.666, summary.cutTap.theoreticalDiameterUm, 0.01)
        assertEquals("#7", summary.cutTap.nearestDrills.closest.label)
        assertEquals(0.2010 * 25400.0, summary.cutTap.nearestDrills.closest.diameterUm, 1e-6)
        assertEquals(75.44, summary.cutTap.closestActualEngagementPercent, 0.05)
        assertEquals(true, summary.cutTap.closestWithinMinorLimits)

        val formTap = assertNotNull(summary.formTap)
        assertEquals(5788.66, formTap.theoreticalDiameterUm, 0.01)
        assertEquals("#1", formTap.nearestDrills.closest.label)
        assertEquals(0.2280 * 25400.0, formTap.nearestDrills.closest.diameterUm, 1e-6)

        assertEquals(0.2570 * 25400.0, summary.clearance.closeFitUm, 1e-6)
        assertEquals(0.2660 * 25400.0, summary.clearance.normalFitUm, 1e-6)
        assertEquals(0.28125 * 25400.0, summary.clearance.looseFitUm, 1e-6)
    }

    @Test
    fun number10By32UnfGivesNumber21CutTapDrill() {
        val majorUm = 0.190 * 25400.0
        val pitchUm = 25400.0 / 32.0
        val cutTap = TapAndClearanceCalculator.cutTapDrill(
            majorDiameterUm = majorUm,
            pitchUm = pitchUm,
            includedAngleDeg = 60.0,
            engagementPercent = 75.0,
            metric = false,
            minorLimitsUm = (0.1560 * 25400.0) to (0.1640 * 25400.0),
        )

        assertEquals("#21", cutTap.nearestDrills.closest.label)
        assertEquals(0.1590 * 25400.0, cutTap.nearestDrills.closest.diameterUm, 1e-6)
        assertEquals(76.36, cutTap.closestActualEngagementPercent, 0.05)
        assertEquals(true, cutTap.closestWithinMinorLimits)
    }

    @Test
    fun gQuarter19BsppUses55DegreeWhitworthFactorAndOmitsFormTap() {
        val majorUm = 13157.0
        val pitchUm = 25400.0 / 19.0
        val summary = TapAndClearanceCalculator.summary(
            majorDiameterUm = majorUm,
            pitchUm = pitchUm,
            includedAngleDeg = 55.0,
            metric = true,
            minorLimitsUm = 11445.0 to 11890.0,
        )

        assertEquals(11872.98, summary.cutTap.theoreticalDiameterUm, 0.05)
        assertEquals("11.8 mm", assertNotNull(summary.cutTap.nearestDrills.smallerOrEqual).label)
        assertEquals("11.9 mm", summary.cutTap.nearestDrills.closest.label)
        assertTrue(summary.cutTap.closestActualEngagementPercent in 70.0..80.0)
        assertNull(summary.formTap)
    }
}
