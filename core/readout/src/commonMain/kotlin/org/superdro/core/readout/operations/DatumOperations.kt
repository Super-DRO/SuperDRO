package org.superdro.core.readout.operations

import kotlin.math.abs
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineDatum
import org.superdro.core.readout.ReadoutFrame

/**
 * The sub-datum activation and "Set origin at a sub-datum".
 *
 * Activation:
 * 1. Clear `S` on X, Y, and Angular, and on Z if the machine is a lathe or 3D sub-datums are on.
 *    Clear means `S = 0`, `S` not active, and `I` in use again if `rel`.
 * 2. For each coordinate `c` of the sub-datum on these axes: `rel = true`, `I` not in use,
 *    `S = −c`, `S` active.
 *
 * Thus the axis shows `abs − c`. Example: sub-datum X = 25400 µm, `abs` = 30000 µm: shown 4600 µm
 * = "0.1811" in. The workspace keeps only the active sub-datum ID: [ReadoutFrame.subDatumOffsets]
 * gives `S` from it.
 */
object SubDatumOperations {

    /** Activates the sub-datum [subDatumId] of the workspace of [frame]. An unknown ID changes nothing. */
    fun activate(frame: ReadoutFrame, subDatumId: String): OperationResult = AxisOperations.result(frame) { base ->
        val subDatum = base.subDatums.firstOrNull { point -> point.id == subDatumId } ?: return@result base
        var workspace = base.copy(activeSubDatumId = subDatum.id)
        val activated = frame.with(workspace)
        activated.subDatumOffsets.keys.forEach { axis -> workspace = workspace.updateAxis(axis) { state -> state.copy(relative = true) } }
        workspace
    }

    /** Makes the active sub-datum not active. */
    fun deactivate(frame: ReadoutFrame): OperationResult = AxisOperations.result(frame) { base -> base.copy(activeSubDatumId = null) }

    /**
     * "Set origin at a sub-datum": for each coordinate `c` (X always; Y, Z, Angular when enabled):
     * `L(axis, abs − c)`. The absolute origin moves to the point. A disabled axis does not change.
     * An unknown ID changes nothing.
     */
    fun setOriginAt(frame: ReadoutFrame, subDatumId: String): OperationResult = AxisOperations.result(frame) { base ->
        val subDatum = base.subDatums.firstOrNull { point -> point.id == subDatumId } ?: return@result base
        val current = frame.with(base)
        val point = current.shownSubDatumPoint(subDatum)
        val coordinates = linkedMapOf(AxisId.X to point.x, AxisId.Y to point.y, AxisId.Z to subDatum.z, AxisId.Angular to subDatum.a)
        val targets = LinkedHashMap<AxisId, Double>()
        coordinates.forEach { (axis, coordinate) ->
            if (coordinate != null && current.enabled(axis) && current.reading(axis).hasValue) {
                targets[axis] = current.reading(axis).absolute - coordinate
            }
        }
        var workspace = AxisOperations.setAbsoluteTargets(current, targets)
        targets.keys.forEach { axis -> workspace = workspace.updateAxis(axis) { state -> state.copy(relative = false) } }
        workspace
    }
}

/**
 * The plan of "Set Absolute Origin to Machine Datum".
 */
sealed interface MachineDatumPlan {

    /** An axis that the datum stores has no finite position: "Position Not Available". */
    data class PositionNotAvailable(val axes: Set<AxisId>) : MachineDatumPlan

    /**
     * The apply.
     *
     * @property shifts The shift of each axis: the new absolute value − the current absolute
     *   value (µm or arc seconds). The confirmation lists them.
     * @property noOp True when each linear shift is less than 0.5 µm and the angular shift is
     *   less than 0.5″: "nothing to do".
     * @property result The new workspace with the undo snapshot.
     */
    data class Apply(val shifts: Map<AxisId, Double>, val noOp: Boolean, val result: OperationResult) : MachineDatumPlan
}

/**
 * The machine position and the machine datums. This is the
 * one implementation of the machine datums: the presenters of `core:ui` use it.
 *
 * - Machine position: the scale position `R` of the axis without the absolute offset. SuperDRO
 *   also leaves out the tool offset and the instrument offset.
 * - Apply: the new absolute offset is `−D` for each axis that the datum stores, so the absolute
 *   position is `M − D` (with the tool and instrument offsets in use on top). After the apply,
 *   the datum position reads 0.
 * - No-op test: each linear shift less than 0.5 µm, the angular shift less than 0.5″.
 *
 * Example: datum (152.400, 76.200, −12.700) mm, machine position (160, 80, 0) mm: absolute
 * (7.600, 3.800, 12.700) mm.
 */
object MachineDatums {

    /** The linear no-op limit. */
    const val LINEAR_NO_OP_UM: Double = 0.5

    /** The angular no-op limit. */
    const val ANGULAR_NO_OP_ARCSEC: Double = 0.5

    /** Gives the machine position of [axis]: `R`. NaN when the axis has no value. */
    fun machinePosition(frame: ReadoutFrame, axis: AxisId): Double = frame.r(axis)

    /**
     * Gives a new datum at the current machine position, or null when an axis has no finite
     * position: X always, Y when enabled and the machine is not a lathe, Z when enabled, and A
     * when the angular axis is enabled.
     */
    fun capture(frame: ReadoutFrame, name: String): MachineDatum? {
        val x = machinePosition(frame, AxisId.X)
        val y = if (frame.enabled(AxisId.Y) && !frame.profile.machineType.isLathe) machinePosition(frame, AxisId.Y) else null
        val z = if (frame.enabled(AxisId.Z)) machinePosition(frame, AxisId.Z) else null
        val a = if (frame.enabled(AxisId.Angular)) machinePosition(frame, AxisId.Angular) else null
        if (listOfNotNull(x, y, z, a).any { value -> !value.isFinite() }) return null
        return MachineDatum(name = name, x = x, y = y, z = z, a = a)
    }

    /**
     * Gives the plan to set the absolute origin of [frame] to [datum]. The apply uses X when the
     * datum stores it, and Y, Z, and A when the datum stores them and the axis is enabled.
     */
    fun plan(frame: ReadoutFrame, datum: MachineDatum): MachineDatumPlan {
        val stored = LinkedHashMap<AxisId, Double>()
        datum.x?.let { value -> stored[AxisId.X] = value }
        listOf(AxisId.Y to datum.y, AxisId.Z to datum.z, AxisId.Angular to datum.a).forEach { (axis, value) ->
            if (value != null && frame.enabled(axis)) stored[axis] = value
        }
        val missing = stored.keys.filterTo(LinkedHashSet()) { axis -> !frame.r(axis).isFinite() }
        if (missing.isNotEmpty()) return MachineDatumPlan.PositionNotAvailable(missing)
        val shifts = LinkedHashMap<AxisId, Double>()
        stored.forEach { (axis, value) -> shifts[axis] = -value - frame.state(axis).absoluteOffset }
        val noOp = shifts.all { (axis, shift) ->
            abs(shift) < if (axis == AxisId.Angular) ANGULAR_NO_OP_ARCSEC else LINEAR_NO_OP_UM
        }
        val result = AxisOperations.result(frame) { base ->
            var workspace = base
            stored.forEach { (axis, value) -> workspace = workspace.updateAxis(axis) { state -> state.copy(absoluteOffset = -value, referenced = true) } }
            workspace
        }
        return MachineDatumPlan.Apply(shifts, noOp, result)
    }

    /**
     * Gives the name of a quick capture: `Datum <n>`. The value n starts at 1 and
     * increments while a datum with the name `datum <n>` exists (the compare ignores case).
     */
    fun nextQuickCaptureName(existing: List<MachineDatum>): String {
        val names = existing.map { datum -> datum.name.lowercase() }.toSet()
        var n = 1
        while ("datum $n" in names) n++
        return "Datum $n"
    }

    /** Gives [datums] in the natural order of their names: "Datum 2" comes before "Datum 10". */
    fun sortByName(datums: List<MachineDatum>): List<MachineDatum> = datums.sortedWith(compareBy(NaturalOrder) { datum -> datum.name })
}

/**
 * The natural order of names: the digit runs compare as numbers, the other parts compare with
 * no case. Equal names in this order then compare by their text.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val partsA = split(a)
        val partsB = split(b)
        for (index in 0 until minOf(partsA.size, partsB.size)) {
            val pa = partsA[index]
            val pb = partsB[index]
            val digitsA = pa[0].isDigit()
            val digitsB = pb[0].isDigit()
            val result = when {
                digitsA && digitsB -> compareNumbers(pa, pb)
                digitsA -> -1
                digitsB -> 1
                else -> pa.lowercase().compareTo(pb.lowercase())
            }
            if (result != 0) return result
        }
        if (partsA.size != partsB.size) return partsA.size.compareTo(partsB.size)
        return a.compareTo(b)
    }

    private fun compareNumbers(a: String, b: String): Int {
        val ta = a.trimStart('0')
        val tb = b.trimStart('0')
        if (ta.length != tb.length) return ta.length.compareTo(tb.length)
        val result = ta.compareTo(tb)
        if (result != 0) return result
        return a.length.compareTo(b.length)
    }

    private fun split(text: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        for (index in 1..text.length) {
            if (index == text.length || text[index].isDigit() != text[index - 1].isDigit()) {
                if (index > start) parts += text.substring(start, index)
                start = index
            }
        }
        return parts
    }
}

/**
 * The caliper "Copy to X". The caliper has its own state.
 *
 * ```text
 * factor = X diameter mode ? 1.0 : 0.5
 * value  = caliper shown value (µm) × factor
 * X.i(value) if X is incremental, else X.h(value)
 * ```
 *
 * Example: the caliper shows 25.000 mm. X is in radius mode and absolute. X becomes 12.500 mm.
 */
object CaliperOperations {

    /** Copies the caliper value to X. Nothing happens when the caliper has no value. */
    fun copyToX(frame: ReadoutFrame): OperationResult {
        val caliper = frame.reading(AxisId.Caliper)
        if (!caliper.hasValue) return OperationResult(frame.workspace, frame.hold, null)
        val diameter = frame.postProcessor(AxisId.X) == org.superdro.core.readout.PostProcessor.Diameter
        val value = caliper.shown * if (diameter) 1.0 else 0.5
        return if (frame.reading(AxisId.X).incremental) {
            AxisOperations.setIncremental(frame, AxisId.X, value)
        } else {
            AxisOperations.setAbsolute(frame, AxisId.X, value)
        }
    }
}
