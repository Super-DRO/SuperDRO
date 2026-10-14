package org.superdro.core.ui.readout

import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.ui.text.UiText

/**
 * The readout of one tab for each entry of the tab. The readout
 * presenter of the tab gives it. The function screens and sheets of the other areas use it to read
 * the position and to change the offsets.
 *
 * Rules:
 * - An operation that needs a position needs a `Connected` session. Call
 *   [positionUnavailable] first, and show its text when it is not null.
 * - [apply] and [applyWorkspace] write the change in one write transaction of `MachineRepository`.
 *   They send no command to the adapter.
 * - A change of an offset or of the active sub-datum shows the snackbar with "Undo" for 10 s.
 *  The undo restores all offsets that the action changed.
 */
interface TabReadout {

    /** The device of the tab. */
    val deviceId: DeviceId

    /**
     * The readout model of the tab: the machine profile, the active workspace, the hold values,
     * and the latest sums of the inputs. It keeps the last values while the session is not
     * online. Null until the profile and the workspace load.
     */
    val frame: StateFlow<ReadoutFrame?>

    /**
     * Gives the readout model with the newest inputs of the session, or null until the profile
     * and the workspace load. The session gives [frame] new inputs at most one time for each
     * display frame, so [frame] can be a little older. An action that uses the position at the
     * time of a press (for example Zero, Set, Hold, or a new point) uses this function.
     */
    fun latestFrame(): ReadoutFrame? = frame.value

    /** The state of the session of the tab, or null when the tab has no session. */
    val sessionState: StateFlow<SessionState?>

    /**
     * Gives null when the session is `Connected`. Else gives the status message: "Waiting for
     * the adapter" while the session connects or waits for data, else "No adapter connected".
     */
    fun positionUnavailable(): UiText?

    /**
     * Applies the result of an operation of `core:readout`: it writes the changed axis states and
     * the active sub-datum of [result], and keeps its hold values. When an offset changed, the
     * snackbar shows [label] with "Undo" for 10 s.
     */
    fun apply(result: OperationResult, label: UiText?)

    /**
     * Applies [transform] to the current workspace in one write transaction, for example the
     * result of a tool compensation. When an offset changed, the snackbar shows [label] with
     * "Undo" for 10 s.
     */
    fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace)

    /**
     * Shows [text] in the message line of the readout of the tab, above the rows. A sheet of the
     * tab does not hide the line. A screen entry hides the readout: it uses
     * `TabEntryScope.showMessage` (the snackbar of the frame) instead.
     */
    fun showMessage(text: UiText)
}
