package org.superdro.core.ui.functions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * A list that counts each full read of its items. A presenter that makes its state again reads the
 * sub-datums of the workspace, so the count shows each new state.
 */
class CountingList<T>(private val items: List<T>) : List<T> by items {

    /** The number of reads of all items. */
    var reads: Int = 0
        private set

    override fun iterator(): Iterator<T> {
        reads++
        return items.iterator()
    }

    override fun listIterator(): ListIterator<T> {
        reads++
        return items.listIterator()
    }

    override fun listIterator(index: Int): ListIterator<T> {
        reads++
        return items.listIterator(index)
    }
}

/**
 * A [TabReadout] with the frames that the test sets. [move] gives a frame with the same profile and
 * workspace objects and new sums of the inputs, as a frame of the adapter does.
 */
class ScriptedReadout(profile: MachineProfile, workspace: Workspace) : TabReadout {
    private val frames = MutableStateFlow<ReadoutFrame?>(ReadoutFrame(profile, workspace))

    override val deviceId: DeviceId = DeviceId(profile.id)
    override val frame: StateFlow<ReadoutFrame?> = frames
    override val sessionState: StateFlow<SessionState?> = MutableStateFlow(SessionState.Connected(0L))

    /** Sets a frame with new sums of the inputs and the same profile and workspace. */
    fun move(vararg values: Pair<AxisId, Double>) {
        val current = frames.value ?: return
        frames.value = ReadoutFrame(current.profile, current.workspace, values.toMap())
    }

    /** Sets a frame with the new workspace [workspace] and the same sums of the inputs. */
    fun change(workspace: Workspace) {
        val current = frames.value ?: return
        frames.value = ReadoutFrame(current.profile, workspace, current.inputs)
    }

    override fun positionUnavailable(): UiText? = null
    override fun apply(result: OperationResult, label: UiText?) = Unit
    override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) = Unit
    override fun showMessage(text: UiText) = Unit

    companion object {
        /** Gives [workspace] with [points] in a [CountingList]. */
        fun counted(workspace: Workspace, points: List<SubDatum>): Pair<Workspace, CountingList<SubDatum>> {
            val list = CountingList(points)
            return workspace.copy(subDatums = list) to list
        }
    }
}
