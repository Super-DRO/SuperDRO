package org.superdro.core.functions.drawing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape

/** The default placement of a drawing on its first load. */
enum class DefaultPlacement {
    /** The drawing origin goes to the workspace origin (offset 0, 0). */
    DocumentOrigin,

    /** The lower left corner of the extents goes to the workspace origin. */
    ExtentsLowerLeft,
}

/** One mirror, rotate, or scale step of "Set Up Drawing". */
sealed interface PlacementStep {
    /** The token of the step in the saved state: `L`, `R`, `H`, `V`, or `S:<factor>`. */
    val token: String

    /** Rotate 90° counterclockwise about the drawing origin. */
    data object RotateLeft : PlacementStep {
        override val token: String = "L"
    }

    /** Rotate 90° clockwise about the drawing origin. */
    data object RotateRight : PlacementStep {
        override val token: String = "R"
    }

    /** Mirror x → −x. */
    data object MirrorHorizontal : PlacementStep {
        override val token: String = "H"
    }

    /** Mirror y → −y. */
    data object MirrorVertical : PlacementStep {
        override val token: String = "V"
    }

    /** Scale by [factor] about the drawing origin (the unit correction of [UnitFactor]). */
    data class Scale(val factor: Double) : PlacementStep {
        override val token: String = "S:$factor"
    }

    companion object {
        /** Gives the step of [token], or null for an unknown token. */
        fun parse(token: String): PlacementStep? = when {
            token == "L" -> RotateLeft
            token == "R" -> RotateRight
            token == "H" -> MirrorHorizontal
            token == "V" -> MirrorVertical
            token.startsWith("S:") -> token.removePrefix("S:").toDoubleOrNull()?.takeIf { factor -> factor > 0.0 && factor.isFinite() }?.let(::Scale)
            else -> null
        }
    }
}

/** The unit errors that the unit test finds. */
enum class UnitFactor(val factor: Double) {
    InchWrittenAsMm(25.4),
    MmWrittenAsInch(1.0 / 25.4),
    UmWrittenAsNm(1000.0),
    NmWrittenAsUm(0.001),
    MmWrittenAsNm(1_000_000.0),
    NmWrittenAsMm(0.000_001),
}

/** The result of the unit test of two reference points. */
sealed interface UnitCheck {
    /** Fewer than two references, or the distances agree within 0.5 %. */
    data object Agrees : UnitCheck

    /** The drawing has the wrong unit. "Apply" scales the drawing by [unit] `.factor`. */
    data class WrongUnit(val unit: UnitFactor) : UnitCheck

    /**
     * The distances do not agree. [differenceMm] = machine distance − drawing distance in mm: a
     * positive value is "further apart on the machine", a negative value is "closer together".
     */
    data class Mismatch(val differenceMm: Double) : UnitCheck
}

/**
 * A reference point of "Set Up Drawing".
 *
 * @property drawingNm The point in the drawing, in nm, after the steps.
 * @property machineUm The machine position as a view position (h, v) in µm, with the vertical
 *   sign of the plane (on a lathe (Z, −X)).
 */
data class PlacementReference(val drawingNm: Vec2, val machineUm: Vec2)

/** The result of "Apply": the placed shapes in nm and their snap point groups. */
data class PlacedDrawing(val shapes: List<DrawingShape>, val snapGroups: List<SnapGroup>)

/**
 * The state of "Set Up Drawing". A machine point is
 * `R(θ) × (drawing point + offset)`, with the drawing point in µm.
 *
 * Each function gives a new state. A mirror, rotate, or scale step also moves the drawing
 * points of the references. Then the function calculates the placement again from the
 * references, so that the set references stay on their machine points.
 *
 * @property fileShapes The shapes as loaded from the file.
 * @property steps The mirror, rotate, and scale steps, in order.
 * @property shapes The shapes after the steps, without the offset and the rotation.
 * @property offsetUm The offset of the placement in µm.
 * @property rotationRad The rotation θ of the placement.
 * @property references Reference 1 and reference 2, or null for a card that is not set.
 * @property locked True while the placement cannot change, for example during "Apply".
 */
data class DrawingPlacement(
    val fileShapes: List<DrawingShape>,
    val steps: List<PlacementStep>,
    val shapes: List<DrawingShape>,
    val defaultPlacement: DefaultPlacement,
    val offsetUm: Vec2,
    val rotationRad: Double,
    val references: List<PlacementReference?> = listOf(null, null),
    val locked: Boolean = false,
) {

    /** The count of the set references. */
    val referenceCount: Int get() = references.count { reference -> reference != null }

    /** Gives the state after "rotate left" (90° counterclockwise). */
    fun rotateLeft(): DrawingPlacement = withStep(PlacementStep.RotateLeft)

    /** Gives the state after "rotate right" (90° clockwise). */
    fun rotateRight(): DrawingPlacement = withStep(PlacementStep.RotateRight)

    /** Gives the state after the horizontal mirror (x → −x). */
    fun mirrorHorizontal(): DrawingPlacement = withStep(PlacementStep.MirrorHorizontal)

    /** Gives the state after the vertical mirror (y → −y). */
    fun mirrorVertical(): DrawingPlacement = withStep(PlacementStep.MirrorVertical)

    /** Gives the state with the reference [index] (0 or 1) set. Then the placement follows the references. */
    fun setReference(index: Int, drawingNm: Vec2, machineUm: Vec2): DrawingPlacement {
        require(index in 0..1) { "The reference index must be 0 or 1" }
        if (locked) return this
        val list = references.toMutableList()
        list[index] = PlacementReference(drawingNm, machineUm)
        return copy(references = list).placed()
    }

    /** Gives the state with the reference [index] cleared. The placement does not move. */
    fun clearReference(index: Int): DrawingPlacement {
        require(index in 0..1) { "The reference index must be 0 or 1" }
        if (locked) return this
        val list = references.toMutableList()
        list[index] = null
        return copy(references = list)
    }

    /** Gives the predicted machine position (µm) of the drawing point [drawingNm] (nm). */
    fun predictMachine(drawingNm: Vec2): Vec2 = (drawingNm / 1000.0 + offsetUm).rotated(rotationRad)

    /** Gives the shapes of the preview: the shapes with the offset and the rotation, in nm. */
    fun previewShapes(): List<DrawingShape> = placeShapes(shapes, offsetUm, rotationRad)

    /**
     * Gives the unit test of the two references. The drawing
     * distance is `d`, the machine distance `m`:
     *
     * - `|m − d| ÷ d ≤ 0.5 %`: no message.
     * - `m ≥ 10 mm` and a factor f with `|m − f·d| ÷ (f·d) ≤ 0.5 %`: the unit message of f. When
     *   more factors fit, the smallest error wins.
     * - Else the distance message.
     */
    fun unitCheck(): UnitCheck {
        val first = references[0] ?: return UnitCheck.Agrees
        val second = references[1] ?: return UnitCheck.Agrees
        val d = (second.drawingNm - first.drawingNm).length / 1000.0
        val m = (second.machineUm - first.machineUm).length
        if (!(d > 0.0)) return UnitCheck.Agrees
        if (abs(m - d) / d <= UNIT_TOLERANCE) return UnitCheck.Agrees
        if (m >= UNIT_TEST_MIN_UM) {
            val best = UnitFactor.entries
                .map { unit -> unit to abs(m - unit.factor * d) / (unit.factor * d) }
                .filter { (_, error) -> error <= UNIT_TOLERANCE }
                .minByOrNull { (_, error) -> error }
            if (best != null) return UnitCheck.WrongUnit(best.first)
        }
        return UnitCheck.Mismatch((m - d) / 1000.0)
    }

    /**
     * Gives the placed drawing of "Apply". First the function
     * applies the unit factor of [unitCheck], if there is one. Then it applies the offset
     * (µm × 1000 → nm) and the rotation θ. The snap points come from the placed shapes. Gives
     * null when the state is locked or has no shapes. The point names stay.
     */
    fun apply(): PlacedDrawing? {
        if (locked || shapes.isEmpty()) return null
        val unit = (unitCheck() as? UnitCheck.WrongUnit)?.unit
        val state = if (unit != null) withStep(PlacementStep.Scale(unit.factor)) else this
        val placed = placeShapes(state.shapes, state.offsetUm, state.rotationRad)
        return PlacedDrawing(placed, SnapPoints.extract(placed))
    }

    /** Gives the state of "Reset": the drawing is back to the file as loaded, with the default placement. */
    fun reset(): DrawingPlacement = load(fileShapes)

    /** Gives the state after [step]. The references move with the drawing. */
    fun withStep(step: PlacementStep): DrawingPlacement {
        if (locked || shapes.isEmpty()) return this
        val moved = references.map { reference -> reference?.copy(drawingNm = stepPoint(step, reference.drawingNm)) }
        return copy(
            steps = steps + step,
            shapes = shapes.map { shape -> stepShape(step, shape) },
            references = moved,
        ).placed()
    }

    /** Gives the state with the offset and the rotation from the set references. */
    private fun placed(): DrawingPlacement {
        val set = references.filterNotNull()
        return when (set.size) {
            0 -> this
            1 -> {
                val reference = set[0]
                copy(offsetUm = reference.machineUm.rotated(-rotationRad) - reference.drawingNm / 1000.0)
            }
            else -> {
                val (first, second) = set
                val drawingVector = (second.drawingNm - first.drawingNm)
                val machineVector = second.machineUm - first.machineUm
                var theta = 0.0
                if (drawingVector.length > 0.0 && machineVector.length > 0.0) {
                    val raw = AngleMath.normalizeRadSigned(machineVector.angle - drawingVector.angle)
                    val limitDegrees = AngleMath.radToDegrees(MIN_ROTATION_NM / drawingVector.length)
                    if (abs(AngleMath.radToDegrees(raw)) > limitDegrees) theta = raw
                }
                copy(rotationRad = theta, offsetUm = first.machineUm.rotated(-theta) - first.drawingNm / 1000.0)
            }
        }
    }

    companion object {
        /** The limit of the unit test: 0.5 %. */
        const val UNIT_TOLERANCE: Double = 0.005

        /** The unit message needs a machine distance of 10 mm or more. */
        const val UNIT_TEST_MIN_UM: Double = 10_000.0

        /** The dialog ignores a rotation that moves the far reference point less than 50 µm (50000 nm). */
        const val MIN_ROTATION_NM: Double = 50_000.0

        /** Gives the state of a drawing on its first load, with the default placement. */
        fun load(shapes: List<DrawingShape>): DrawingPlacement {
            val placement = defaultPlacement(shapes)
            val box = ShapeGeometry.extents(shapes)
            val offset = if (placement == DefaultPlacement.ExtentsLowerLeft && box != null) {
                Vec2(-box.minX / 1000.0, -box.minY / 1000.0)
            } else {
                Vec2.ZERO
            }
            return DrawingPlacement(shapes, emptyList(), shapes, placement, offset, 0.0)
        }

        /**
         * Gives the default placement. The function compares the gap
         * between the drawing origin and the extents box with the larger side of the box. Each gap
         * is 0 when the origin is in the box range of that axis. When the X gap or the Y gap is
         * more than the larger side, the lower left corner goes to the workspace origin.
         */
        fun defaultPlacement(shapes: List<DrawingShape>): DefaultPlacement {
            val box = ShapeGeometry.extents(shapes) ?: return DefaultPlacement.DocumentOrigin
            val side = max(box.width, box.height)
            val gapX = gap(box.minX, box.maxX)
            val gapY = gap(box.minY, box.maxY)
            return if (gapX > side || gapY > side) DefaultPlacement.ExtentsLowerLeft else DefaultPlacement.DocumentOrigin
        }

        /** Gives [shapes] moved by [offsetUm] and then turned by [rotationRad] about the origin, in nm. */
        fun placeShapes(shapes: List<DrawingShape>, offsetUm: Vec2, rotationRad: Double): List<DrawingShape> {
            val dx = (offsetUm.x * 1000.0).roundToLong()
            val dy = (offsetUm.y * 1000.0).roundToLong()
            return shapes.map { shape ->
                val moved = if (dx == 0L && dy == 0L) shape else ShapeGeometry.translate(shape, dx, dy)
                if (rotationRad == 0.0) moved else ShapeGeometry.rotate(moved, rotationRad, 0L, 0L)
            }
        }

        private fun gap(min: Long, max: Long): Long = when {
            min <= 0L && max >= 0L -> 0L
            else -> min(abs(min), abs(max))
        }

        private fun stepShape(step: PlacementStep, shape: DrawingShape): DrawingShape = when (step) {
            PlacementStep.RotateLeft -> ShapeGeometry.quarterTurn(shape, 1, 0L, 0L)
            PlacementStep.RotateRight -> ShapeGeometry.quarterTurn(shape, -1, 0L, 0L)
            PlacementStep.MirrorHorizontal -> ShapeGeometry.mirrorAcrossVertical(shape, 0L)
            PlacementStep.MirrorVertical -> ShapeGeometry.mirrorAcrossHorizontal(shape, 0L)
            is PlacementStep.Scale -> ShapeGeometry.scale(shape, step.factor, 0L, 0L)
        }

        private fun stepPoint(step: PlacementStep, point: Vec2): Vec2 = when (step) {
            PlacementStep.RotateLeft -> Vec2(-point.y, point.x)
            PlacementStep.RotateRight -> Vec2(point.y, -point.x)
            PlacementStep.MirrorHorizontal -> Vec2(-point.x, point.y)
            PlacementStep.MirrorVertical -> Vec2(point.x, -point.y)
            is PlacementStep.Scale -> point * step.factor
        }
    }
}
