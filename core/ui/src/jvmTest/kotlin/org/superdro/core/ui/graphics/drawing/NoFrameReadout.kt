package org.superdro.core.ui.graphics.drawing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/** A [TabReadout] with no frame: the profile and the workspace did not load. */
class NoFrameReadout(override val deviceId: DeviceId) : TabReadout {
    override val frame: StateFlow<ReadoutFrame?> = MutableStateFlow(null)
    override val sessionState: StateFlow<SessionState?> = MutableStateFlow(null)
    override fun positionUnavailable(): UiText? = null
    override fun apply(result: OperationResult, label: UiText?) = Unit
    override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) = Unit
    override fun showMessage(text: UiText) = Unit
}
