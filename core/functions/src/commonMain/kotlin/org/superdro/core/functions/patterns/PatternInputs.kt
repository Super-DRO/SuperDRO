package org.superdro.core.functions.patterns

import org.superdro.core.functions.PointUm
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane

/**
 * A point with an optional X, Y, and Z, in µm. A null coordinate is an axis that the point does
 * not have. A point with no Z is a 2D sub-datum.
 */
data class AxisPoint(val x: Double? = null, val y: Double? = null, val z: Double? = null)

/** The axis switches of a pattern dialog: the axes that the new points get. */
data class AxisSwitches(val x: Boolean, val y: Boolean, val z: Boolean) {

    /** Gives [point] with null on each axis that is off. */
    fun filter(point: AxisPoint): AxisPoint = AxisPoint(
        x = point.x.takeIf { x },
        y = point.y.takeIf { y },
        z = point.z.takeIf { z },
    )
}

/** The position source of a pattern dialog. */
enum class PositionSource {
    /** The default: the absolute position of each enabled axis, with the tool offset. */
    Current,

    /** 0 for each enabled axis. */
    Zero,

    /** The X, Y, and Z fields. A stored sub-datum can fill them. */
    Custom;

    /**
     * Gives the origin of the pattern.
     *
     * @param switches The axis switches of the dialog. An axis that is off gives no coordinate.
     * @param current The absolute position of each axis, with the tool offset. A disabled machine
     *   axis has null.
     * @param custom The values of the custom fields.
     */
    fun origin(switches: AxisSwitches, current: AxisPoint, custom: AxisPoint = AxisPoint()): AxisPoint = when (this) {
        Current -> switches.filter(current)
        Zero -> switches.filter(AxisPoint(0.0, 0.0, 0.0))
        Custom -> switches.filter(custom)
    }
}

/**
 * The position reference of the hole circle, the arbitrary hole circle, and the arc.
 */
enum class PositionReference {
    /** The default: the origin is the center of the circle. */
    Center,

    /** "First hole" or "Start of cut": the first point is at the origin. */
    FirstPoint,
}

/** The projection planes and the plane map of the patterns. */
object PatternPlanes {

    /** The plane of a pattern. A lathe always uses `ZX`. A mill uses `XY`, `XZ`, or `YZ`, default `XY`. */
    fun effectivePlane(machineType: MachineType, selected: Plane = Plane.XY): Plane = when {
        machineType.isLathe -> Plane.ZX
        selected == Plane.ZX -> Plane.XY
        else -> selected
    }

    /**
     * The default plane of a machine type for the group suffix: `XZ` for a lathe, `XY` for a mill.
     * A lathe pattern uses `ZX`, so it always gets the suffix.
     */
    fun defaultPlane(machineType: MachineType): Plane = if (machineType.isLathe) Plane.XZ else Plane.XY

    /** Gives the suffix `" (<plane>)"` of the group name, or an empty text for the default plane. */
    fun groupSuffix(machineType: MachineType, plane: Plane): String =
        if (plane == defaultPlane(machineType)) "" else " (${plane.name})"

    /**
     * The axis switches when the plane changes. The two axes of the
     * plane are on. The third axis is on when 3D sub-datums are on. A lathe has no Y.
     */
    fun defaultSwitches(plane: Plane, use3dSubDatums: Boolean): AxisSwitches = when (plane) {
        Plane.ZX -> AxisSwitches(x = true, y = false, z = true)
        Plane.XY -> AxisSwitches(x = true, y = true, z = use3dSubDatums)
        Plane.XZ -> AxisSwitches(x = true, y = use3dSubDatums, z = true)
        Plane.YZ -> AxisSwitches(x = use3dSubDatums, y = true, z = true)
    }

    /**
     * The plane map: adds the origin to the 2D point `(px, py)`. A null
     * origin coordinate gives a null coordinate.
     *
     * | Plane | X | Y | Z |
     * |---|---|---|---|
     * | `XY` | X0 + px | Y0 + py | Z0 |
     * | `XZ` | X0 + px | Y0 | Z0 + py |
     * | `YZ` | X0 | Y0 + px | Z0 + py |
     * | `ZX` | X0 + px | Y0 | Z0 + py |
     */
    fun map(plane: Plane, origin: AxisPoint, point: PointUm): AxisPoint = when (plane) {
        Plane.XY -> AxisPoint(origin.x?.plus(point.h), origin.y?.plus(point.v), origin.z)
        Plane.XZ, Plane.ZX -> AxisPoint(origin.x?.plus(point.h), origin.y, origin.z?.plus(point.v))
        Plane.YZ -> AxisPoint(origin.x, origin.y?.plus(point.h), origin.z?.plus(point.v))
    }

    /** The axis letters of the two plane axes: (px axis, py axis). */
    fun planeAxes(plane: Plane): Pair<AxisLetter, AxisLetter> = when (plane) {
        Plane.XY -> AxisLetter.X to AxisLetter.Y
        Plane.XZ, Plane.ZX -> AxisLetter.X to AxisLetter.Z
        Plane.YZ -> AxisLetter.Y to AxisLetter.Z
    }
}

/**
 * The position reference rule: [PositionReference.FirstPoint] subtracts the first point from
 * all points.
 */
fun List<PointUm>.withReference(reference: PositionReference): List<PointUm> {
    if (reference == PositionReference.Center || isEmpty()) return this
    val first = first()
    return map { point -> PointUm(point.h - first.h, point.v - first.v) }
}

/** The sub-datum prefix rule of the pattern dialogs. */
object PatternPrefix {
    const val MIN_LENGTH: Int = 3
    const val MAX_LENGTH: Int = 16

    const val DEFAULT_CIRCLE: String = "Circle"
    const val DEFAULT_GRID: String = "Grid"
    const val DEFAULT_ARC: String = "Arc"

    /**
     * Gives the prefix, or an error. An empty prefix (also only white space) takes [default].
     * A prefix has 3 to 16 characters after the white space at the two ends is removed.
     */
    fun resolve(text: String, default: String): PrefixResult {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> PrefixResult.Valid(default)
            trimmed.length < MIN_LENGTH -> PrefixResult.Invalid(
                PatternError(PatternField.Prefix, PatternTexts.TEXT_TOO_SHORT.format(PatternField.Prefix.label.message())),
            )
            trimmed.length > MAX_LENGTH -> PrefixResult.Invalid(
                PatternError(PatternField.Prefix, PatternTexts.TEXT_TOO_LONG.format(PatternField.Prefix.label.message())),
            )
            else -> PrefixResult.Valid(trimmed)
        }
    }
}

/** The result of [PatternPrefix.resolve]. */
sealed interface PrefixResult {
    data class Valid(val prefix: String) : PrefixResult
    data class Invalid(val error: PatternError) : PrefixResult
}

/**
 * The inputs that all pattern dialogs except the equidistant array have.
 *
 * @property prefix The typed prefix. An empty text gives the default of the dialog.
 * @property plane The selected plane. [PatternPlanes.effectivePlane] gives the plane that applies.
 * @property origin The origin from [PositionSource.origin].
 * @property reference The position reference. The hole grid ignores it.
 * @property machineType The machine type. A lathe always uses the plane `ZX` and the LEGACY angles.
 * @property convention The pattern angle convention of the machine profile.
 */
data class PatternCommon(
    val prefix: String = "",
    val plane: Plane = Plane.XY,
    val origin: AxisPoint = AxisPoint(0.0, 0.0, null),
    val reference: PositionReference = PositionReference.Center,
    val machineType: MachineType = MachineType.VerticalMill,
    val convention: PatternAngleConvention = PatternAngleConvention.Standard,
) {
    /** The plane that applies. */
    val effectivePlane: Plane get() = PatternPlanes.effectivePlane(machineType, plane)

    /** The angle convention that applies: a lathe always uses LEGACY. */
    val effectiveConvention: PatternAngleConvention get() = PatternAngles.convention(machineType, convention)
}

/**
 * The result of a pattern: the group name and the points. Each point has the full value of
 * each field. [PatternBatch] gives the names, the group, and the tag.
 */
data class PatternGroup(val groupName: String, val points: List<AxisPoint>)

/** The result of a pattern function. */
sealed interface PatternResult {
    data class Success(val group: PatternGroup) : PatternResult

    data class Failure(val errors: List<PatternError>) : PatternResult {
        /** The English error text of the dialog. */
        val summary: String get() = PatternError.summary(errors)
    }
}

/** Collects the field errors of a dialog, in field order. */
internal class FieldChecks {
    val errors = mutableListOf<PatternError>()

    fun prefix(text: String, default: String): String? = when (val result = PatternPrefix.resolve(text, default)) {
        is PrefixResult.Valid -> result.prefix
        is PrefixResult.Invalid -> {
            errors += result.error
            null
        }
    }

    /**
     * Checks a number field. An empty field (null) takes [default]. With no default, an empty
     * field gives "value is required" only when [required] is true.
     */
    fun number(
        field: PatternField,
        value: Double?,
        min: Double? = null,
        max: Double? = null,
        default: Double? = null,
        required: Boolean = true,
    ): Double? {
        val resolved = value ?: default
        if (resolved == null) {
            if (required) errors += PatternError(field, PatternTexts.VALUE_REQUIRED.format(field.label.message()))
            return null
        }
        if (resolved.isNaN()) {
            errors += PatternError(field, PatternTexts.VALUE_REQUIRED.format(field.label.message()))
            return null
        }
        if (min != null && resolved < min) {
            errors += PatternError(field, PatternTexts.VALUE_TOO_SMALL.format(field.label.message()))
            return null
        }
        if (max != null && resolved > max) {
            errors += PatternError(field, PatternTexts.VALUE_TOO_LARGE.format(field.label.message()))
            return null
        }
        return resolved
    }

    /** Checks an integer field, as [number]. */
    fun count(field: PatternField, value: Int?, min: Int, max: Int, default: Int? = null): Int? =
        number(field, value?.toDouble(), min.toDouble(), max.toDouble(), default?.toDouble())?.toInt()

    fun add(error: PatternError) {
        errors += error
    }
}
