package org.superdro.core.functions.patterns

import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum

/**
 * The inputs of the hole grid. Lengths are µm, angles are arc
 * seconds. A null value is an empty field.
 *
 * @property repeat1 The count along the first plane axis, 0 to 100, default 1.
 * @property step1Um The step along the first plane axis, 0 µm or more. It is required when
 *   [repeat1] is more than 1.
 * @property repeat2 The count along the second plane axis, 0 to 100, default 1.
 * @property step2Um The step along the second plane axis, as [step1Um].
 * @property angleArcSec The angle `α`, −360° to 360°, default 0.
 */
data class HoleGridInput(
    val repeat1: Int? = null,
    val step1Um: Double? = null,
    val repeat2: Int? = null,
    val step2Um: Double? = null,
    val angleArcSec: Double? = null,
)

/** The hole grid, "Linear Point Array" on a lathe. */
object HoleGrid {
    const val MIN_REPEAT: Int = 0
    const val MAX_REPEAT: Int = 100
    const val DEFAULT_REPEAT: Int = 1
    const val MIN_STEP_UM: Double = 0.0

    /** The dialog title: "Hole Grid", or "Linear Point Array" on a lathe. */
    fun title(machineType: MachineType) =
        if (machineType.isLathe) PatternTexts.TITLE_HOLE_GRID_LATHE.message() else PatternTexts.TITLE_HOLE_GRID.message()

    /**
     * The labels of the repeat and step fields for [plane]: repeat 1 and
     * step 1 use the first plane axis, repeat 2 and step 2 use the second plane axis.
     */
    fun fields(plane: Plane): GridFields = when (PatternPlanes.planeAxes(plane)) {
        AxisLetter.X to AxisLetter.Y -> GridFields(PatternField.RepeatX, PatternField.StepX, PatternField.RepeatY, PatternField.StepY)
        AxisLetter.X to AxisLetter.Z -> GridFields(PatternField.RepeatX, PatternField.StepX, PatternField.RepeatZ, PatternField.StepZ)
        else -> GridFields(PatternField.RepeatY, PatternField.StepY, PatternField.RepeatZ, PatternField.StepZ)
    }

    /**
     * Gives the grid points, row by row (`r` is the outer loop, `c` is the inner loop):
     *
     * ```text
     * LEGACY:   t = −α      STANDARD: t = +α
     * px = c × e × cos(t) − r × f × sin(t)
     * py = c × e × sin(t) + r × f × cos(t)
     * ```
     *
     * With LEGACY this is `px = c×e×cos(α) + r×f×sin(α)` and `py = −c×e×sin(α) + r×f×cos(α)`.
     * The first point (c = 0, r = 0) is at the origin.
     */
    fun points(
        repeat1: Int,
        step1Um: Double,
        repeat2: Int,
        step2Um: Double,
        angleArcSec: Double,
        convention: PatternAngleConvention,
    ): List<PointUm> {
        val turn = if (convention == PatternAngleConvention.Legacy) -angleArcSec else angleArcSec
        return buildList {
            for (row in 0 until repeat2) {
                for (column in 0 until repeat1) {
                    add(PatternAngles.rotate(PointUm(column * step1Um, row * step2Um), turn))
                }
            }
        }
    }

    /** Checks the grid fields. The first part of the result is the checked input, or null. */
    internal fun check(plane: Plane, input: HoleGridInput, checks: FieldChecks): CheckedGrid? {
        val fields = fields(plane)
        val repeat1 = checks.count(fields.repeat1, input.repeat1, MIN_REPEAT, MAX_REPEAT, DEFAULT_REPEAT)
        val step1 = checks.number(fields.step1, input.step1Um, min = MIN_STEP_UM, required = (repeat1 ?: 0) > 1)
        val repeat2 = checks.count(fields.repeat2, input.repeat2, MIN_REPEAT, MAX_REPEAT, DEFAULT_REPEAT)
        val step2 = checks.number(fields.step2, input.step2Um, min = MIN_STEP_UM, required = (repeat2 ?: 0) > 1)
        val angle = checks.number(
            PatternField.GridAngle, input.angleArcSec,
            PatternAngles.MIN_ANGLE_ARCSEC, PatternAngles.MAX_ANGLE_ARCSEC, 0.0,
        )
        if (repeat1 == 1 && repeat2 == 1) {
            checks.add(
                PatternError(
                    null,
                    PatternTexts.REPEAT_TOO_SMALL.format(fields.repeat1.label.message(), fields.repeat2.label.message()),
                ),
            )
        }
        if (checks.errors.isNotEmpty()) return null
        return CheckedGrid(repeat1!!, step1 ?: 0.0, repeat2!!, step2 ?: 0.0, angle!!)
    }

    /**
     * Checks the inputs and gives the grid group (default prefix "Grid"). The grid ignores the
     * position reference: the first hole is the origin. A repeat value of 0 gives no point.
     */
    fun create(common: PatternCommon, input: HoleGridInput): PatternResult {
        val checks = FieldChecks()
        val prefix = checks.prefix(common.prefix, PatternPrefix.DEFAULT_GRID)
        val grid = check(common.effectivePlane, input, checks)
        if (checks.errors.isNotEmpty() || grid == null) return PatternResult.Failure(checks.errors)
        val points = grid.points(common.effectiveConvention)
        return PatternResult.Success(group(common.copy(reference = PositionReference.Center), prefix!!, points))
    }
}

/** The fields of the grid for one plane. */
data class GridFields(
    val repeat1: PatternField,
    val step1: PatternField,
    val repeat2: PatternField,
    val step2: PatternField,
)

internal data class CheckedGrid(
    val repeat1: Int,
    val step1Um: Double,
    val repeat2: Int,
    val step2Um: Double,
    val angleArcSec: Double,
) {
    fun points(convention: PatternAngleConvention): List<PointUm> =
        HoleGrid.points(repeat1, step1Um, repeat2, step2Um, angleArcSec, convention)
}

/**
 * Step and repeat of selected sub-datums or of the workspace.
 *
 * The function makes one point for each grid point (outer loop) and each source sub-datum (inner
 * loop, list order). It drops the first grid point, so the copies do not repeat the source points.
 * The plane is the default plane of the machine type (`XY` for a mill, `ZX` for a lathe).
 *
 * - Mill: `px = grid.px + X`, `py = grid.py + Y`, then the origin and the `XY` map apply. Z is the
 *   Z of the sub-datum. With a part rotation or an axis alignment, the source vector (X, Y)
 *   turns first, with no pivot.
 * - Lathe: `X = X0 + grid X + sub-datum X`, `Z = Z0 + grid Z + sub-datum Z`.
 *
 * A null X, Y, or Z of a sub-datum counts as 0 in the sum.
 */
object StepAndRepeat {

    /**
     * The dialog title: "Step and Repeat <n> Sub-Datums" for a selection of [selectedCount]
     * points, or "Step and Repeat Workspace" when [selectedCount] is null.
     */
    fun title(selectedCount: Int?) = if (selectedCount == null) {
        PatternTexts.TITLE_STEP_AND_REPEAT_WORKSPACE.message()
    } else {
        PatternTexts.TITLE_STEP_AND_REPEAT.format(selectedCount)
    }

    /**
     * Checks the inputs and gives the copies (default prefix "Grid").
     *
     * @param common The common inputs. The plane and the position reference are ignored.
     * @param sources The source sub-datums in list order: the selection, or all sub-datums of the
     *   workspace.
     * @param sourceRotationArcSec The rotation of the source vector on a mill, counter-clockwise
     *   positive: the part rotation angle φ plus the axis alignment angle. 0 when no rotation is
     *   active.
     */
    fun create(
        common: PatternCommon,
        input: HoleGridInput,
        sources: List<SubDatum>,
        sourceRotationArcSec: Double = 0.0,
    ): PatternResult {
        val checks = FieldChecks()
        val prefix = checks.prefix(common.prefix, PatternPrefix.DEFAULT_GRID)
        val plane = PatternPlanes.effectivePlane(common.machineType, PatternPlanes.defaultPlane(common.machineType))
        val grid = HoleGrid.check(plane, input, checks)
        if (checks.errors.isNotEmpty() || grid == null) return PatternResult.Failure(checks.errors)
        val gridPoints = grid.points(common.effectiveConvention).drop(1)
        val origin = common.origin
        val points = buildList {
            for (gridPoint in gridPoints) {
                for (source in sources) {
                    add(copy(common.machineType, origin, gridPoint, source, sourceRotationArcSec))
                }
            }
        }
        return PatternResult.Success(PatternGroup(prefix!! + PatternPlanes.groupSuffix(common.machineType, plane), points))
    }

    private fun copy(
        machineType: MachineType,
        origin: AxisPoint,
        gridPoint: PointUm,
        source: SubDatum,
        rotationArcSec: Double,
    ): AxisPoint {
        val x = source.x ?: 0.0
        return if (machineType.isLathe) {
            val point = PointUm(gridPoint.h + x, gridPoint.v + (source.z ?: 0.0))
            PatternPlanes.map(Plane.ZX, origin, point)
        } else {
            val vector = PatternAngles.rotate(PointUm(x, source.y ?: 0.0), rotationArcSec)
            val mapped = PatternPlanes.map(Plane.XY, origin, PointUm(gridPoint.h + vector.h, gridPoint.v + vector.v))
            mapped.copy(z = source.z)
        }
    }
}
