package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.ws_created
import org.superdro.core.resources.ws_deleted
import org.superdro.core.resources.ws_deleted_count
import org.superdro.core.resources.ws_renamed
import org.superdro.core.resources.ws_selected
import org.superdro.core.resources.ws_undo_nothing
import org.superdro.core.resources.ws_undone
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.TransformUndo
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** One row of the workspaces sheet. [active] marks the active workspace (an icon and a text). */
@Immutable
data class WorkspaceRowUiState(val id: String, val name: String, val active: Boolean, val selected: Boolean)

/**
 * The stored data of the workspaces sheet.
 *
 * @property undoLabel The label of the last transform of the active workspace, or null when the
 *   store keeps no undo copy.
 */
@Immutable
data class WorkspacesUiState(
    val loaded: Boolean = false,
    val rows: List<WorkspaceRowUiState> = emptyList(),
    val activeId: String? = null,
    val undoLabel: String? = null,
    val selectedIds: Set<String> = emptySet(),
) {
    val selecting: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * The fields of the workspaces sheet. The texts change at once at each event, so that a text
 * field keeps the typed text.
 *
 * @property renameId The workspace of the rename field, or null.
 */
@Immutable
data class WorkspaceFieldsUiState(
    val newName: String = "",
    val newNameError: UiText? = null,
    val renameId: String? = null,
    val renameText: String = "",
    val renameError: UiText? = null,
    val confirmDeleteSelected: Boolean = false,
)

/**
 * The presenter of the workspaces sheet.
 *
 * - Create: "Workspace name can't be blank." and "A workspace with this name already exists."
 * - Select: the store keeps the axis state of the old workspace and loads the full state of the new
 *   workspace (the offsets, the modes, the active tool, the active sub-datum, the proximity
 *   warning, and the graphical switches).
 * - Delete: the store also removes the drawings, the threads, the logs, and the image file.
 *   The last workspace stays: "The last workspace can't be deleted.".
 * - "Undo last transform" while the store keeps the undo copy of the active workspace.
 *
 * @param messages Shows a short message (the message line of the readout).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacesPresenter(
    private val profileId: String,
    private val services: FunctionServices,
    private val readout: TabReadout,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val mutableFields = MutableStateFlow(WorkspaceFieldsUiState())

    private val profile = services.machines.profile(profileId).stateIn(scope, SharingStarted.Eagerly, null)
    private val workspaces = services.machines.workspaces(profileId).stateIn(scope, SharingStarted.Eagerly, null)
    private val activeId: StateFlow<String?> = combine(profile, workspaces) { current, list ->
        if (current == null || list == null) null else list.firstOrNull { it.id == current.activeWorkspaceId }?.id ?: list.firstOrNull()?.id
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private val undo: StateFlow<TransformUndo?> = activeId
        .flatMapLatest { id -> if (id == null) flowOf(null) else services.machines.transformUndo(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The fields of the sheet. */
    val fields: StateFlow<WorkspaceFieldsUiState> = mutableFields.asStateFlow()

    /** The list of the sheet. */
    val uiState: StateFlow<WorkspacesUiState> = combine(workspaces, activeId, undo, selected) { list, active, copy, chosen ->
        if (list == null) {
            WorkspacesUiState()
        } else {
            val ids = list.mapTo(HashSet()) { it.id }
            WorkspacesUiState(
                loaded = true,
                rows = list.map { workspace -> WorkspaceRowUiState(workspace.id, workspace.name, workspace.id == active, workspace.id in chosen) },
                activeId = active,
                undoLabel = copy?.label,
                selectedIds = chosen.filterTo(LinkedHashSet()) { it in ids },
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, WorkspacesUiState())

    fun onNewName(text: String) = mutableFields.update { it.copy(newName = text, newNameError = null) }

    /** Makes a workspace with the typed name. The new workspace does not become active. */
    fun onCreate() {
        val name = mutableFields.value.newName
        scope.launch {
            when (val result = services.machines.createWorkspace(profileId, name)) {
                is StoreResult.Refused -> mutableFields.update { it.copy(newNameError = result.refusal.toUiText()) }
                is StoreResult.Ok -> {
                    mutableFields.update { it.copy(newName = "", newNameError = null) }
                    messages(UiText.of(Res.string.ws_created, result.value.name))
                }
            }
        }
    }

    /**
     * Selects the workspace [id]. The store keeps the axis states of the old
     * workspace in one transaction and loads the state of the new one. Gives true when the sheet
     * can close.
     */
    suspend fun select(id: String): Boolean {
        if (id == activeId.value) return true
        val current = readout.frame.value?.workspace?.axisStates
        return when (val result = services.machines.selectWorkspace(profileId, id, current)) {
            is StoreResult.Refused -> {
                messages(result.refusal.toUiText())
                false
            }
            is StoreResult.Ok -> {
                messages(UiText.of(Res.string.ws_selected, result.value.name))
                true
            }
        }
    }

    /** Opens the rename field of the workspace [id]. */
    fun onRenameStart(id: String) {
        val name = workspaces.value.orEmpty().firstOrNull { it.id == id }?.name ?: return
        mutableFields.update { it.copy(renameId = id, renameText = name, renameError = null) }
    }

    fun onRenameText(text: String) = mutableFields.update { it.copy(renameText = text, renameError = null) }

    fun onRenameCancel() = mutableFields.update { it.copy(renameId = null, renameText = "", renameError = null) }

    /** Renames the workspace with the rules of the create. */
    fun onRenameConfirm() {
        val id = mutableFields.value.renameId ?: return
        val name = mutableFields.value.renameText
        scope.launch {
            when (val result = services.machines.renameWorkspace(id, name)) {
                is StoreResult.Refused -> mutableFields.update { it.copy(renameError = result.refusal.toUiText()) }
                is StoreResult.Ok -> {
                    mutableFields.update { it.copy(renameId = null, renameText = "", renameError = null) }
                    messages(UiText.of(Res.string.ws_renamed, result.value.name))
                }
            }
        }
    }

    /** Selects or deselects the workspace [id] for a multi-select delete. */
    fun onToggleSelected(id: String) = selected.update { set -> if (id in set) set - id else set + id }

    fun onClearSelection() {
        selected.value = emptySet()
    }

    fun onDeleteSelectedRequest() {
        if (selected.value.isNotEmpty()) mutableFields.update { it.copy(confirmDeleteSelected = true) }
    }

    fun onDeleteSelectedCancel() = mutableFields.update { it.copy(confirmDeleteSelected = false) }

    /**
     * Deletes the selected workspaces in one transaction. A selection of all
     * workspaces keeps one: then the message is "The last workspace can't be deleted."
     */
    fun onDeleteSelectedConfirm() {
        val ids = selected.value
        mutableFields.update { it.copy(confirmDeleteSelected = false) }
        selected.value = emptySet()
        if (ids.isEmpty()) return
        scope.launch {
            val result = services.machines.deleteWorkspaces(profileId, ids)
            result.refusal?.let { refusal -> messages(refusal.toUiText()) }
            if (result.refusal == null) messages(UiText.of(Res.string.ws_deleted_count, result.deletedIds.size))
        }
    }

    /** "Undo last transform" of the active workspace. */
    fun onUndoLastTransform() {
        val id = activeId.value ?: return
        scope.launch {
            val done = services.machines.undoLastTransform(id)
            messages(UiText.of(if (done) Res.string.ws_undone else Res.string.ws_undo_nothing))
        }
    }

    companion object {
        /**
         * Deletes the workspace [workspace]. Gives the message: the name of the deleted
         * workspace, or "The last workspace can't be deleted.".
         */
        suspend fun delete(services: FunctionServices, workspace: Workspace): UiText =
            when (val result = services.machines.deleteWorkspace(workspace.id)) {
                is StoreResult.Refused -> result.refusal.toUiText()
                is StoreResult.Ok -> UiText.of(Res.string.ws_deleted, workspace.name)
            }
    }
}
