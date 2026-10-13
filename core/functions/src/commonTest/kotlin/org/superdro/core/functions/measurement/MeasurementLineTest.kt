package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.PointSource

class MeasurementLineTest {

    private val tolerance = 20.0
    private val mm3 = LogDisplayFormat(metric = true, precision = 3)

    private fun latch(x: Double, y: Double) = CollectedPoint(x = x, y = y, source = PointSource.Latch)

    private fun probe(x: Double, y: Double, ax: Int, ay: Int, radius: Double?) =
        CollectedPoint(x = x, y = y, source = PointSource.Probe, tipRadiusUm = radius, approachX = ax, approachY = ay)

    private fun angles(verb: MeasurementVerb, vararg points: CollectedPoint): List<String> =
        MeasurementFunctions.evaluate(verb, points.toList(), tolerance).results.map { result ->
            result.value?.let { value -> MeasurementFormat.valueText(value, mm3) } ?: "—"
        }

    @Test
    fun theDistanceExampleGivesTheFourResults() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.Distance,
            listOf(latch(0.0, 0.0), latch(30000.0, 40000.0)),
            tolerance,
        )
        assertEquals(
            listOf(ResultKey.Distance, ResultKey.Dx, ResultKey.Dy, ResultKey.Midpoint),
            evaluation.results.map { result -> result.key },
        )
        assertEquals(
            listOf("50.000 mm", "30.000 mm", "40.000 mm", "15.000, 20.000 mm"),
            evaluation.results.map { result -> MeasurementFormat.valueText(result.value!!, mm3) },
        )
        assertEquals(AddAction.AddSubDatum(MeasuredSubDatumName.Midpoint), evaluation.results[3].addAction)
    }

    @Test
    fun thePointFunctionGivesThePosition() {
        val evaluation = MeasurementFunctions.evaluate(MeasurementVerb.Point, listOf(latch(1234.5, -42.0)), tolerance)
        assertEquals(ResultValue.Position(1234.5, -42.0), evaluation.results.single().value)
        assertEquals(AddAction.AddSubDatum(MeasuredSubDatumName.MeasuredPoint), evaluation.results.single().addAction)
    }

    @Test
    fun theLineExampleGivesTheTwoAngles() {
        assertEquals(
            listOf("0.57°", "-89.43°"),
            angles(MeasurementVerb.LineAngle, latch(0.0, 0.0), latch(100000.0, 1000.0)),
        )
    }

    // The tip example
    @Test
    fun theTipExampleGivesTheSurfaceLine() {
        val fit = MeasurementMath.lineFrom2Points(
            probe(0.0, 1000.0, 0, -1, 1000.0),
            probe(50000.0, 1000.0, 0, -1, 1000.0),
            tolerance,
        )
        val line = assertIs<LineFit.Fitted>(fit).line
        assertEquals(0.0, line.startXUm, 1e-9)
        assertEquals(0.0, line.startYUm, 1e-9)
        assertEquals(50000.0, line.endXUm, 1e-9)
        assertEquals(0.0, line.endYUm, 1e-9)
        assertEquals("0.00", MeasurementFormat.angle(line.angleToX))
    }

    @Test
    fun anglesGoIntoTheHalfOpenRange() {
        assertEquals(90.0, MeasurementMath.angleToX(0.0, 1.0), 1e-12)
        assertEquals(90.0, MeasurementMath.angleToX(0.0, -1.0), 1e-12)
        assertEquals(0.0, MeasurementMath.angleToX(-1.0, 0.0), 1e-12)
        assertEquals(-45.0, MeasurementMath.angleToX(-1.0, 1.0), 1e-12)
        assertEquals(45.0, MeasurementMath.angleToX(-1.0, -1.0), 1e-12)
        assertEquals(0.0, MeasurementMath.angleToY(90.0), 1e-12)
        assertEquals(90.0, MeasurementMath.angleToY(0.0), 1e-12)
        assertEquals(-45.0, MeasurementMath.angleToY(45.0), 1e-12)
    }

    @Test
    fun pointsCloserThanTheToleranceGiveTheTooCloseFailure() {
        val fit = MeasurementMath.lineFrom2Points(latch(0.0, 0.0), latch(19.0, 0.0), tolerance)
        assertEquals(MeasurementFailure.PointsTooClose, assertIs<LineFit.Failed>(fit).failure)
        assertIs<LineFit.Fitted>(MeasurementMath.lineFrom2Points(latch(0.0, 0.0), latch(20.0, 0.0), tolerance))
    }

    @Test
    fun tipCirclesWithNoCommonTangentGiveTheirOwnFailure() {
        val fit = MeasurementMath.lineFrom2Points(probe(0.0, 0.0, 0, -1, 2000.0), latch(1000.0, 0.0), tolerance)
        assertEquals(MeasurementFailure.TangentImpossible, assertIs<LineFit.Failed>(fit).failure)
    }

    @Test
    fun theNormalCountOfTheTangentGivesTwoSeparateFailures() {
        val none = MeasurementMath.lineFrom2Points(
            probe(0.0, 1000.0, 0, -1, 1000.0),
            probe(50000.0, 1000.0, 0, 1, 1000.0),
            tolerance,
        )
        assertEquals(MeasurementFailure.LineTipNoNormal, assertIs<LineFit.Failed>(none).failure)
        val two = MeasurementMath.lineFrom2Points(probe(1000.0, 0.0, -1, 0, 900.0), latch(0.0, 0.0), tolerance)
        assertEquals(MeasurementFailure.LineTipTwoNormals, assertIs<LineFit.Failed>(two).failure)
    }

    @Test
    fun theAngleBetweenLinesExampleGivesTheAngleTheSupplementAndTheCorner() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.AngleBetweenLines,
            listOf(latch(0.0, 0.0), latch(100000.0, 0.0), latch(100000.0, 10000.0), latch(90000.0, 27321.0)),
            tolerance,
        )
        assertNull(evaluation.failure)
        val texts = evaluation.results.map { result -> MeasurementFormat.valueText(result.value!!, mm3) }
        assertEquals(listOf("120.00°", "60.00°", "105.773, 0.000 mm"), texts)
        assertEquals(119.9993, (evaluation.results[0].value as ResultValue.Angle).degrees, 5e-5)
        assertEquals(true, evaluation.results[2].addEnabled)
        assertEquals(AddAction.AddSubDatum(MeasuredSubDatumName.Corner), evaluation.results[2].addAction)
    }

    // The exact point gives 105.774
    @Test
    fun theExactPointGivesTheCornerWithTheHalfUpRoundOff() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.AngleBetweenLines,
            listOf(latch(0.0, 0.0), latch(100000.0, 0.0), latch(100000.0, 10000.0), latch(90000.0, 27320.5)),
            tolerance,
        )
        assertEquals("105.774, 0.000 mm", MeasurementFormat.valueText(evaluation.results[2].value!!, mm3))
    }

    @Test
    fun parallelLinesGiveAnAngleButNoCorner() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.AngleBetweenLines,
            listOf(latch(0.0, 0.0), latch(100000.0, 0.0), latch(0.0, 10000.0), latch(100000.0, 10000.0)),
            tolerance,
        )
        assertEquals(ResultValue.Angle(0.0), evaluation.results[0].value)
        assertEquals(ResultValue.Angle(180.0), evaluation.results[1].value)
        assertNull(evaluation.results[2].value)
        assertEquals(false, evaluation.results[2].addEnabled)
    }

    @Test
    fun aCornerFarFromThePointsShowsButCannotBeAdded() {
        val evaluation = MeasurementFunctions.evaluate(
            MeasurementVerb.AngleBetweenLines,
            listOf(latch(0.0, 0.0), latch(100000.0, 0.0), latch(0.0, 10000.0), latch(100000.0, 10100.0)),
            tolerance,
        )
        val corner = evaluation.results[2].value as ResultValue.Position
        assertEquals(-10000000.0, corner.xUm, 1e-3)
        assertEquals(false, evaluation.results[2].addEnabled)
    }

    @Test
    fun theIncludedAngleIsInTheHalfOpenRange() {
        assertEquals(0.0, MeasurementMath.includedAngle(10.0, 10.0), 1e-12)
        assertEquals(170.0, MeasurementMath.includedAngle(10.0, 0.0), 1e-12)
        assertEquals(30.0, MeasurementMath.includedAngle(-60.0, -30.0), 1e-12)
    }
}
