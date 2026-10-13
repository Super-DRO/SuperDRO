package org.superdro.core.ui.readout

import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Workspace
import org.superdro.core.model.CompoundProjection
import org.superdro.core.model.HeadTilt

/** A value that a [WorkspaceDelta] sets. The wrapper lets a delta set a null value. */
data class Changed<T>(val value: T)

/**
 * The fields of a workspace that one action of the readout changed: the axis states, the active
 * sub-datum, the tool values of a compensation, and the compound slide and head tilt
 * projections. A delta writes only these
 * fields, in one write transaction, on top of the current workspace of the store. The undo of an
 * action is the delta of the old values.
 */
data class WorkspaceDelta(
    val axisStates: Map<AxisId, AxisState> = emptyMap(),
    val activeSubDatumId: Changed<String?>? = null,
    val activeToolId: Changed<String?>? = null,
    val toolXUm: Changed<Double>? = null,
    val toolZUm: Changed<Double>? = null,
    val toothCount: Changed<Int>? = null,
    val compoundProjection: Changed<CompoundProjection>? = null,
    val headTilt: Changed<HeadTilt>? = null,
) {
    /** True when the delta changes nothing. */
    val isEmpty: Boolean
        get() = axisStates.isEmpty() && activeSubDatumId == null && activeToolId == null &&
            toolXUm == null && toolZUm == null && toothCount == null && compoundProjection == null && headTilt == null

    /** Gives [workspace] with the fields of this delta. */
    fun applyTo(workspace: Workspace): Workspace = workspace.copy(
        axisStates = workspace.axisStates + axisStates,
        activeSubDatumId = if (activeSubDatumId != null) activeSubDatumId.value else workspace.activeSubDatumId,
        activeToolId = if (activeToolId != null) activeToolId.value else workspace.activeToolId,
        toolXUm = toolXUm?.value ?: workspace.toolXUm,
        toolZUm = toolZUm?.value ?: workspace.toolZUm,
        toothCount = toothCount?.value ?: workspace.toothCount,
        compoundProjection = compoundProjection?.value ?: workspace.compoundProjection,
        headTilt = headTilt?.value ?: workspace.headTilt,
    )

    companion object {
        /** Gives the change from [before] to [after]: the forward delta and the undo delta. */
        fun between(before: Workspace, after: Workspace): Pair<WorkspaceDelta, WorkspaceDelta> {
            val forwardStates = LinkedHashMap<AxisId, AxisState>()
            val backwardStates = LinkedHashMap<AxisId, AxisState>()
            (before.axisStates.keys + after.axisStates.keys).forEach { axis ->
                val old = before.axisStates[axis] ?: AxisState()
                val new = after.axisStates[axis] ?: AxisState()
                if (old != new) {
                    forwardStates[axis] = new
                    backwardStates[axis] = old
                }
            }
            fun <T> change(old: T, new: T): Pair<Changed<T>?, Changed<T>?> =
                if (old == new) null to null else Changed(new) to Changed(old)
            val subDatum = change(before.activeSubDatumId, after.activeSubDatumId)
            val tool = change(before.activeToolId, after.activeToolId)
            val toolX = change(before.toolXUm, after.toolXUm)
            val toolZ = change(before.toolZUm, after.toolZUm)
            val teeth = change(before.toothCount, after.toothCount)
            val compound = change(before.compoundProjection, after.compoundProjection)
            val tilt = change(before.headTilt, after.headTilt)
            return WorkspaceDelta(forwardStates, subDatum.first, tool.first, toolX.first, toolZ.first, teeth.first, compound.first, tilt.first) to
                WorkspaceDelta(backwardStates, subDatum.second, tool.second, toolX.second, toolZ.second, teeth.second, compound.second, tilt.second)
        }

        /**
         * True when the change from [before] to [after] changes an offset: an absolute, incremental,
         * or tool offset of an axis, the active sub-datum, a tool value, or the on state of a
         * projection (a projection changes the axis values as an offset does). A change of the
         * mode, the unit, the diameter mode, or the divisions is not an offset change: it gets no
         * undo.
         */
        fun changesOffsets(before: Workspace, after: Workspace): Boolean {
            val axes = before.axisStates.keys + after.axisStates.keys
            val axisOffsets = axes.any { axis ->
                val old = before.axisStates[axis] ?: AxisState()
                val new = after.axisStates[axis] ?: AxisState()
                old.absoluteOffset != new.absoluteOffset || old.incrementalOffset != new.incrementalOffset ||
                    old.toolOffset != new.toolOffset || old.toolOffsetActive != new.toolOffsetActive
            }
            return axisOffsets || before.activeSubDatumId != after.activeSubDatumId ||
                before.activeToolId != after.activeToolId || before.toolXUm != after.toolXUm ||
                before.toolZUm != after.toolZUm || before.toothCount != after.toothCount ||
                before.compoundProjection.active != after.compoundProjection.active ||
                before.headTilt.active != after.headTilt.active
        }
    }
}
