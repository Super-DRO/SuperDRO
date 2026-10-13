package org.superdro.core.ui.functions.tools

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Tool
import org.superdro.core.resources.Res
import org.superdro.core.resources.tools_deleted
import org.superdro.core.resources.tools_export_failed
import org.superdro.core.resources.tools_exported
import org.superdro.core.resources.tools_export_empty
import org.superdro.core.store.files.tool.ToolCsvExporter
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/**
 * One row of the tool list.
 *
 * @property detail The row text of the tool.
 * @property active True for the active tool of the workspace.
 */
@Immutable
data class ToolRowUiState(
    val id: String,
    val name: String,
    val description: String,
    val detail: UiText,
    val active: Boolean,
    val selected: Boolean,
)

/**
 * The tool library screen.
 *
 * @property sharedLibrary True when the profile uses the shared tool library.
 */
@Immutable
data class ToolLibraryUiState(
    val loaded: Boolean = false,
    val machineType: MachineType = MachineType.VerticalMill,
    val sharedLibrary: Boolean = false,
    val sort: ToolSort = ToolSort.NameAscending,
    val rows: List<ToolRowUiState> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
) {
    /** True on a lathe: the lathe tools. */
    val lathe: Boolean get() = machineType.isLathe

    /** True while one or more tools are selected. */
    val selecting: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * The presenter of the tool library screen.
 *
 * - The list is the library that the profile uses now: the profile tools, or the shared tools
 *   when "Use Shared Tool Library" is on. The library filters by the machine kind. A change
 *   of the switch takes effect at once.
 * - Sort, select, delete the selected tools with undo for 10 s, and export the selected
 *   tools (or all tools) to a CSV file.
 *
 * @param messages Shows a short message in the snackbar of the frame.
 */
class ToolLibraryPresenter(
    private val profileId: String,
    private val services: FunctionServices,
    private val undo: UndoOffers,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    private val local = MutableStateFlow(Local())
    private val profile = services.machines.profile(profileId).stateIn(scope, SharingStarted.Eagerly, null)
    private val library = services.tools.library(profileId).stateIn(scope, SharingStarted.Eagerly, null)
    private val workspace = services.machines.activeWorkspace(profileId).stateIn(scope, SharingStarted.Eagerly, null)

    /** The UI state of the screen. */
    val uiState: StateFlow<ToolLibraryUiState> = combine(profile, library, workspace, local) { current, tools, active, ui ->
        if (current == null || tools == null) {
            ToolLibraryUiState(sort = ui.sort)
        } else {
            val ids = tools.mapTo(HashSet()) { it.id }
            val format = current.display.readoutFormat
            ToolLibraryUiState(
                loaded = true,
                machineType = current.machineType,
                sharedLibrary = current.useSharedToolLibrary,
                sort = ui.sort,
                rows = tools.sortedFor(ui.sort).map { tool -> rowOf(tool, current.metricDefault, format, active?.activeToolId, ui.selected) },
                selectedIds = ui.selected.filterTo(LinkedHashSet()) { it in ids },
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, ToolLibraryUiState())

    /** Sets the sort order. */
    fun onSort(sort: ToolSort) = local.update { state -> state.copy(sort = sort) }

    /** Turns the shared tool library on or off. The list changes at once. */
    fun onSharedLibrary(on: Boolean) {
        local.update { state -> state.copy(selected = emptySet()) }
        scope.launch { services.tools.setUseSharedLibrary(profileId, on) }
    }

    /** Selects or deselects the tool [id]. */
    fun onToggleSelected(id: String) = local.update { state ->
        state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id)
    }

    /** Selects all tools of the list. */
    fun onSelectAll() = local.update { state -> state.copy(selected = library.value.orEmpty().mapTo(LinkedHashSet()) { it.id }) }

    /** The selected tools that are in the library now. */
    private fun selection(): Set<String> {
        val ids = library.value.orEmpty().mapTo(HashSet()) { it.id }
        return local.value.selected.filterTo(LinkedHashSet()) { it in ids }
    }

    /** Clears the selection. */
    fun onClearSelection() = local.update { state -> state.copy(selected = emptySet()) }

    /** Deletes the selected tools, with undo for 10 s. */
    fun onDeleteSelected() {
        val ids = selection()
        if (ids.isEmpty()) return
        local.update { state -> state.copy(selected = emptySet()) }
        delete(ids)
    }

    /** Deletes the tool [id], with undo for 10 s. */
    fun onDelete(id: String) = delete(setOf(id))

    private fun delete(ids: Set<String>) {
        val tools = library.value.orEmpty().filter { tool -> tool.id in ids }
        if (tools.isEmpty()) return
        scope.launch {
            services.tools.deleteTools(ids)
            undo.offer(UiText.of(Res.string.tools_deleted, tools.size)) { services.tools.saveTools(profileId, tools) }
        }
    }

    /**
     * Exports the selected tools, or all tools when none is selected, to a CSV file.
     * The file has the SuperDRO metadata and no device identity.
     */
    fun onExport() {
        val current = profile.value ?: return
        val tools = library.value.orEmpty()
        val selected = selection()
        val count = if (selected.isEmpty()) tools.size else selected.size
        if (count == 0) {
            messages(UiText.of(Res.string.tools_export_empty))
            return
        }
        scope.launch {
            val file = ToolCsvExporter.export(current, tools, services.exportContext(), selected)
            val written = try {
                services.filePicker.createDocument(file.fileName, file.mimeType, file.bytes())
            } catch (error: Exception) {
                false
            }
            messages(if (written) UiText.of(Res.string.tools_exported, count, file.fileName) else UiText.of(Res.string.tools_export_failed))
        }
    }

    private fun rowOf(tool: Tool, profileMetric: Boolean, format: ReadoutFormat, activeId: String?, selected: Set<String>) = ToolRowUiState(
        id = tool.id,
        name = tool.name,
        description = tool.description,
        detail = ToolTexts.row(tool, profileMetric, format),
        active = tool.id == activeId,
        selected = tool.id in selected,
    )

    private data class Local(val sort: ToolSort = ToolSort.NameAscending, val selected: Set<String> = emptySet())
}
