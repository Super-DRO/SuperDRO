package org.superdro.core.functions.measurement

import kotlin.math.hypot
import org.superdro.core.model.CollectedPoint

/**
 * The evaluation of a measurement function.
 *
 * @property results The result rows in the order of the function. A row has no value while the
 *   set is not complete or when the function fails.
 * @property failure The failure of a complete set, or null.
 * @property circle The fitted circle of [MeasurementVerb.Circle], for the graphical view.
 * @property lines The fitted lines of [MeasurementVerb.LineAngle] and
 *   [MeasurementVerb.AngleBetweenLines].
 */
data class MeasurementEvaluation(
    val results: List<MeasurementResult>,
    val failure: MeasurementFailure? = null,
    val circle: FittedCircle? = null,
    val lines: List<FittedLine> = emptyList(),
) {
    /** True when at least one result has a value. */
    val hasValue: Boolean get() = results.any { result -> result.value != null }
}

/**
 * The results of the five measurement functions.
 */
object MeasurementFunctions {

    /**
     * Evaluates [verb] with the points of [slots]. While a slot is empty, all results have no
     * value. [toleranceUm] comes from [MeasurementTolerance].
     */
    fun evaluate(verb: MeasurementVerb, slots: List<CollectedPoint?>, toleranceUm: Double): MeasurementEvaluation {
        require(slots.size == verb.slotCount) { "the function ${verb.wire} has ${verb.slotCount} slots" }
        val points = slots.filterNotNull()
        if (points.size < verb.slotCount) return MeasurementEvaluation(emptyResults(verb))
        return when (verb) {
            MeasurementVerb.Point -> point(points[0])
            MeasurementVerb.Distance -> distance(points[0], points[1])
            MeasurementVerb.Circle -> circle(points, toleranceUm)
            MeasurementVerb.LineAngle -> lineAngle(points, toleranceUm)
            MeasurementVerb.AngleBetweenLines -> angleBetweenLines(points, toleranceUm)
        }
    }

    /** Gives the result rows of [verb] with no values. */
    fun emptyResults(verb: MeasurementVerb): List<MeasurementResult> = when (verb) {
        MeasurementVerb.Point -> listOf(
            MeasurementResult(ResultKey.Position, addAction = AddAction.AddSubDatum(MeasuredSubDatumName.MeasuredPoint)),
        )
        MeasurementVerb.Distance -> listOf(
            MeasurementResult(ResultKey.Distance),
            MeasurementResult(ResultKey.Dx),
            MeasurementResult(ResultKey.Dy),
            MeasurementResult(ResultKey.Midpoint, addAction = AddAction.AddSubDatum(MeasuredSubDatumName.Midpoint)),
        )
        MeasurementVerb.Circle -> listOf(
            MeasurementResult(ResultKey.Center, addAction = AddAction.AddSubDatum(MeasuredSubDatumName.FittedCenter)),
            MeasurementResult(ResultKey.Diameter, addAction = AddAction.AddCircleToDrawing),
        )
        MeasurementVerb.LineAngle -> listOf(
            MeasurementResult(ResultKey.AngleToX),
            MeasurementResult(ResultKey.AngleToY),
        )
        MeasurementVerb.AngleBetweenLines -> listOf(
            MeasurementResult(ResultKey.IncludedAngle),
            MeasurementResult(ResultKey.Supplement),
            MeasurementResult(ResultKey.Corner, addAction = AddAction.AddSubDatum(MeasuredSubDatumName.Corner)),
        )
    }

    private fun point(p: CollectedPoint): MeasurementEvaluation {
        val rows = emptyResults(MeasurementVerb.Point)
        return MeasurementEvaluation(listOf(rows[0].withValue(ResultValue.Position(p.x, p.y))))
    }

    private fun distance(p1: CollectedPoint, p2: CollectedPoint): MeasurementEvaluation {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val rows = emptyResults(MeasurementVerb.Distance)
        return MeasurementEvaluation(
            listOf(
                rows[0].withValue(ResultValue.Length(hypot(dx, dy))),
                rows[1].withValue(ResultValue.Length(dx)),
                rows[2].withValue(ResultValue.Length(dy)),
                rows[3].withValue(ResultValue.Position((p1.x + p2.x) / 2.0, (p1.y + p2.y) / 2.0)),
            ),
        )
    }

    private fun circle(points: List<CollectedPoint>, toleranceUm: Double): MeasurementEvaluation {
        val rows = emptyResults(MeasurementVerb.Circle)
        return when (val fit = MeasurementMath.circleFrom3Points(points[0], points[1], points[2], toleranceUm)) {
            is CircleFit.Failed -> MeasurementEvaluation(rows, failure = fit.failure)
            is CircleFit.Fitted -> MeasurementEvaluation(
                listOf(
                    rows[0].withValue(ResultValue.Position(fit.circle.centerXUm, fit.circle.centerYUm)),
                    rows[1].withValue(ResultValue.Diameter(fit.circle.diameterUm)),
                ),
                circle = fit.circle,
            )
        }
    }

    private fun lineAngle(points: List<CollectedPoint>, toleranceUm: Double): MeasurementEvaluation {
        val rows = emptyResults(MeasurementVerb.LineAngle)
        return when (val fit = MeasurementMath.lineFrom2Points(points[0], points[1], toleranceUm)) {
            is LineFit.Failed -> MeasurementEvaluation(rows, failure = fit.failure)
            is LineFit.Fitted -> MeasurementEvaluation(
                listOf(
                    rows[0].withValue(ResultValue.Angle(fit.line.angleToX)),
                    rows[1].withValue(ResultValue.Angle(fit.line.angleToY)),
                ),
                lines = listOf(fit.line),
            )
        }
    }

    private fun angleBetweenLines(points: List<CollectedPoint>, toleranceUm: Double): MeasurementEvaluation {
        val rows = emptyResults(MeasurementVerb.AngleBetweenLines)
        val first = MeasurementMath.lineFrom2Points(points[0], points[1], toleranceUm)
        if (first is LineFit.Failed) return MeasurementEvaluation(rows, failure = first.failure)
        val second = MeasurementMath.lineFrom2Points(points[2], points[3], toleranceUm)
        if (second is LineFit.Failed) return MeasurementEvaluation(rows, failure = second.failure)
        val line1 = (first as LineFit.Fitted).line
        val line2 = (second as LineFit.Fitted).line
        val angle = MeasurementMath.includedAngle(line1.angleToX, line2.angleToX)
        val corner = MeasurementMath.corner(line1, line2)
        val cornerRow = if (corner == null) {
            rows[2]
        } else {
            rows[2].copy(
                value = ResultValue.Position(corner.h, corner.v),
                addEnabled = MeasurementMath.cornerAddEnabled(corner, line1, line2),
            )
        }
        return MeasurementEvaluation(
            listOf(
                rows[0].withValue(ResultValue.Angle(angle)),
                rows[1].withValue(ResultValue.Angle(180.0 - angle)),
                cornerRow,
            ),
            lines = listOf(line1, line2),
        )
    }

    private fun MeasurementResult.withValue(value: ResultValue): MeasurementResult =
        copy(value = value, addEnabled = addAction != null)
}
