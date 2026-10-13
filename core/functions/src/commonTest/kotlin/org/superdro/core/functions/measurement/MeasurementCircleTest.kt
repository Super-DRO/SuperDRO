package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.PointSource

class MeasurementCircleTest {

    private val tolerance = MeasurementTolerance.toleranceUm(listOf(5080.0, 5080.0))
    private val mm3 = LogDisplayFormat(metric = true, precision = 3)

    private fun latch(x: Double, y: Double) = CollectedPoint(x = x, y = y, source = PointSource.Latch)

    private fun probe(x: Double, y: Double, ax: Int, ay: Int, radius: Double = 1000.0) =
        CollectedPoint(x = x, y = y, source = PointSource.Probe, tipRadiusUm = radius, approachX = ax, approachY = ay)

    @Test
    fun threePointsGiveTheCenterAndTheDiameterOfTheWorkedExample() {
        val fit = MeasurementMath.circleFrom3Points(latch(0.0, 0.0), latch(40000.0, 0.0), latch(0.0, 30000.0), tolerance)
        val circle = assertIs<CircleFit.Fitted>(fit).circle
        assertEquals(20000.0, circle.centerXUm, 1e-9)
        assertEquals(15000.0, circle.centerYUm, 1e-9)
        assertEquals(50000.0, circle.diameterUm, 1e-9)
        assertEquals(0, circle.side)
        assertEquals("Ø 50.000 mm", MeasurementFormat.valueText(ResultValue.Diameter(circle.diameterUm), mm3))
        assertEquals("20.000, 15.000 mm", MeasurementFormat.valueText(ResultValue.Position(circle.centerXUm, circle.centerYUm), mm3))
    }

    @Test
    fun aTwoMillimeterBallWithOutwardApproachGivesTheBore() {
        val fit = MeasurementMath.circleFrom3Points(
            probe(0.0, 0.0, -1, -1),
            probe(40000.0, 0.0, 1, -1),
            probe(0.0, 30000.0, -1, 1),
            tolerance,
        )
        val circle = assertIs<CircleFit.Fitted>(fit).circle
        assertEquals(-1, circle.side)
        assertEquals(20000.0, circle.centerXUm, 1e-6)
        assertEquals(15000.0, circle.centerYUm, 1e-6)
        assertEquals(26000.0, circle.radiusUm, 1e-6)
        assertEquals("Ø 52.000 mm", MeasurementFormat.valueText(ResultValue.Diameter(circle.diameterUm), mm3))
    }

    @Test
    fun aTwoMillimeterBallWithInwardApproachGivesTheBoss() {
        val fit = MeasurementMath.circleFrom3Points(
            probe(0.0, 0.0, 1, 1),
            probe(40000.0, 0.0, -1, 1),
            probe(0.0, 30000.0, 1, -1),
            tolerance,
        )
        val circle = assertIs<CircleFit.Fitted>(fit).circle
        assertEquals(1, circle.side)
        assertEquals(24000.0, circle.radiusUm, 1e-6)
        assertEquals("Ø 48.000 mm", MeasurementFormat.valueText(ResultValue.Diameter(circle.diameterUm), mm3))
    }

    // The table of the two sides
    @Test
    fun theTipFitGivesTheTwoCandidatesOfTheWorkedTable() {
        val points = listOf(probe(0.0, 0.0, -1, -1), probe(40000.0, 0.0, 1, -1), probe(0.0, 30000.0, -1, 1))
        val candidates = MeasurementMath.tipCandidates(points, listOf(1000.0, 1000.0, 1000.0))
        assertEquals(listOf(-1, 1), candidates.map { candidate -> candidate.side })
        assertEquals(26000.0, candidates[0].radiusUm, 1e-6)
        assertEquals(24000.0, candidates[1].radiusUm, 1e-6)
        assertEquals(true, MeasurementMath.agreesWithApproach(candidates[0], points))
        assertEquals(false, MeasurementMath.agreesWithApproach(candidates[1], points))
    }

    @Test
    fun threePointsInALineGiveTheCollinearFailure() {
        val fit = MeasurementMath.circleFrom3Points(latch(0.0, 0.0), latch(20000.0, 5.0), latch(40000.0, 0.0), tolerance)
        assertEquals(MeasurementFailure.Collinear, assertIs<CircleFit.Failed>(fit).failure)
        val same = MeasurementMath.circleFrom3Points(latch(0.0, 0.0), latch(0.0, 0.0), latch(0.0, 0.0), tolerance)
        assertEquals(MeasurementFailure.Collinear, assertIs<CircleFit.Failed>(same).failure)
    }

    @Test
    fun approachSignsThatDisagreeGiveTheNoCandidateFailure() {
        val fit = MeasurementMath.circleFrom3Points(
            probe(0.0, 0.0, -1, -1),
            probe(40000.0, 0.0, -1, 1),
            probe(0.0, 30000.0, -1, 1),
            tolerance,
        )
        assertEquals(MeasurementFailure.TipFitNoCandidate, assertIs<CircleFit.Failed>(fit).failure)
    }

    @Test
    fun twoValidCandidatesGiveTheirOwnFailure() {
        val fit = MeasurementMath.circleFrom3Points(
            probe(-5000.0, 1000.0, 0, 0, radius = 1000.0),
            probe(3000.0, -3000.0, 1, 1, radius = 3000.0),
            probe(3000.0, 3000.0, -1, 1, radius = 500.0),
            tolerance,
        )
        assertEquals(MeasurementFailure.TipFitTwoCandidates, assertIs<CircleFit.Failed>(fit).failure)
    }

    @Test
    fun theCircleFunctionGivesTheCenterAndTheDiameterRows() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.Circle,
            listOf(latch(0.0, 0.0), latch(40000.0, 0.0), latch(0.0, 30000.0)),
            tolerance,
        )
        assertNull(evaluation.failure)
        assertEquals(listOf(ResultKey.Center, ResultKey.Diameter), evaluation.results.map { result -> result.key })
        assertEquals(ResultValue.Position(20000.0, 15000.0), evaluation.results[0].value)
        assertEquals(ResultValue.Diameter(50000.0), evaluation.results[1].value)
        assertEquals(AddAction.AddSubDatum(MeasuredSubDatumName.FittedCenter), evaluation.results[0].addAction)
        assertEquals(AddAction.AddCircleToDrawing, evaluation.results[1].addAction)
        assertEquals(true, evaluation.results[1].addEnabled)
        assertEquals(FittedCircle(20000.0, 15000.0, 25000.0), evaluation.circle)
    }

    @Test
    fun aFailedCircleHasNoValues() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.Circle,
            listOf(latch(0.0, 0.0), latch(1.0, 0.0), latch(2.0, 0.0)),
            tolerance,
        )
        assertEquals(MeasurementFailure.Collinear, evaluation.failure)
        assertEquals(listOf(null, null), evaluation.results.map { result -> result.value })
        assertEquals(false, evaluation.results[0].addEnabled)
    }
}
