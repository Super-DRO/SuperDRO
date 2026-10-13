package org.superdro.core.functions.errormap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.MapPoint

class MapBuilderStepsTest {

    @Test
    fun thePinOfTravel0To200000Is100000() {
        // Margin 10000, lo 10000, hi 190000, step 10000.
        val targets = (0..200).map { it * 1000.0 }
        assertEquals(100000.0, MapBuilderSteps.pin(targets))
    }

    @Test
    fun thePinOfTravelMinus50000To150000Is0() {
        // Lo −40000, hi 140000; 0 is in [lo, hi].
        val targets = (-50..150).map { it * 1000.0 }
        assertEquals(0.0, MapBuilderSteps.pin(targets))
    }

    @Test
    fun thePinUsesTheMedianWhenTheMarginsMeetAndTheDeadBandMargin() {
        assertEquals(5.0, MapBuilderSteps.pin(listOf(5.0, 5.0, 5.0)))
        // margin2 = 60 is larger than 5 % of 1000: lo = 1060, hi = 1940, step 100.
        assertEquals(1500.0, MapBuilderSteps.pin(listOf(1000.0, 2000.0), margin2 = 60.0))
        // lo = 1000.35, hi = 1000.65, step 1: round(1000.5) = 1001 is above hi, so the pin is hi.
        assertEquals(1000.65, MapBuilderSteps.pin(listOf(1000.0, 1001.0), margin2 = 0.35), 1e-9)
    }

    @Test
    fun theNodeFitWithK2GivesTheNodesOfTheWorkedExample() {
        val samples = listOf(
            MapSample(100.0, 3.0), MapSample(110.0, 5.0), MapSample(200.0, 4.0),
            MapSample(210.0, 2.0), MapSample(300.0, -1.0), MapSample(310.0, 1.0),
        )
        assertEquals(listOf(MapPoint(105, 4), MapPoint(205, 3), MapPoint(305, 0)), MapBuilderSteps.fitNodes(2, samples.shuffled()))
    }

    @Test
    fun theIsotonicStepPoolsAViolator() {
        val iso = MapBuilderSteps.isotonic(doubleArrayOf(1.0, 3.0, 2.0, 4.0))
        assertEquals(listOf(1.0, 2.5, 2.5, 4.0), iso.toList())
        // Groups (5, 0), (25, −20), (45, 0): y = 5, 4, 43 pools to 4.5, 4.5, 43.
        val samples = listOf(
            MapSample(0.0, 0.0), MapSample(10.0, 0.0), MapSample(20.0, -20.0),
            MapSample(30.0, -20.0), MapSample(40.0, 0.0), MapSample(50.0, 0.0),
        )
        val nodes = MapBuilderSteps.fitNodes(2, samples)
        assertEquals(listOf(MapPoint(5, 0), MapPoint(25, -19), MapPoint(45, 0)), nodes)
        assertTrue(MapValidation.validateErrorMap(nodes).isEmpty())
    }

    @Test
    fun aCandidateCloserThan5CountsIsDropped() {
        val samples = listOf(MapSample(0.0, 0.0), MapSample(2.0, 0.0), MapSample(3.0, 0.0), MapSample(5.0, 0.0), MapSample(20.0, 0.0), MapSample(22.0, 0.0))
        // Candidates 1, 4 (dropped: less than 5 above 1), 21.
        assertEquals(listOf(MapPoint(1, 0), MapPoint(21, 0)), MapBuilderSteps.fitNodes(2, samples))
    }

    @Test
    fun aShortLastGroupIsDropped() {
        val samples = (0 until 9).map { MapSample(it * 10.0, 0.0) }
        // K = 4: groups of 4, 4, then 1 sample (fewer than max(2, 2)).
        assertEquals(listOf(MapPoint(15, 0), MapPoint(55, 0)), MapBuilderSteps.fitNodes(4, samples))
    }

    @Test
    fun theFitValueIsLinearWithFlatEnds() {
        val nodes = listOf(MapPoint(0, 0), MapPoint(100, 10), MapPoint(200, 0))
        assertEquals(0.0, MapBuilderSteps.fitValue(nodes, -50.0))
        assertEquals(5.0, MapBuilderSteps.fitValue(nodes, 50.0))
        assertEquals(5.0, MapBuilderSteps.fitValue(nodes, 150.0))
        assertEquals(0.0, MapBuilderSteps.fitValue(nodes, 900.0))
        assertEquals(0.0, MapBuilderSteps.fitValue(emptyList(), 5.0))
    }

    @Test
    fun oneSampleListGivesFourInterleavedFolds() {
        val samples = (0 until 10).map { MapSample(it.toDouble(), 0.0) }
        val folds = MapBuilderSteps.folds(listOf(samples))
        assertEquals(4, folds.size)
        assertEquals(listOf(0.0, 4.0, 8.0), folds[0].map { it.position })
        assertEquals(listOf(3.0, 7.0), folds[3].map { it.position })
        val two = listOf(samples, samples)
        assertEquals(two, MapBuilderSteps.folds(two))
    }

    @Test
    fun withNoScoreKIs2ForFewSamplesAnd16ForMany() {
        assertEquals(2, MapBuilderSteps.selectK(listOf((0 until 10).map { MapSample(it.toDouble(), 0.0) })))
        // One fold only: no fold has a training set, so no K has a score.
        assertEquals(16, MapBuilderSteps.selectK(listOf((0 until 64).map { MapSample(it.toDouble(), 0.0) })))
    }

    @Test
    fun theDeadBandOfAStallingTargetIs30Counts() {
        val passes = listOf(stallPass(0), stallPass(1_000_000))
        val band = MapBuilderSteps.deadBand(passes)
        assertEquals(DeadBandResult(counts = 30.0, inReference = false, eventCount = 2, spread = 0.0), band)
        assertEquals(60.0, band!!.margin2)
    }

    @Test
    fun aShortPassHasNoDeadBand() {
        assertNull(MapBuilderSteps.deadBand(listOf(RecordedPass((0 until 7).map { SamplePair(it * 1000L, it * 1000L, it.toLong()) }))))
    }

    @Test
    fun theDirectionSplitOfAStallingTargetIs30Counts() {
        val passes = listOf(stallPass(0), stallPass(1_000_000)).map { MapBuilderSteps.passSamples(it) }
        val split = MapBuilderSteps.measureSplit(passes)
        assertNotNull(split)
        assertEquals(30.0, split.atMidTravel)
        assertTrue(split.knots.isNotEmpty())
        // After the removal, the up leg and the down leg are equal far from the turn.
        val removed = MapBuilderSteps.removeSplit(passes[0], split)
        assertEquals(-15.0, removed[100].delta)
        assertEquals(-15.0, removed[removed.size - 100].delta)
    }

    @Test
    fun theClosureOfARoundTripIsKept() {
        val trip = RecordedPass(listOf(SamplePair(0, 0, 0), SamplePair(1000, 0, 1), SamplePair(10, 0, 2), SamplePair(10, 1, 3)))
        assertEquals(10.0, MapBuilderSteps.closure(trip))
        val oneWay = RecordedPass(listOf(SamplePair(0, 0, 0), SamplePair(1000, 0, 1)))
        assertNull(MapBuilderSteps.closure(oneWay))
    }

    @Test
    fun theLinearTermIsTheLeastSquaresSlope() {
        val samples = (0..10).map { MapSample(it * 100.0, it * 100.0 * 0.002 + 7.0) }
        assertEquals(0.002, MapBuilderSteps.slope(samples), 1e-12)
    }

    /**
     * A round trip from 0 to 10000 counts and back in steps of 10. The target follows the
     * reference on the way up. On the way down, the target stays still for 30 counts of the
     * reference and then follows 30 counts behind.
     */
    private fun stallPass(startTime: Long): RecordedPass {
        val pairs = ArrayList<SamplePair>()
        var time = startTime
        for (r in 0..10000 step 10) pairs.add(SamplePair(r.toLong(), r.toLong(), time++))
        for (r in 9990 downTo 0 step 10) pairs.add(SamplePair(minOf(10000L, r + 30L), r.toLong(), time++))
        return RecordedPass(pairs)
    }
}
