package org.superdro.core.functions.tipcal

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run of the tip calibration: the steps, the result, the checks, and "Retry". */
class TipCalibrationRunTest {

    private fun position(clock: ClockPosition, x: Double, y: Double, sigma: Double = 0.0): CapturedPosition =
        CapturedPosition(
            clock = clock,
            touches = List(3) { index -> Touch(x, y, 0.0, 0.0, index.toLong()) },
            xUm = x,
            yUm = y,
            sigmaXUm = sigma,
            sigmaYUm = sigma,
            spreadUm = 0.0,
        )

    /** A run with the gauge [gauge] and the four positions on an ellipse about (cx, cy). */
    private fun completeRun(
        gauge: Double = 25000.0,
        cx: Double = 0.4,
        cy: Double = 0.1,
        rx: Double = 11506.3,
        ry: Double = rx,
        sigma: Double = 0.0,
    ): TipCalibrationRun = TipCalibrationRun(
        profileId = "probe-7",
        started = "2026-10-11 13:52:10",
        gaugeUm = gauge,
        positions = listOf(
            position(ClockPosition.Nine, cx - rx, cy, sigma),
            position(ClockPosition.Three, cx + rx, cy, sigma),
            position(ClockPosition.Twelve, cx, cy + ry, sigma),
            position(ClockPosition.Six, cx, cy - ry, sigma),
        ),
    )

    @Test
    fun theNextPositionIsTheFirstPositionWithNoData() {
        var run = TipCalibrationRun(profileId = "probe-7", started = "2026-10-11 13:52:10", gaugeUm = 25000.0)
        assertEquals(ClockPosition.Nine, run.nextPosition)
        assertEquals(TouchPrompt.FirstPosition, run.prompt)
        run = run.withPosition(position(ClockPosition.Nine, -11506.2, 0.1))
        assertEquals(ClockPosition.Three, run.nextPosition)
        assertEquals(TouchPrompt.NextPosition, run.prompt)
        assertEquals(false, run.needsRecenter)
        assertNull(run.recenterMarker)
        run = run.withPosition(position(ClockPosition.Three, 11507.0, 0.1))
        assertEquals(ClockPosition.Twelve, run.nextPosition)
        assertTrue(run.needsRecenter)
        assertEquals(0.4, assertNotNull(run.recenterMarker).centerXUm, 1e-9)
        // A position that has data again replaces the old data.
        run = run.withPosition(position(ClockPosition.Nine, -11506.0, 0.1))
        assertEquals(2, run.positions.size)
        run = run.withPosition(position(ClockPosition.Twelve, 0.4, 11506.4))
        assertEquals(ClockPosition.Six, run.nextPosition)
        assertEquals(false, run.needsRecenter)
        run = run.withPosition(position(ClockPosition.Six, 0.4, -11506.2))
        assertNull(run.nextPosition)
        assertTrue(run.hasAllPositions)
        assertEquals(12, run.touchCount)
    }

    @Test
    fun aGaugeThatIsTooSmallFailsAndRetryAsksForTheGaugeAgain() {
        val run = TipCalibrationRun(profileId = "probe-7", started = "2026-10-11 13:52:10")
        val failed = assertIs<TipCalibrationStep.Failed>(run.withGauge(2000.0, nominalTipUm = 2000.0))
        assertEquals(TipCalibrationError.GaugeUnusable, failed.error)
        assertEquals(RetryAction.AskGaugeAgain, failed.error.retry)
        val accepted = assertIs<TipCalibrationStep.Accepted>(run.withGauge(25000.0, nominalTipUm = 2000.0))
        assertEquals(25000.0, accepted.run.gaugeUm)
        assertNull(accepted.run.retry(TipCalibrationError.GaugeUnusable).gaugeUm)
    }

    @Test
    fun aScatteredPositionFailsAndRetryTakesTheSamePositionAgain() {
        val run = TipCalibrationRun(profileId = "probe-7", started = "2026-10-11 13:52:10", gaugeUm = 25000.0)
        var collection = TouchCollection(ClockPosition.Nine)
        listOf(0.0, 30.0, 1.0).forEach { x ->
            collection = (collection.add(Touch(x, 0.0, 0.0, 0.0, 0L)) as TouchResult.Added).touches
        }
        val failed = assertIs<TipCalibrationStep.Failed>(run.continueWith(collection, spreadLimitUm = 20.0))
        assertEquals(TipCalibrationError.PositionScattered, failed.error)
        assertEquals(run, run.retry(failed.error))
        assertEquals(ClockPosition.Nine, run.retry(failed.error).nextPosition)
    }

    @Test
    fun fitAndResultErrorsClearThePositionsAndKeepTheGauge() {
        val run = completeRun()
        listOf(TipCalibrationError.FitFailed, TipCalibrationError.ResultRefused).forEach { error ->
            assertEquals(RetryAction.ClearPositionsKeepGauge, error.retry)
            val next = run.retry(error)
            assertEquals(25000.0, next.gaugeUm)
            assertTrue(next.positions.isEmpty())
            assertEquals(ClockPosition.Nine, next.nextPosition)
        }
    }

    @Test
    fun theWorkedExampleGivesTheTipDiameter() {
        val outcome = assertIs<TipCalibrationOutcome.Ready>(completeRun().evaluate(nominalTipUm = 2000.0))
        assertEquals(ResultCheckKind.Normal, outcome.check)
        val result = outcome.result
        assertEquals(1987.4, result.effectiveDiameterUm, 1e-9)
        assertEquals(993.7, result.effectiveRadiusUm, 1e-9)
        assertEquals(0.4, result.centerXUm, 1e-9)
        assertEquals(0.1, result.centerYUm, 1e-9)
        assertEquals(11506.3, result.radiusUm, 1e-9)
        assertEquals(12, result.touchCount)
        assertEquals(1987.4, assertNotNull(result.diameterXUm), 1e-6)
        assertEquals(1987.4, assertNotNull(result.diameterYUm), 1e-6)
        assertNull(result.asymmetry)
    }

    @Test
    fun theRepeatabilityUsesTheSigmasOfTheFourPositions() {
        val result = assertIs<TipCalibrationOutcome.Ready>(completeRun(sigma = 1.0).evaluate(2000.0)).result
        // sqrt(Σ (1² + 1²) / 4) = sqrt(2).
        assertEquals(sqrt(2.0), result.repeatabilityUm, 1e-12)
        assertEquals(0.0, TipCalibrationRun.repeatabilityUm(emptyList()))
    }

    @Test
    fun differentXAndYDiametersGiveTheAsymmetryNote() {
        val result = assertIs<TipCalibrationOutcome.Ready>(
            completeRun(rx = 11500.0, ry = 11510.0, sigma = 1.0).evaluate(2000.0),
        ).result
        assertEquals(2000.0, assertNotNull(result.diameterXUm), 1e-6)
        assertEquals(1980.0, assertNotNull(result.diameterYUm), 1e-6)
        // |2000 − 1980| = 20 > max(10, 3 × sqrt(2)).
        val note = assertNotNull(result.asymmetry)
        assertEquals(10.0, note.halfDifferenceUm, 1e-6)
    }

    @Test
    fun aSmallOrAMaskedAsymmetryGivesNoNote() {
        // |dX − dY| = 8 µm is not more than 10 µm.
        val small = assertIs<TipCalibrationOutcome.Ready>(completeRun(rx = 11500.0, ry = 11504.0).evaluate(2000.0)).result
        assertNull(small.asymmetry)
        // |dX − dY| = 12 µm is not more than 3 × repeatability = 3 × 5 = 15 µm.
        val masked = TipCalibrationResult(
            gaugeUm = 25000.0, centerXUm = 0.0, centerYUm = 0.0, radiusUm = 11500.0, residualUm = 0.0,
            repeatabilityUm = 5.0, diameterXUm = 2006.0, diameterYUm = 1994.0, touchCount = 12,
        )
        assertNull(masked.asymmetry)
        assertNotNull(masked.copy(repeatabilityUm = 3.0).asymmetry)
    }

    @Test
    fun theResultChecksOccurInTheExpectedOrder() {
        fun check(gauge: Double, radius: Double, nominal: Double): ResultCheckKind = TipResultChecks.check(
            TipCalibrationResult(gauge, 0.0, 0.0, radius, 0.0, 0.0, null, null, 12),
            nominal,
        )
        // G/2 − R <= 0 comes before the size check, also when d < 100 µm.
        assertEquals(ResultCheckKind.TipHasNoSize, check(25000.0, 12500.0, 2000.0))
        assertEquals(ResultCheckKind.TipHasNoSize, check(25000.0, 12510.0, 2000.0))
        assertEquals(ResultCheckKind.UnlikelyTipSize, check(25000.0, 12460.0, 2000.0))
        assertEquals(ResultCheckKind.UnlikelyTipSize, check(60000.0, 17000.0, 26000.0))
        assertEquals(ResultCheckKind.NominalMismatch, check(25000.0, 11506.3, 1500.0))
        assertEquals(ResultCheckKind.Normal, check(25000.0, 11506.3, 2000.0))
        // No nominal diameter: no nominal check.
        assertEquals(ResultCheckKind.Normal, check(25000.0, 11506.3, 0.0))
        // The limits 100 µm and 25400 µm are accepted.
        assertEquals(ResultCheckKind.Normal, check(25000.0, 12450.0, 100.0))
        assertEquals(ResultCheckKind.Normal, check(50800.0, 12700.0, 25400.0))
        assertEquals(false, ResultCheckKind.TipHasNoSize.canApply)
        assertEquals(false, ResultCheckKind.UnlikelyTipSize.canApply)
        assertEquals(true, ResultCheckKind.NominalMismatch.canApply)
        assertEquals(true, ResultCheckKind.Normal.canApply)
    }

    @Test
    fun aRefusedResultHasNoApply() {
        val outcome = completeRun(gauge = 23000.0).evaluate(2000.0)
        val refused = assertIs<TipCalibrationOutcome.Refused>(outcome)
        assertEquals(ResultCheckKind.TipHasNoSize, refused.check)
    }

    @Test
    fun anIncompleteRunOrPointsOnALineGiveNoResult() {
        assertEquals(TipCalibrationOutcome.NotComplete, completeRun().copy(gaugeUm = null).evaluate(2000.0))
        val three = completeRun().let { run -> run.copy(positions = run.positions.dropLast(1)) }
        assertEquals(TipCalibrationOutcome.NotComplete, three.evaluate(2000.0))
        val line = TipCalibrationRun(
            profileId = "probe-7",
            started = "2026-10-11 13:52:10",
            gaugeUm = 25000.0,
            positions = listOf(
                position(ClockPosition.Nine, 0.0, 0.0),
                position(ClockPosition.Three, 10.0, 10.0),
                position(ClockPosition.Twelve, 20.0, 20.0),
                position(ClockPosition.Six, 30.0, 30.0),
            ),
        )
        assertEquals(TipCalibrationOutcome.FitFailed, line.evaluate(2000.0))
    }
}
