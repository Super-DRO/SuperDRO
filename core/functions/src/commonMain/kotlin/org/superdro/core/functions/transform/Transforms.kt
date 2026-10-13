package org.superdro.core.functions.transform

import kotlin.math.roundToLong
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.functions.graphics.AngleMath
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.model.AxisId
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.SubDatum

/** Gives the nm value of a µm value: `round(1000 · um)`. */
internal fun umToNm(um: Double): Long = (um * 1000.0).roundToLong()

/** The line of a flip. */
enum class FlipAxis {
    /** "Flip Across X Axis": a reflection about the line y = L. */
    AcrossX,

    /** "Flip Across Y Axis": a reflection about the line x = L. */
    AcrossY,
}

/**
 * The flip of the workspace. [lineUm] is the line
 * position L.
 *
 * | Item | Flip across X | Flip across Y |
 * |---|---|---|
 * | Sub-datum and guide | `y' = 2L − y` | `x' = 2L − x` |
 * | Image matrix | `[1 0 0; 0 −1 2L; 0 0 1]` | `[−1 0 2L; 0 1 0; 0 0 1]` |
 * | Shapes | `y' = 2·round(1000·L) − y`, `a0' = −a0` | `x' = 2·round(1000·L) − x`, `a0' = π − a0` |
 *
 * The sweeps and the bulges change sign. A result of −0.0 becomes +0.0. No guide is lost.
 */
data class FlipTransform(val axis: FlipAxis, val lineUm: Double) : WorkspaceTransform {

    init {
        require(lineUm.isFinite()) { "flip offset must be finite" }
    }

    override val isIdentity: Boolean get() = false
    override val breaksGuides: Boolean get() = false
    override val keepsAxisAlignment: Boolean get() = false

    override val imageMatrix: Matrix3
        get() = when (axis) {
            FlipAxis.AcrossX -> Matrix3(1.0, 0.0, 0.0, 0.0, -1.0, 2.0 * lineUm, 0.0, 0.0, 1.0)
            FlipAxis.AcrossY -> Matrix3(-1.0, 0.0, 2.0 * lineUm, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        }

    override fun mapPoint(subDatum: SubDatum): SubDatum = when (axis) {
        FlipAxis.AcrossX -> subDatum.copy(y = subDatum.y?.let(::reflect))
        FlipAxis.AcrossY -> subDatum.copy(x = subDatum.x?.let(::reflect))
    }

    override fun mapGuide(guide: Guide, policy: GuidePolicy): GuideMapResult {
        val flipped = when (axis) {
            FlipAxis.AcrossX -> AxisId.Y
            FlipAxis.AcrossY -> AxisId.X
        }
        return GuideMapResult.Kept(if (guide.axis == flipped) guide.copy(valueUm = reflect(guide.valueUm)) else guide)
    }

    override fun mapShape(shape: DrawingShape): DrawingShape = when (axis) {
        FlipAxis.AcrossX -> ShapeGeometry.mirrorAcrossHorizontal(shape, umToNm(lineUm))
        FlipAxis.AcrossY -> ShapeGeometry.mirrorAcrossVertical(shape, umToNm(lineUm))
    }

    private fun reflect(value: Double): Double = positiveZero(2.0 * lineUm - value)
}

/**
 * The rotation of the workspace about a pivot.
 *
 * The angle is normalized to (−180°, 180°]. A multiple of 90° uses the exact values of cos and
 * sin. A positive angle turns counterclockwise when X points right and Y points up.
 *
 * ```text
 * x' = (x − Px)·cos θ − (y − Py)·sin θ + Px
 * y' = (x − Px)·sin θ + (y − Py)·cos θ + Py
 * ```
 *
 * A row without X or without Y does not change. Guides:
 *
 * | Angle | Guide on X | Guide on Y |
 * |---|---|---|
 * | 0° or 180° | stays on X: the rotated X of (x, 0) | stays on Y: the rotated Y of (0, y) |
 * | 90° or −90° | becomes a guide on Y | becomes a guide on X |
 * | Other, [GuidePolicy.Delete] | deleted | deleted |
 * | Other, [GuidePolicy.Convert] | the 2D point of (x, 0) | the 2D point of (0, y) |
 *
 * A guide on Z does not change and does not count.
 *
 * @property angleArcSec The angle, normalized to (−648000″, 648000″].
 */
class RotateTransform(angleArcSec: Double, val pivotXUm: Double, val pivotYUm: Double) : WorkspaceTransform {

    val angleArcSec: Double

    private val quarterTurns: Int?
    private val cos: Double
    private val sin: Double

    init {
        require(angleArcSec.isFinite()) { "rotation angle must be finite" }
        require(pivotXUm.isFinite() && pivotYUm.isFinite()) { "rotation pivot must be finite" }
        this.angleArcSec = positiveZero(AngleMath.normalizeArcSec(angleArcSec))
        quarterTurns = AngleMath.quarterTurns(this.angleArcSec)
        val (c, s) = AngleMath.cosSinArcSec(this.angleArcSec)
        cos = c
        sin = s
    }

    /** The angle in degrees. */
    val angleDegrees: Double get() = AngleMath.arcSecToDegrees(angleArcSec)

    /** The label of the guide message: the angle with up to 4 decimals and `°`, for example `30°`. */
    val label: String get() = formatDecimals(angleDegrees, 4) + "°"

    override val isIdentity: Boolean get() = angleArcSec == 0.0
    override val breaksGuides: Boolean get() = quarterTurns == null
    override val keepsAxisAlignment: Boolean get() = false

    override val imageMatrix: Matrix3 get() = Matrix3.rotationAbout(cos, sin, pivotXUm, pivotYUm)

    override fun breaksGuide(guide: Guide): Boolean = quarterTurns == null && (guide.axis == AxisId.X || guide.axis == AxisId.Y)

    override fun mapPoint(subDatum: SubDatum): SubDatum {
        val x = subDatum.x ?: return subDatum
        val y = subDatum.y ?: return subDatum
        val (nx, ny) = rotate(x, y)
        return subDatum.copy(x = nx, y = ny)
    }

    override fun mapGuide(guide: Guide, policy: GuidePolicy): GuideMapResult {
        if (guide.axis != AxisId.X && guide.axis != AxisId.Y) return GuideMapResult.Kept(guide)
        val (nx, ny) = if (guide.axis == AxisId.X) rotate(guide.valueUm, 0.0) else rotate(0.0, guide.valueUm)
        return when (quarterTurns) {
            0, 2 -> GuideMapResult.Kept(guide.copy(valueUm = if (guide.axis == AxisId.X) nx else ny))
            1, 3 -> GuideMapResult.Kept(
                if (guide.axis == AxisId.X) guide.copy(axis = AxisId.Y, valueUm = ny) else guide.copy(axis = AxisId.X, valueUm = nx),
            )
            else -> when (policy) {
                GuidePolicy.Delete -> GuideMapResult.Deleted
                GuidePolicy.Convert -> GuideMapResult.ConvertedToPoint(nx, ny)
            }
        }
    }

    override fun mapShape(shape: DrawingShape): DrawingShape {
        val px = umToNm(pivotXUm)
        val py = umToNm(pivotYUm)
        val turns = quarterTurns
        return if (turns != null) {
            ShapeGeometry.quarterTurn(shape, turns, px, py)
        } else {
            ShapeGeometry.rotate(shape, AngleMath.arcSecToRad(angleArcSec), px, py)
        }
    }

    private fun rotate(x: Double, y: Double): Pair<Double, Double> {
        val dx = x - pivotXUm
        val dy = y - pivotYUm
        return positiveZero(dx * cos - dy * sin + pivotXUm) to positiveZero(dx * sin + dy * cos + pivotYUm)
    }

    override fun equals(other: Any?): Boolean =
        other is RotateTransform && other.angleArcSec == angleArcSec && other.pivotXUm == pivotXUm && other.pivotYUm == pivotYUm

    override fun hashCode(): Int = (angleArcSec.hashCode() * 31 + pivotXUm.hashCode()) * 31 + pivotYUm.hashCode()

    override fun toString(): String = "RotateTransform($label about ($pivotXUm, $pivotYUm))"

    companion object {
        /** Gives the rotation of [degrees] about (px, py). */
        fun ofDegrees(degrees: Double, pivotXUm: Double, pivotYUm: Double): RotateTransform =
            RotateTransform(AngleMath.degreesToArcSec(degrees), pivotXUm, pivotYUm)
    }
}

/**
 * The scale of the workspace about a pivot.
 *
 * ```text
 * x' = (x − Px)·s + Px
 * y' = (y − Py)·s + Py
 * z' = (z − Pz)·s + Pz      only when the scale uses Z
 * ```
 *
 * The scale uses Z only when [pivotZUm] is not null. Then a 2D sub-datum is a valid pivot
 * source for a scale without Z. A does not change. An absent coordinate stays absent. The guides
 * use the same formula. No guide is lost. The image matrix is `setScale(s, s, Px, Py)`. The
 * shapes change on X and Y only.
 *
 * @property factor The factor s = percent / 100. It must be more than 0 and finite.
 */
data class ScaleTransform(
    val factor: Double,
    val pivotXUm: Double,
    val pivotYUm: Double,
    val pivotZUm: Double? = null,
) : WorkspaceTransform {

    init {
        require(factor.isFinite() && factor > 0.0) { "scale factor must be more than 0 and finite" }
        require(pivotXUm.isFinite() && pivotYUm.isFinite() && (pivotZUm?.isFinite() ?: true)) { "scale pivot must be finite" }
    }

    /** True when the scale changes Z. */
    val usesZ: Boolean get() = pivotZUm != null

    override val isIdentity: Boolean get() = factor == 1.0
    override val breaksGuides: Boolean get() = false
    override val keepsAxisAlignment: Boolean get() = true

    override val imageMatrix: Matrix3 get() = Matrix3.scaleAbout(factor, pivotXUm, pivotYUm)

    override fun mapPoint(subDatum: SubDatum): SubDatum = subDatum.copy(
        x = subDatum.x?.let { x -> scale(x, pivotXUm) },
        y = subDatum.y?.let { y -> scale(y, pivotYUm) },
        z = subDatum.z?.let { z -> pivotZUm?.let { pz -> scale(z, pz) } ?: z },
    )

    override fun mapGuide(guide: Guide, policy: GuidePolicy): GuideMapResult = GuideMapResult.Kept(
        when (guide.axis) {
            AxisId.X -> guide.copy(valueUm = scale(guide.valueUm, pivotXUm))
            AxisId.Y -> guide.copy(valueUm = scale(guide.valueUm, pivotYUm))
            AxisId.Z -> pivotZUm?.let { pz -> guide.copy(valueUm = scale(guide.valueUm, pz)) } ?: guide
            else -> guide
        },
    )

    override fun mapShape(shape: DrawingShape): DrawingShape =
        ShapeGeometry.scale(shape, factor, umToNm(pivotXUm), umToNm(pivotYUm))

    private fun scale(value: Double, pivot: Double): Double = positiveZero((value - pivot) * factor + pivot)

    companion object {
        /** Gives the scale of [percent] (%) about the pivot. */
        fun ofPercent(percent: Double, pivotXUm: Double, pivotYUm: Double, pivotZUm: Double? = null): ScaleTransform =
            ScaleTransform(percent / 100.0, pivotXUm, pivotYUm, pivotZUm)
    }
}

/**
 * The translation of the workspace. Only "Align Absolute
 * Origin to Sub-Datum" uses it.
 *
 * ```text
 * x' = x + dx,  y' = y + dy,  z' = z + dz,  a' = a + da   (only for coordinates that exist)
 * ```
 *
 * The image matrix is `setTranslate(dx, dy)`. The shapes get `x' = x + round(1000·dx)` and
 * `y' = y + round(1000·dy)`.
 */
data class TranslateTransform(
    val dxUm: Double,
    val dyUm: Double,
    val dzUm: Double = 0.0,
    val daArcSec: Double = 0.0,
) : WorkspaceTransform {

    init {
        require(dxUm.isFinite() && dyUm.isFinite() && dzUm.isFinite() && daArcSec.isFinite()) { "translation must be finite" }
    }

    override val isIdentity: Boolean get() = dxUm == 0.0 && dyUm == 0.0 && dzUm == 0.0 && daArcSec == 0.0
    override val breaksGuides: Boolean get() = false
    override val keepsAxisAlignment: Boolean get() = true

    override val imageMatrix: Matrix3 get() = Matrix3.translation(dxUm, dyUm)

    override fun mapPoint(subDatum: SubDatum): SubDatum = subDatum.copy(
        x = subDatum.x?.let { x -> positiveZero(x + dxUm) },
        y = subDatum.y?.let { y -> positiveZero(y + dyUm) },
        z = subDatum.z?.let { z -> positiveZero(z + dzUm) },
        a = subDatum.a?.let { a -> positiveZero(a + daArcSec) },
    )

    override fun mapGuide(guide: Guide, policy: GuidePolicy): GuideMapResult = GuideMapResult.Kept(
        when (guide.axis) {
            AxisId.X -> guide.copy(valueUm = positiveZero(guide.valueUm + dxUm))
            AxisId.Y -> guide.copy(valueUm = positiveZero(guide.valueUm + dyUm))
            AxisId.Z -> guide.copy(valueUm = positiveZero(guide.valueUm + dzUm))
            else -> guide
        },
    )

    override fun mapShape(shape: DrawingShape): DrawingShape = ShapeGeometry.translate(shape, umToNm(dxUm), umToNm(dyUm))
}

/** Gives [value] with up to [decimals] decimals and no end zeros (the pattern `#.####`). */
internal fun formatDecimals(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10L }
    val scaled = (kotlin.math.abs(value) * factor).roundToLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0').trimEnd('0')
    val sign = if (value < 0.0 && scaled != 0L) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}
