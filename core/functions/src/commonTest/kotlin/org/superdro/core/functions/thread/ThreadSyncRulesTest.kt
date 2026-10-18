package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadSyncRulesTest {

    private val leadscrew8TpiUm = 25_400.0 / 8.0

    private fun tpiToUm(tpi: Double): Double = 25_400.0 / tpi

    @Test
    fun dialRuleOn8TpiLeadscrewMatchesStandardImperialRules() {
        assertEquals(
            ThreadDialRule.AnyPoint,
            ThreadSyncRules.dialRule(
                leadUm = tpiToUm(16.0),
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
        assertEquals(
            ThreadDialRule.AnyLine,
            ThreadSyncRules.dialRule(
                leadUm = tpiToUm(20.0),
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
        assertEquals(
            ThreadDialRule.AnyNumberedLine,
            ThreadSyncRules.dialRule(
                leadUm = tpiToUm(13.0),
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
        assertEquals(
            ThreadDialRule.OppositeNumberedLines,
            ThreadSyncRules.dialRule(
                leadUm = tpiToUm(11.5),
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
        assertEquals(
            ThreadDialRule.SameLineOnly,
            ThreadSyncRules.dialRule(
                leadUm = tpiToUm(5.25),
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
        assertEquals(
            ThreadDialRule.SameLineOnly,
            ThreadSyncRules.dialRule(
                leadUm = tpiToUm(4.75),
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
    }

    @Test
    fun dialRuleKeepsHalfNutClosedForMetricThreadOnImperialLeadscrew() {
        assertEquals(
            ThreadDialRule.KeepHalfNutClosed,
            ThreadSyncRules.dialRule(
                leadUm = 1_500.0,
                threadMetric = true,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
    }

    @Test
    fun dialRuleHandlesMetricLeadscrewAndInvalidInputs() {
        assertEquals(
            ThreadDialRule.AnyPoint,
            ThreadSyncRules.dialRule(
                leadUm = 1_500.0,
                threadMetric = true,
                leadscrewPitchUm = 6_000.0,
                leadscrewMetric = true,
            ),
        )
        assertEquals(
            ThreadDialRule.KeepHalfNutClosed,
            ThreadSyncRules.dialRule(
                leadUm = 1_750.0,
                threadMetric = true,
                leadscrewPitchUm = 6_000.0,
                leadscrewMetric = true,
            ),
        )
        assertEquals(
            ThreadDialRule.KeepHalfNutClosed,
            ThreadSyncRules.dialRule(
                leadUm = 1_500.0,
                threadMetric = true,
                leadscrewPitchUm = null,
                leadscrewMetric = true,
            ),
        )
        assertEquals(
            ThreadDialRule.KeepHalfNutClosed,
            ThreadSyncRules.dialRule(
                leadUm = 0.0,
                threadMetric = false,
                leadscrewPitchUm = leadscrew8TpiUm,
                leadscrewMetric = false,
            ),
        )
    }

    @Test
    fun safeSpeedUsesStoppingZoneAndReactionWindow() {
        // External M10 × 1.5 with 4.5 mm relief: stoppingZone = 4500 µm, maxRpm = floor(60 * 4500 / (1500 * 1.0)) = 180.
        val external = ThreadSyncRules.safeSpeed(
            leadUm = 1_500.0,
            pitchUm = 1_500.0,
            exitReliefUm = 4_500.0,
            internal = false,
            currentRpm = 120.0,
        )
        assertEquals(4_500.0, external.stoppingZoneUm, 1e-6)
        assertEquals(180, external.maxRpm)
        assertEquals(3_000.0, external.liveCarriageSpeedUmPerSec!!, 1e-6)
        assertEquals(1.5, external.liveReactionWindowSec!!, 1e-6)
        assertFalse(external.reactionTooFast)

        // Internal thread uses 1.5 s target reaction and flags live reaction window < 0.75 s.
        val internalFast = ThreadSyncRules.safeSpeed(
            leadUm = 1_500.0,
            pitchUm = 1_500.0,
            exitReliefUm = 1_000.0,
            internal = true,
            currentRpm = 180.0,
        )
        assertEquals(2_250.0, internalFast.stoppingZoneUm, 1e-6)
        assertEquals(60, internalFast.maxRpm)
        assertEquals(4_500.0, internalFast.liveCarriageSpeedUmPerSec!!, 1e-6)
        assertEquals(0.5, internalFast.liveReactionWindowSec!!, 1e-6)
        assertTrue(internalFast.reactionTooFast)

        val noTach = ThreadSyncRules.safeSpeed(
            leadUm = 1_500.0,
            pitchUm = 1_500.0,
            exitReliefUm = null,
            internal = false,
            currentRpm = null,
        )
        assertNull(noTach.liveCarriageSpeedUmPerSec)
        assertNull(noTach.liveReactionWindowSec)
        assertFalse(noTach.reactionTooFast)
    }

    @Test
    fun pitchCheckComparesMeasuredFeedAgainstTargetLeadWithinThreePercent() {
        val match = ThreadSyncRules.pitchCheck(measuredFeedPerRevUm = 1_530.0, targetLeadUm = 1_500.0)
        assertIs<PitchCheckResult.Match>(match)
        assertEquals(0.02, match.relativeError, 1e-9)

        val mismatch = ThreadSyncRules.pitchCheck(measuredFeedPerRevUm = 1_600.0, targetLeadUm = 1_500.0)
        assertIs<PitchCheckResult.Mismatch>(mismatch)
        assertEquals(100.0 / 1_500.0, mismatch.relativeError, 1e-9)

        assertNull(ThreadSyncRules.pitchCheck(measuredFeedPerRevUm = null, targetLeadUm = 1_500.0))
        assertNull(ThreadSyncRules.pitchCheck(measuredFeedPerRevUm = -10.0, targetLeadUm = 1_500.0))
        assertNull(ThreadSyncRules.pitchCheck(measuredFeedPerRevUm = 1_500.0, targetLeadUm = 0.0))
    }

    @Test
    fun internalBarRecommendationSelectsLargestFittingHolderAndChecksOverhang() {
        val tooSmall = ThreadSyncRules.internalBarRecommendation(minorDiameterUm = 2_000.0, lengthUm = 5_000.0)
        assertNull(tooSmall.holder)
        assertEquals(0.0, tooSmall.maxSafeDepthUm, 1e-6)
        assertFalse(tooSmall.deflectionRisk)

        val micro = ThreadSyncRules.internalBarRecommendation(minorDiameterUm = 4_200.0, lengthUm = 12_000.0)
        assertEquals(InternalBarHolder.CarbideMicro, micro.holder)
        assertEquals(14_000.0, micro.maxSafeDepthUm, 1e-6)
        assertFalse(micro.deflectionRisk)

        // M10 × 1.5 internal minor bore is ~8376 µm -> fits 08IR (minBore 8000 µm, shank 8000 µm, maxSafeDepth 28000 µm).
        val m10Safe = ThreadSyncRules.internalBarRecommendation(minorDiameterUm = 8_376.0, lengthUm = 25_000.0)
        assertEquals(InternalBarHolder.Ir08, m10Safe.holder)
        assertEquals(28_000.0, m10Safe.maxSafeDepthUm, 1e-6)
        assertFalse(m10Safe.deflectionRisk)

        val m10Deep = ThreadSyncRules.internalBarRecommendation(minorDiameterUm = 8_376.0, lengthUm = 30_000.0)
        assertEquals(InternalBarHolder.Ir08, m10Deep.holder)
        assertTrue(m10Deep.deflectionRisk)

        val large = ThreadSyncRules.internalBarRecommendation(minorDiameterUm = 25_000.0, lengthUm = null)
        assertEquals(InternalBarHolder.Ir22, large.holder)
        assertEquals(56_000.0, large.maxSafeDepthUm, 1e-6)
        assertFalse(large.deflectionRisk)
    }

    @Test
    fun leadAngleAndAnvilTiltThresholdMatchHelixFormula() {
        // M20 × 1.5 single-start: lead = 1500 µm, d2 = 19025.72 µm -> λ = 5175.4" (1.44°).
        val m20Angle = ThreadSyncRules.leadAngleArcSec(leadUm = 1_500.0, pitchDiameterUm = 19_025.72)
        assertEquals(5_175.4, m20Angle, 1.0)
        assertFalse(ThreadSyncRules.needsAnvilTilt(m20Angle))

        // Two-start Tr 16 × 4: lead = 8000 µm, d2 = 14000 µm -> λ = 37112.06" (10.31°).
        val multiStartAngle = ThreadSyncRules.leadAngleArcSec(leadUm = 8_000.0, pitchDiameterUm = 14_000.0)
        assertEquals(37_112.06, multiStartAngle, 0.01)
        assertTrue(ThreadSyncRules.needsAnvilTilt(multiStartAngle))

        assertEquals(0.0, ThreadSyncRules.leadAngleArcSec(leadUm = 0.0, pitchDiameterUm = 10_000.0))
    }
}
