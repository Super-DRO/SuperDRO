package org.superdro.core.functions.patterns

import org.superdro.core.model.MachineType

/**
 * The start and the end of one axis of the equidistant array, in µm. A null value is a field
 * that is empty or not valid.
 */
data class AxisRange(val startUm: Double?, val endUm: Double?)

/**
 * The equidistant array. It has no prefix field, no plane, and
 * no position reference. The group name is always "Grid".
 */
object EquidistantArray {
    const val MIN_COUNT: Int = 3
    const val GROUP_NAME: String = PatternPrefix.DEFAULT_GRID

    /** The dialog title: "Equidistant Hole Array", or "Equidistant Point Array" on a lathe. */
    fun title(machineType: MachineType) =
        if (machineType.isLathe) PatternTexts.TITLE_EQUIDISTANT_LATHE.message() else PatternTexts.TITLE_EQUIDISTANT.message()

    /**
     * The axes of the array: X if enabled; Y if enabled and not a
     * lathe; Z if enabled and 3D sub-datums are on.
     */
    fun includedAxes(
        machineType: MachineType,
        xEnabled: Boolean,
        yEnabled: Boolean,
        zEnabled: Boolean,
        use3dSubDatums: Boolean,
    ): AxisSwitches = AxisSwitches(
        x = xEnabled,
        y = yEnabled && !machineType.isLathe,
        z = zEnabled && use3dSubDatums,
    )

    /**
     * Checks the inputs and gives the points:
     *
     * ```text
     * step_k = (end_k − start_k) / (n − 1)
     * P_i.k  = start_k + i × step_k        for i = 0 .. n − 1
     * ```
     *
     * An axis that is not included gives null.
     *
     * @param count The number of holes, or null when the field is empty or not an integer.
     */
    fun create(count: Int?, axes: AxisSwitches, x: AxisRange, y: AxisRange, z: AxisRange): PatternResult {
        val errors = mutableListOf<PatternError>()
        when {
            count == null -> errors += PatternError(PatternField.NumberOfHoles, PatternTexts.HOLE_COUNT_NOT_VALID.message())
            count < MIN_COUNT -> errors += PatternError(PatternField.NumberOfHoles, PatternTexts.HOLE_COUNT_TOO_SMALL.message())
        }
        val ranges = listOf(
            Triple(axes.x, x, AxisLetter.X),
            Triple(axes.y, y, AxisLetter.Y),
            Triple(axes.z, z, AxisLetter.Z),
        )
        val startFields = listOf(PatternField.StartX, PatternField.StartY, PatternField.StartZ)
        val endFields = listOf(PatternField.EndX, PatternField.EndY, PatternField.EndZ)
        ranges.forEachIndexed { index, (included, range, letter) ->
            if (!included) return@forEachIndexed
            if (range.startUm == null || range.startUm.isNaN()) {
                errors += PatternError(startFields[index], PatternTexts.START_NOT_VALID.format(letter.text))
            }
            if (range.endUm == null || range.endUm.isNaN()) {
                errors += PatternError(endFields[index], PatternTexts.END_NOT_VALID.format(letter.text))
            }
        }
        if (errors.isNotEmpty()) return PatternResult.Failure(errors)
        val n = count!!
        fun values(included: Boolean, range: AxisRange): List<Double?> {
            if (!included) return List(n) { null }
            val start = range.startUm!!
            val step = (range.endUm!! - start) / (n - 1)
            return List(n) { index -> start + index * step }
        }
        val xs = values(axes.x, x)
        val ys = values(axes.y, y)
        val zs = values(axes.z, z)
        val points = List(n) { index -> AxisPoint(xs[index], ys[index], zs[index]) }
        return PatternResult.Success(PatternGroup(GROUP_NAME, points))
    }
}
