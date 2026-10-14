package org.superdro.core.ui.functions.tools

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.resources.Res
import org.superdro.core.resources.tools_undo_activate
import org.superdro.core.resources.tools_undo_clear
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.text.UiText

/** The tool panel of the side panel. */
@Immutable
data class ToolPanelUiState(val loaded: Boolean = false, val rows: List<ToolRowUiState> = emptyList())

/**
 * The presenter of the tool panel of the side panel:
 * the tools of the library, sorted by name. A tap activates a tool. A tap on the active tool
 * clears it. The change goes through the readout of the tab, so the snackbar shows "Undo" for
 * 10 s.
 *
 * The state uses no live position: only a change of the library, the profile, or the workspace
 * makes it again. [uiState] runs only while the panel collects it.
 */
class ToolPanelPresenter(
    private val profileId: String,
    private val services: FunctionServices,
    private val readout: TabReadout,
) : ScopedPresenter(services.context) {

    private val library = services.tools.library(profileId).stateIn(scope, SharingStarted.Eagerly, null)

    /** The UI state of the panel. It runs only while a collector is present. */
    val uiState: StateFlow<ToolPanelUiState> = combine(library, readout.frame.structure()) { tools, frame ->
        if (tools == null || frame == null) {
            ToolPanelUiState()
        } else {
            val profile = frame.profile
            ToolPanelUiState(
                loaded = true,
                rows = tools.sortedFor(ToolSort.NameAscending).map { tool ->
                    ToolRowUiState(
                        id = tool.id,
                        name = tool.name,
                        description = tool.description,
                        detail = ToolTexts.row(tool, profile.metricDefault, profile.display.readoutFormat),
                        active = tool.id == frame.workspace.activeToolId,
                        selected = false,
                    )
                },
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), ToolPanelUiState())

    /** A tap on the tool [id]: activate it, or clear it when it is the active tool. */
    fun onTap(id: String) {
        val frame = readout.frame.value ?: return
        val tool = library.value.orEmpty().firstOrNull { it.id == id } ?: return
        val clear = ToolActivation.isActive(frame.workspace, tool)
        val label = UiText.of(if (clear) Res.string.tools_undo_clear else Res.string.tools_undo_activate, tool.name)
        readout.applyWorkspace(label) { workspace -> ToolActivation.toggle(workspace, tool, frame.profile.machineType) }
    }
}
