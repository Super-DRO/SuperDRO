package org.superdro.core.functions.transform

import kotlin.math.abs
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.operations.MachineDatums

/** The shift of one axis: new absolute value − current absolute value. µm, or arc seconds for `Angular`. */
data class AxisShift(val axis: AxisId, val shift: Double)

/**
 * The no-op test of the origin commands. A command is a no-op
 * when each linear shift is less than 0.5 µm and the angular shift is less than 0.5″.
 */
object NoOpLimits {

    /** The linear limit in µm: the limit of the machine datums of `core:readout`. */
    const val LINEAR_UM: Double = MachineDatums.LINEAR_NO_OP_UM

    /** The angular limit in arc seconds: the limit of the machine datums of `core:readout`. */
    const val ANGULAR_ARCSEC: Double = MachineDatums.ANGULAR_NO_OP_ARCSEC

    /** True when each shift of [shifts] is below its limit. */
    fun isNoOp(shifts: List<AxisShift>): Boolean = shifts.all { shift ->
        val limit = if (shift.axis == AxisId.Angular) ANGULAR_ARCSEC else LINEAR_UM
        abs(shift.shift) < limit
    }
}

/**
 * The result of "Align Absolute Origin to Sub-Datum".
 *
 * @property transform The translation of all stored geometry. The store writes it first.
 * @property newAbsolute The new absolute value of each axis. The readout sets them after a good write.
 */
data class AlignOriginResult(val transform: TranslateTransform, val newAbsolute: Map<AxisId, Double>)

/** The result of "Align Sub-Datum to Spindle". */
sealed interface SpindleAlignResult {
    /** Each shift is below the no-op limit. */
    data object NoOp : SpindleAlignResult

    /** The new absolute values and the shifts of the confirmation. The stored rows do not change. */
    data class Apply(val newAbsolute: Map<AxisId, Double>, val shifts: List<AxisShift>) : SpindleAlignResult
}

/**
 * The origin commands of a sub-datum.
 * The positions are in the workspace frame. The axes are in the order X, Y, Z, A.
 */
object OriginCommands {

    /** The axes of the origin commands, in order. */
    val AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

    /**
     * Gives "Align Absolute Origin to Sub-Datum". The translation is
     * `(−S_x, −S_y, −S_z, −S_a)`, with 0 for an axis that is off or that S does not have. After
     * a good write, the new absolute value is `Abs_c − S_c`. This applies to X when S has X. It
     * applies to Y, Z, and A when S has the value and the axis is on. S becomes (0, 0, 0). The
     * physical positions do not change.
     *
     * Example: S = (25, 40, −5) mm and the position (60, 70, 10) mm give the translation
     * (−25, −40, +5) mm. The new absolute position is (35, 30, 15) mm.
     *
     * @param enabledAxes The axes that are on. X always counts.
     * @param absolute The current absolute value of each axis.
     */
    fun alignAbsoluteOriginToSubDatum(
        subDatum: SubDatum,
        enabledAxes: Set<AxisId>,
        absolute: Map<AxisId, Double>,
    ): AlignOriginResult {
        val values = AXES.associateWith { axis -> usedValue(subDatum, axis, enabledAxes) }
        val transform = TranslateTransform(
            dxUm = positiveZero(-(values[AxisId.X] ?: 0.0)),
            dyUm = positiveZero(-(values[AxisId.Y] ?: 0.0)),
            dzUm = positiveZero(-(values[AxisId.Z] ?: 0.0)),
            daArcSec = positiveZero(-(values[AxisId.Angular] ?: 0.0)),
        )
        val newAbsolute = LinkedHashMap<AxisId, Double>()
        for (axis in AXES) {
            val value = values[axis] ?: continue
            val current = absolute[axis] ?: continue
            newAbsolute[axis] = positiveZero(current - value)
        }
        return AlignOriginResult(transform, newAbsolute)
    }

    /**
     * Gives "Align Sub-Datum to Spindle" (on a lathe "Align Sub-Datum to Tool"). The
     * function uses each axis c that S has. Y, Z, and A count only when the axis is on. For each
     * axis, `shift_c = S_c − Abs_c` and `Abs_c_new = S_c`. The stored rows do not change.
     *
     * Example: S = (25, 40) mm and the position (60, 70) mm give the shift (−35, −30) mm.
     *
     * @param onlyAxes The lathe buttons "(Z Only)" and "(X Only)" give one axis. Null means all axes.
     */
    fun alignSubDatumToSpindle(
        subDatum: SubDatum,
        enabledAxes: Set<AxisId>,
        absolute: Map<AxisId, Double>,
        onlyAxes: Set<AxisId>? = null,
    ): SpindleAlignResult {
        val newAbsolute = LinkedHashMap<AxisId, Double>()
        val shifts = ArrayList<AxisShift>()
        for (axis in AXES) {
            if (onlyAxes != null && axis !in onlyAxes) continue
            val value = usedValue(subDatum, axis, enabledAxes) ?: continue
            val current = absolute[axis] ?: continue
            newAbsolute[axis] = value
            shifts += AxisShift(axis, positiveZero(value - current))
        }
        if (NoOpLimits.isNoOp(shifts)) return SpindleAlignResult.NoOp
        return SpindleAlignResult.Apply(newAbsolute, shifts)
    }

    /** Gives the value of [axis] of [subDatum] when it counts: X always, Y, Z, and A when the axis is on. */
    private fun usedValue(subDatum: SubDatum, axis: AxisId, enabledAxes: Set<AxisId>): Double? {
        if (axis != AxisId.X && axis !in enabledAxes) return null
        return PlaneAxes.valueOf(axis, subDatum)
    }
}
