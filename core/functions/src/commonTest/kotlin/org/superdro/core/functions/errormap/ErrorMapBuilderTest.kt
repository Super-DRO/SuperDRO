package org.superdro.core.functions.errormap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.MapPoint

class ErrorMapBuilderTest {

    /** A round trip from [from] to [to] and back with [step] counts. The reference is the target plus [error]. */
    private fun roundTrip(from: Long, to: Long, step: Long, startTime: Long = 0, error: (Long) -> Long): RecordedPass {
        val pairs = ArrayList<SamplePair>()
        var time = startTime
        var t = from
        while (t <= to) {
            pairs.add(SamplePair(t, t + error(t), time++))
            t += step
        }
        t = to - step
        while (t >= from) {
            pairs.add(SamplePair(t, t + error(t), time++))
            t -= step
        }
        return RecordedPass(pairs)
    }

    /** 8 counts of error in each odd block of 10000 counts. */
    private fun squareWave(t: Long): Long = if ((t / 10000) % 2L == 1L) 8 else 0

    private fun linearPasses(): List<RecordedPass> =
        listOf(roundTrip(0, 100000, 1000) { it / 1000 }, roundTrip(0, 100000, 1000, 1000) { it / 1000 })

    private fun built(passes: List<RecordedPass>, verification: RecordedPass? = null): ErrorMapReport {
        val build = ErrorMapBuilder.build(passes, verification)
        assertIs<ErrorMapBuild.Built>(build)
        return build.report
    }

    @Test
    fun noPassOrOneSampleGivesNotEnoughData() {
        assertEquals(ErrorMapBuild.NotEnoughData, ErrorMapBuilder.build(emptyList()))
        assertEquals(ErrorMapBuild.NotEnoughData, ErrorMapBuilder.build(listOf(RecordedPass())))
        assertEquals(ErrorMapBuild.NotEnoughData, ErrorMapBuilder.build(listOf(RecordedPass(listOf(SamplePair(5, 6, 1))))))
    }

    @Test
    fun aPureLinearErrorGivesTheLinearTermAndAFlatMap() {
        // The reference is 1000 ppm longer than the target: delta = target / 1000.
        val report = built(linearPasses())
        val stats = report.statistics
        assertEquals(1000.0, stats.linearTermPpm, 1e-6)
        assertEquals(50000.0, report.frame.pin)
        // delta' = 50000 × 0.001 = 50 at each position, so the level is 50.
        assertEquals(50.0, report.frame.level, 1e-9)
        assertTrue(report.nodes.isNotEmpty())
        assertTrue(report.nodes.all { it.value == 0L })
        assertEquals(2, stats.passCount)
        assertEquals(402, stats.sampleCount)
        assertTrue(stats.heldOutIsWholePass)
        assertEquals(0.0, stats.spreadBeforeCounts!!, 1e-9)
        assertEquals(0.0, stats.spreadAfterCounts!!, 1e-9)
        assertEquals(listOf(0.0, 0.0), stats.closureCountsByPass)
        assertEquals(null, stats.deadBand)
        assertEquals(0.0, stats.directionSplitCounts!!, 1e-9)
        assertTrue(stats.violations.isEmpty())
        // No range on either side gives the improvement 1.0, and the map does not make it worse.
        assertEquals(1.0, stats.improvement)
        assertTrue(report.advice.isEmpty())
        assertTrue(report.canSave)
        assertTrue(report.showsZeroAt)
        assertFalse(report.expectedErrorInSample)
    }

    @Test
    fun aSquareWaveErrorIsFittedAndTheMapCanBeSaved() {
        val passes = listOf(roundTrip(0, 95000, 100) { squareWave(it) }, roundTrip(0, 95000, 100, 5000) { squareWave(it) })
        val report = built(passes)
        val stats = report.statistics
        assertTrue(stats.nodeCount > 10)
        assertTrue(stats.violations.isEmpty())
        assertTrue(stats.spreadAfterCounts!! < stats.spreadBeforeCounts!! / 4.0)
        assertTrue(stats.improvement >= 1.0)
        val values = report.nodes.map { it.value }
        assertTrue(values.max() - values.min() in 6L..10L, "the map follows the 8-count steps")
        assertTrue(report.advice.none { it.blocksSave })
        assertTrue(report.canSave)
        assertIs<WizardSaveResult.Saved>(report.saveMap("2026-10-12 08:00:00"))
    }

    @Test
    fun onePassCannotBeSaved() {
        val report = built(listOf(roundTrip(0, 95000, 100) { squareWave(it) }))
        assertEquals(1, report.statistics.passCount)
        assertFalse(report.statistics.heldOutIsWholePass)
        assertTrue(report.expectedErrorInSample)
        assertEquals(null, report.statistics.repeatabilityCounts)
        assertEquals(null, report.statistics.referenceDriftCounts)
        assertFalse(report.canSave)
        assertEquals(WizardSaveResult.NotAllowed, report.saveMap("2026-10-12 08:00:00"))
    }

    @Test
    fun aMapThatMakesTheErrorWorseCannotBeSaved() {
        // Two passes agree and the third pass does not: the held-out residuals are larger than the raw error.
        val passes = listOf(
            roundTrip(0, 95000, 100) { 0 },
            roundTrip(0, 95000, 100, 5000) { 0 },
            roundTrip(0, 95000, 100, 10000) { squareWave(it) },
        )
        val report = built(passes)
        assertTrue(report.statistics.improvement < 1.0)
        assertTrue(ErrorMapAdvice.MapMakesItWorse in report.advice)
        assertTrue(ErrorMapAdvice.MapMakesItWorse.blocksSave)
        assertFalse(report.canSave)
        assertEquals(WizardSaveResult.NotAllowed, report.saveMap("2026-10-12 08:00:00"))
    }

    @Test
    fun theVerificationGivesTheResidualRangeAndTheOffset() {
        // The verification reference is 3 counts away from the map frame at each position.
        val verification = roundTrip(0, 100000, 1000, 9000) { it / 1000 + 3 }
        val result = built(linearPasses(), verification).verification
        assertIs<VerificationResult.Measured>(result)
        assertEquals(3.0, result.minResidual, 1e-6)
        assertEquals(3.0, result.maxResidual, 1e-6)
        assertEquals(3.0, result.frameOffsetCounts, 1e-6)
        assertEquals(VerificationResult.NotRecorded, built(linearPasses()).verification)
    }

    @Test
    fun theVerificationDoesNotBlockSave() {
        val verification = roundTrip(0, 100000, 1000, 9000) { it / 1000 + 50 }
        assertTrue(built(linearPasses(), verification).canSave)
    }

    @Test
    fun aSinePatternGivesThePeriodicError() {
        val error = { t: Long -> (5.0 * sin(2 * PI * t / 5000.0)).toLong() }
        val report = built(listOf(roundTrip(0, 100000, 50, error = error), roundTrip(0, 100000, 50, 9000, error)))
        val periodic = report.statistics.periodicError
        assertNotNull(periodic)
        assertTrue(abs(periodic.periodCounts - 5000.0) < 250.0)
        assertEquals(2, periodic.passCount)
        assertTrue(periodic.powerFraction > 0.9)
        assertTrue(periodic.amplitudeCounts >= 1.0)
        assertNotNull(report.statistics.travelExtremes)
    }

    @Test
    fun theAdviceFollowsTheRulesOfTheTable() {
        val base = built(linearPasses()).statistics
        val drift = base.copy(referenceDriftCounts = 11.0, repeatabilityCounts = 2.0)
        assertEquals(listOf(ErrorMapAdvice.ReferenceMoved), ErrorMapBuilder.advice(drift, captureTruncated = false))
        assertEquals(emptyList(), ErrorMapBuilder.advice(base.copy(referenceDriftCounts = 10.0, repeatabilityCounts = 2.0), false))
        val stalls = base.copy(deadBand = DeadBandResult(counts = 30.0, inReference = false, eventCount = 2, spread = 15.0))
        assertEquals(listOf(ErrorMapAdvice.TargetStalls), ErrorMapBuilder.advice(stalls, false))
        val reference = base.copy(deadBand = DeadBandResult(counts = 30.0, inReference = true, eventCount = 2, spread = 0.0))
        assertEquals(emptyList(), ErrorMapBuilder.advice(reference, false))
        assertEquals(listOf(ErrorMapAdvice.CaptureTruncated), ErrorMapBuilder.advice(base, captureTruncated = true))
        val worse = base.copy(rawLow = 0.0, rawHigh = 4.0, correctedLow = -3.0, correctedHigh = 3.0)
        assertEquals(4.0 / 6.0, worse.improvement)
        assertEquals(listOf(ErrorMapAdvice.MapMakesItWorse), ErrorMapBuilder.advice(worse, false))
    }

    @Test
    fun theImprovementIs1WhenAValueIsMissingOrTheDenominatorIsNotPositive() {
        val base = built(linearPasses()).statistics
        assertEquals(1.0, base.copy(rawLow = null).improvement)
        assertEquals(1.0, base.copy(rawLow = 0.0, rawHigh = 9.0, correctedLow = 2.0, correctedHigh = 2.0).improvement)
        assertEquals(3.0, base.copy(rawLow = 0.0, rawHigh = 9.0, correctedLow = 1.0, correctedHigh = 4.0).improvement)
    }

    @Test
    fun saveMapWritesTheProvenanceAndTheNodes() {
        val base = built(linearPasses())
        val nodes = listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9))
        val report = base.copy(nodes = nodes, statistics = base.statistics.copy(passCount = 4, spreadBeforeCounts = 23.0, spreadAfterCounts = 4.0))
        val saved = report.saveMap("2026-10-11 14:03:22")
        assertIs<WizardSaveResult.Saved>(saved)
        assertEquals(
            "# generated-by: SuperDRO Error Map Wizard\n" +
                "# generated-on: 2026-10-11 14:03:22\n" +
                "# passes: 4\n" +
                "# spread-before-counts: 23.0\n" +
                "# spread-after-counts: 4.0\n" +
                "0,0\n10000,4\n20000,9\n",
            saved.text,
        )
        assertEquals(nodes, saved.points)
        // The editor opens the saved map read-only.
        assertTrue(MapEditor.import(saved.text, MapKind.Error).readOnly)
    }

    @Test
    fun saveMapRefusesNodesThatTheValidatorRefuses() {
        val base = built(linearPasses())
        val bad = base.copy(nodes = listOf(MapPoint(0, 0), MapPoint(10, -10)))
        assertEquals(WizardSaveResult.Refused(listOf(MapProblem.DeadBand(0, 10))), bad.saveMap("2026-10-11 14:03:22"))
    }

    @Test
    fun theDiagnosticsGiveTheHalfSpreadsAndThePassRows() {
        val passes = linearPasses()
        val report = built(passes)
        val diagnostics = ErrorMapDiagnostics.of(report, passes, WizardContext("26.42.1", "Mill", "1.2", 'y'))
        assertEquals(1000L, diagnostics.linearTermPpmRounded)
        assertEquals(HeldOutFolds.WholePass, diagnostics.heldOutFolds)
        assertEquals(listOf(PassDiagnostics(201, 100000, 0.0), PassDiagnostics(201, 100000, 0.0)), diagnostics.passRows)
        assertEquals(50000.0, diagnostics.zeroAt)
        assertEquals(402, diagnostics.readings)
        assertEquals(report.statistics.samplesPerNode, diagnostics.readingsPerNode)
    }
}
