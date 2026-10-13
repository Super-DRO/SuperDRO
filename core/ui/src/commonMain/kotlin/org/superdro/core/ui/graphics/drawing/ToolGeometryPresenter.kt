package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.drawing.DrawingImporter
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.DrawingTypeTest
import org.superdro.core.drawing.tool.ToolGeometryCheck
import org.superdro.core.drawing.tool.ToolGeometryCodec
import org.superdro.core.drawing.tool.ToolGeometryImport
import org.superdro.core.drawing.tool.ToolGeometryImportResult
import org.superdro.core.drawing.tool.ToolGeometryReport
import org.superdro.core.model.AxisId
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolGeometry
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_no_shapes
import org.superdro.core.resources.drawing_not_a_drawing
import org.superdro.core.resources.drawing_tool_geometry_unreadable
import org.superdro.core.resources.drawing_too_large
import org.superdro.core.resources.drawingui_tool_attached
import org.superdro.core.resources.drawingui_tool_removed
import org.superdro.core.resources.drawingui_tool_save_failed
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.dxfImportSettings
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * The state of the cutter geometry screen of one lathe tool.
 *
 * @property tool The tool, or null while it loads or when the library does not have it.
 * @property geometry The geometry that the screen shows: the preview of an import, else the stored
 *   geometry of the tool, or null ("Not defined").
 * @property preview True while an imported geometry waits for "Attach" ("Cutter Geometry Import").
 * @property report The checks and the size of [geometry], or null.
 * @property metric The display unit of the size text.
 * @property message The result of an action, or null.
 */
@Immutable
data class ToolGeometryUiState(
    val tool: Tool? = null,
    val loaded: Boolean = false,
    val geometry: ToolGeometry? = null,
    val preview: Boolean = false,
    val report: ToolGeometryReport? = null,
    val metric: Boolean = true,
    val readoutFormat: ReadoutFormat = ReadoutFormat.Format3,
    val busy: Boolean = false,
    val message: UiText? = null,
    val problems: List<DrawingProblem> = emptyList(),
    val done: Boolean = false,
) {
    /** True for a geometry in inch (`drawnIn` 25.4). */
    val drawnInInch: Boolean get() = geometry?.drawnIn == ToolGeometryCodec.DRAWN_IN_INCH
}

/**
 * The presenter of the cutter geometry screen of the lathe tool [toolId]. The screen
 * imports a drawing file as the cutter outline of the tool, shows the checks of 22.10.3, and
 * stores the geometry in the tool. The graphical view draws the outline at the tool.
 */
class ToolGeometryPresenter(
    private val toolId: String,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private data class Local(
        val imported: ToolGeometry? = null,
        val unitsAssumed: Boolean = false,
        val busy: Boolean = false,
        val message: UiText? = null,
        val problems: List<DrawingProblem> = emptyList(),
        val done: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    private val library: StateFlow<List<Tool>?> = deps.tools.library(readout.deviceId.value)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The state of the screen. */
    val uiState: StateFlow<ToolGeometryUiState> = combine(local, library, readout.frame) { state, tools, frame ->
        val tool = tools?.firstOrNull { item -> item.id == toolId }
        val geometry = state.imported ?: tool?.geometry
        ToolGeometryUiState(
            tool = tool,
            loaded = tools != null,
            geometry = geometry,
            preview = state.imported != null,
            report = geometry?.let { item -> ToolGeometryImport.check(item, state.imported != null && state.unitsAssumed) },
            metric = frame?.metric(AxisId.X) ?: true,
            readoutFormat = frame?.profile?.display?.readoutFormat ?: ReadoutFormat.Format3,
            busy = state.busy,
            message = state.message,
            problems = state.problems,
            done = state.done,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ToolGeometryUiState())

    /** "Import cutter geometry from a file": the file picker, then the preview. */
    fun onImport() {
        if (local.value.busy) return
        local.update { state -> state.copy(busy = true, message = null) }
        scope.launch {
            val file = deps.filePicker.openDocument(listOf(DrawingImportController.ANY_MIME_TYPE))
            if (file == null) {
                local.update { state -> state.copy(busy = false) }
            } else {
                importNow(file.name, file.content)
            }
        }
    }

    /** Imports the file [bytes] with the display name [fileName] into the preview. */
    fun importBytes(fileName: String, bytes: ByteArray) {
        local.update { state -> state.copy(busy = true, message = null) }
        scope.launch { importNow(fileName, bytes) }
    }

    private fun importNow(fileName: String, bytes: ByteArray) {
        val drawnIn = local.value.imported?.drawnIn ?: ToolGeometryCodec.DRAWN_IN_MM
        when (val result = ToolGeometryImport.import(bytes, fileName, drawnIn, deps.dxfImportSettings(), now = deps.wallClock.now())) {
            is ToolGeometryImportResult.Imported -> local.update { state ->
                state.copy(
                    imported = result.geometry,
                    unitsAssumed = result.warnings.any { warning -> warning.code == DrawingProblemCodes.UNITS_ASSUMED },
                    busy = false,
                    problems = emptyList(),
                )
            }
            is ToolGeometryImportResult.Failed -> local.update { state ->
                state.copy(busy = false, message = failureText(result.message), problems = result.problems)
            }
        }
    }

    /** "Drawn in": mm ([inch] false, factor 1.0) or inch (factor 25.4). Only the preview changes. */
    fun onDrawnIn(inch: Boolean) {
        val factor = if (inch) ToolGeometryCodec.DRAWN_IN_INCH else ToolGeometryCodec.DRAWN_IN_MM
        local.update { state -> state.copy(imported = state.imported?.copy(drawnIn = factor)) }
    }

    /** "Attach": stores the preview geometry in the tool. */
    fun onAttach() {
        val geometry = local.value.imported ?: return
        save(geometry, UiText.of(Res.string.drawingui_tool_attached)) { state -> state.copy(imported = null) }
    }

    /** "Cancel" of the preview: the stored geometry shows again. */
    fun onCancelPreview() = local.update { state -> state.copy(imported = null, problems = emptyList()) }

    /** "Remove cutter geometry". */
    fun onRemove() {
        save(null, UiText.of(Res.string.drawingui_tool_removed)) { state -> state.copy(imported = null) }
    }

    /** "Done": the screen closes. */
    fun onDone() = local.update { state -> state.copy(done = true) }

    /** The screen showed the message. */
    fun onMessageShown() = local.update { state -> state.copy(message = null) }

    private fun save(geometry: ToolGeometry?, success: UiText, after: (Local) -> Local) {
        val tool = uiState.value.tool ?: return
        local.update { state -> state.copy(busy = true) }
        scope.launch {
            val result = deps.tools.updateTool(tool.copy(geometry = geometry))
            local.update { state ->
                if (result is StoreResult.Ok) {
                    after(state).copy(busy = false, message = success)
                } else {
                    state.copy(busy = false, message = UiText.of(Res.string.drawingui_tool_save_failed))
                }
            }
        }
    }

    companion object {
        /** Gives the text of an import failure of [ToolGeometryImport] in the language of the user. */
        fun failureText(message: String): UiText = when (message) {
            ToolGeometryCodec.UNREADABLE_MESSAGE -> UiText.of(Res.string.drawing_tool_geometry_unreadable)
            DrawingTypeTest.NOT_A_DRAWING_MESSAGE -> UiText.of(Res.string.drawing_not_a_drawing)
            DrawingImporter.TOO_LARGE_MESSAGE -> UiText.of(Res.string.drawing_too_large)
            DrawingImporter.NO_SHAPES_MESSAGE -> UiText.of(Res.string.drawing_no_shapes)
            else -> UiText.Plain(message)
        }

        /** Gives the text of a check of 22.10.3 in the language of the user. */
        fun checkText(check: ToolGeometryCheck): UiText = UiText.Key(check.stringKey, check.message)
    }
}
