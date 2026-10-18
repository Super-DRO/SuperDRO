package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadInspectionTest {

    @Test
    fun bestWireDiameterAndWireRangeMatchFlankAngles() {
        val m10Best = ThreadInspection.bestWireDiameterUm(pitchUm = 1500.0, includedAngleDeg = 60.0)
        assertEquals(866.0254, m10Best, 0.001)
        val m10Range = ThreadInspection.wireRangeUm(pitchUm = 1500.0, includedAngleDeg = 60.0)
        assertTrue(m10Best in m10Range)
        assertFalse(200.0 in m10Range)
        assertFalse(2000.0 in m10Range)

        val bsppBest = ThreadInspection.bestWireDiameterUm(pitchUm = 25400.0 / 19.0, includedAngleDeg = 55.0)
        assertEquals(0.56369 * (25400.0 / 19.0), bsppBest, 0.05)

        val trBest = ThreadInspection.bestWireDiameterUm(pitchUm = 4000.0, includedAngleDeg = 30.0)
        assertEquals(0.517638 * 4000.0, trBest, 0.01)

        val acmeBest = ThreadInspection.bestWireDiameterUm(pitchUm = 2540.0, includedAngleDeg = 29.0)
        assertEquals(0.516450 * 2540.0, acmeBest, 0.05)
    }

    @Test
    fun threeWireAppliesBuckinghamLeadAngleAndCustomWireAndRemainingDepth() {
        // Tr 16 × 4 with 1 start vs 2 starts: 2-start has a larger lead angle and larger M over wires.
        val singleStart = ThreadInspection.threeWire(
            pitchUm = 4000.0,
            includedAngleDeg = 30.0,
            starts = 1,
            targetPitchDiameterUm = 13905.0,
            limits = ClassLimits(13640.0, 13905.0, 11105.0, 15905.0),
        )
        val twoStart = ThreadInspection.threeWire(
            pitchUm = 4000.0,
            includedAngleDeg = 30.0,
            starts = 2,
            targetPitchDiameterUm = 13905.0,
            limits = ClassLimits(13640.0, 13905.0, 11105.0, 15905.0),
        )
        assertTrue(singleStart.wireValid)
        assertTrue(twoStart.measurementUm > singleStart.measurementUm + 50.0)

        // With custom wire and measured micrometer reading 120 µm above target M -> remaining radial depth is 60 µm.
        val measured = ThreadInspection.threeWire(
            pitchUm = 4000.0,
            includedAngleDeg = 30.0,
            starts = 2,
            targetPitchDiameterUm = 13905.0,
            limits = ClassLimits(13640.0, 13905.0, 11105.0, 15905.0),
            customWireDiameterUm = 2100.0,
            measuredOverWiresUm = twoStart.measurementUm + 300.0,
        )
        assertEquals(2100.0, measured.wireDiameterUm, 1e-6)
        assertTrue(measured.wireValid)
        val actualPitch = assertNotNull(measured.actualPitchDiameterUm)
        val remainingRadial = assertNotNull(measured.remainingRadialDepthUm)
        assertEquals((actualPitch - 13905.0) / 2.0, remainingRadial, 1e-6)

        // Invalid tiny wire flags wireValid = false.
        val invalidWire = ThreadInspection.threeWire(
            pitchUm = 1500.0,
            includedAngleDeg = 60.0,
            starts = 1,
            targetPitchDiameterUm = 8993.72,
            limits = null,
            customWireDiameterUm = 200.0,
        )
        assertFalse(invalidWire.wireValid)
    }

    @Test
    fun threeWireAppliesTaperFactorForTaperedPipeThreads() {
        val parallel = ThreadInspection.threeWire(
            pitchUm = 25400.0 / 14.0,
            includedAngleDeg = 60.0,
            starts = 1,
            targetPitchDiameterUm = 0.77843 * 25400.0,
            limits = null,
            taperRatio = 0.0,
        )
        val tapered = ThreadInspection.threeWire(
            pitchUm = 25400.0 / 14.0,
            includedAngleDeg = 60.0,
            starts = 1,
            targetPitchDiameterUm = 0.77843 * 25400.0,
            limits = null,
            taperRatio = 0.0625,
        )
        assertTrue(tapered.measurementUm > parallel.measurementUm)
    }

    @Test
    fun internalGaugeLimitsGiveGoAndNoGoDiameters() {
        val limits = ClassLimits(
            pitchMinUm = 10863.0,
            pitchMaxUm = 11063.0,
            crestMinUm = 10106.0,
            crestMaxUm = 10441.0,
        )
        val gauges = assertNotNull(ThreadInspection.internalGaugeLimits(limits))
        assertEquals(10106.0, gauges.minorGoUm, 1e-6)
        assertEquals(10441.0, gauges.minorNoGoUm, 1e-6)
        assertEquals(10863.0, gauges.pitchGoUm, 1e-6)
        assertEquals(11063.0, gauges.pitchNoGoUm, 1e-6)
        assertNull(ThreadInspection.internalGaugeLimits(null))
    }
}
