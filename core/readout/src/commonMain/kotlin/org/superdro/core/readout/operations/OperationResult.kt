package org.superdro.core.readout.operations

import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutMessage

/**
 * The offsets that an action changed, for the undo ("Undo" for 10 s in the snackbar).
 * The snapshot holds the old state of each changed axis and the old active sub-datum.
 *
 * @property axisStates The state of each changed axis before the action.
 * @property restoresSubDatum True when the action changed the active sub-datum.
 * @property activeSubDatumId The active sub-datum before the action.
 */
data class UndoSnapshot(
    val axisStates: Map<AxisId, AxisState>,
    val restoresSubDatum: Boolean,
    val activeSubDatumId: String?,
) {
    /** Gives [workspace] with the old offsets of this snapshot. The other values stay. */
    fun restore(workspace: Workspace): Workspace = workspace.copy(
        axisStates = workspace.axisStates + axisStates,
        activeSubDatumId = if (restoresSubDatum) activeSubDatumId else workspace.activeSubDatumId,
    )

    companion object {
        /** Gives the snapshot of the changes from [before] to [after], or null when nothing changed. */
        fun between(before: Workspace, after: Workspace): UndoSnapshot? {
            val changed = LinkedHashMap<AxisId, AxisState>()
            (before.axisStates.keys + after.axisStates.keys).forEach { axis ->
                val old = before.axisStates[axis]
                if (old != after.axisStates[axis] && old != null) changed[axis] = old
            }
            val subDatumChanged = before.activeSubDatumId != after.activeSubDatumId
            if (changed.isEmpty() && !subDatumChanged) return null
            return UndoSnapshot(changed, subDatumChanged, before.activeSubDatumId)
        }
    }
}

/** A request of an action to the UI. */
sealed interface OperationRequest {
    /** Open the keypad sheet of [axis]. */
    data class OpenKeypad(val axis: AxisId) : OperationRequest

    /** Open the divisions sheet of the angular axis. */
    data object OpenDivisionsSheet : OperationRequest

    /** Open the preset sheet of the tachometer with [presets]. The list starts with 0. */
    data class OpenPresetSheet(val presets: List<Double>) : OperationRequest

    /** Show [message]. */
    data class ShowMessage(val message: ReadoutMessage) : OperationRequest
}

/**
 * The result of an action on the readout. The actions keep no state: they give the new
 * workspace and the new hold values. The caller stores the workspace in one write transaction.
 *
 * @property undo The offsets before the action, or null when no offset changed.
 */
data class OperationResult(
    val workspace: Workspace,
    val hold: Map<AxisId, Double>,
    val undo: UndoSnapshot?,
    val request: OperationRequest? = null,
) {
    /** True when the action changed an offset or the active sub-datum. */
    val changed: Boolean get() = undo != null
}
