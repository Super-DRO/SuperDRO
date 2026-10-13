package org.superdro.core.ui.graphics.view

import kotlinx.coroutines.flow.MutableStateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/** A [TabReadout] with a fixed frame for the UI tests: each write changes the frame at once. */
class FakeTabReadout(start: ReadoutFrame, connected: Boolean = true) : TabReadout {
    override val deviceId: DeviceId = DeviceId(start.profile.id)
    override val frame = MutableStateFlow<ReadoutFrame?>(start)
    override val sessionState = MutableStateFlow<SessionState?>(if (connected) SessionState.Connected(0L) else SessionState.Disconnected)
    val messages = mutableListOf<UiText>()
    val labels = mutableListOf<UiText?>()

    override fun positionUnavailable(): UiText? = null

    override fun apply(result: OperationResult, label: UiText?) {
        labels += label
        frame.value = frame.value?.with(workspace = result.workspace)
    }

    override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) {
        labels += label
        val current = frame.value ?: return
        frame.value = current.with(workspace = transform(current.workspace))
    }

    override fun showMessage(text: UiText) {
        messages += text
    }
}
