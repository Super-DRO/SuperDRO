package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_close
import org.superdro.core.resources.ic_flip
import org.superdro.core.resources.ic_my_location
import org.superdro.core.resources.ic_rotate_right
import org.superdro.core.resources.ic_undo
import org.superdro.core.resources.ic_zoom_out_map
import org.superdro.core.resources.ws_align_origin
import org.superdro.core.resources.ws_commands_title
import org.superdro.core.resources.ws_flip_title
import org.superdro.core.resources.ws_rotate_title
import org.superdro.core.resources.ws_scale_title
import org.superdro.core.resources.ws_undo_last
import org.superdro.core.resources.ws_undo_nothing
import org.superdro.core.resources.ws_undone
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tags of the "Workspace Commands" dialog. */
object WorkspaceCommandsTags {
    const val ALIGN_ORIGIN = "ws-commands-align-origin"
    const val FLIP = "ws-commands-flip"
    const val ROTATE = "ws-commands-rotate"
    const val SCALE = "ws-commands-scale"
    const val UNDO = "ws-commands-undo"
}

/**
 * The state of the "Workspace Commands" dialog.
 *
 * @property transforms The flip, rotate, and scale buttons show: not on a lathe.
 * @property activeSubDatumId The target of "Align Absolute Origin to Sub-Datum", or null.
 * @property blocked The lock text while a thread is armed, or null.
 */
@Immutable
data class WorkspaceCommandsUiState(
    val loaded: Boolean = false,
    val transforms: Boolean = false,
    val activeSubDatumId: String? = null,
    val undoLabel: String? = null,
    val blocked: UiText? = null,
    val done: Boolean = false,
)

/** The presenter of the "Workspace Commands" dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCommandsPresenter(
    private val services: FunctionServices,
    private val readout: TabReadout,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    private val workspaceId: StateFlow<String?> = readout.frame.map { frame -> frame?.workspace?.id }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, readout.frame.value?.workspace?.id)

    private val undo = workspaceId.flatMapLatest { id -> if (id == null) flowOf(null) else services.machines.transformUndo(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val done = MutableStateFlow(false)

    /** The UI state of the dialog. */
    val uiState: StateFlow<WorkspaceCommandsUiState> = combine(readout.frame.structure(), undo, done) { frame, copy, finished ->
        if (frame == null) {
            WorkspaceCommandsUiState()
        } else {
            WorkspaceCommandsUiState(
                loaded = true,
                transforms = !frame.profile.machineType.isLathe,
                activeSubDatumId = frame.workspace.activeSubDatumId,
                undoLabel = copy?.label,
                blocked = workspaceOperationsBlocked(frame.workspace),
                done = finished,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, WorkspaceCommandsUiState())

    /** "Undo last transform". Then the dialog can close ([WorkspaceCommandsUiState.done]). */
    fun onUndo() {
        val id = workspaceId.value ?: return
        scope.launch {
            val undone = services.machines.undoLastTransform(id)
            messages(UiText.of(if (undone) Res.string.ws_undone else Res.string.ws_undo_nothing))
            done.value = true
        }
    }
}

/** The events of the "Workspace Commands" dialog. */
interface WorkspaceCommandsEvents {
    fun onAlignOrigin(subDatumId: String)
    fun onFlip()
    fun onRotate()
    fun onScale()
    fun onUndo()
    fun onDismiss()
}

/**
 * The "Workspace Commands" dialog: "Align Absolute Origin to Sub-Datum", and, not on a lathe, the
 * flip, rotate, and scale buttons that work. "Undo last transform" shows while the store keeps the
 * undo copy.
 */
@Composable
fun WorkspaceCommandsDialog(state: WorkspaceCommandsUiState, events: WorkspaceCommandsEvents) {
    AlertDialog(
        onDismissRequest = events::onDismiss,
        title = { Text(stringResource(Res.string.ws_commands_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.blocked?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                val enabled = state.blocked == null
                val active = state.activeSubDatumId
                CommandButton(
                    stringResource(Res.string.ws_align_origin),
                    Res.drawable.ic_my_location,
                    enabled = enabled && active != null,
                    tag = WorkspaceCommandsTags.ALIGN_ORIGIN,
                ) { if (active != null) events.onAlignOrigin(active) }
                if (state.transforms) {
                    CommandButton(stringResource(Res.string.ws_rotate_title), Res.drawable.ic_rotate_right, enabled, WorkspaceCommandsTags.ROTATE, events::onRotate)
                    CommandButton(stringResource(Res.string.ws_flip_title), Res.drawable.ic_flip, enabled, WorkspaceCommandsTags.FLIP, events::onFlip)
                    CommandButton(stringResource(Res.string.ws_scale_title), Res.drawable.ic_zoom_out_map, enabled, WorkspaceCommandsTags.SCALE, events::onScale)
                }
                state.undoLabel?.let { label ->
                    CommandButton(stringResource(Res.string.ws_undo_last, label), Res.drawable.ic_undo, true, WorkspaceCommandsTags.UNDO, events::onUndo)
                }
            }
        },
        confirmButton = { TextButton(onClick = events::onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.fn_close)) } },
    )
}

@Composable
private fun CommandButton(text: String, icon: DrawableResource, enabled: Boolean, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = AccentButtons.outlined(),
        modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(tag),
    ) {
        Icon(painterResource(icon), contentDescription = null)
        Text("  $text", modifier = Modifier.weight(1f))
    }
}
