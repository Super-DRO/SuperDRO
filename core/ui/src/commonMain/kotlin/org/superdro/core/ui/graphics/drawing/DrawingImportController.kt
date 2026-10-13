package org.superdro.core.ui.graphics.drawing

import androidx.compose.runtime.Immutable
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.connection.DeviceId
import org.superdro.core.drawing.DrawingImportResult
import org.superdro.core.drawing.DrawingImporter
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.WireframeDocument
import org.superdro.core.drawing.export.DxfExportRequest
import org.superdro.core.drawing.export.DxfExporter
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingFormat
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_export_no_workspace
import org.superdro.core.resources.drawing_no_shapes
import org.superdro.core.resources.drawing_not_a_drawing
import org.superdro.core.resources.drawing_too_large
import org.superdro.core.resources.drawingui_export_not_saved
import org.superdro.core.resources.drawingui_export_saved
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.dxfImportSettings
import org.superdro.core.ui.navigation.DrawingProblemsRoute
import org.superdro.core.ui.navigation.DrawingSetupRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * A drawing that the import read and that waits for "Set Up Drawing".
 *
 * @property fileName The display name of the picked file.
 * @property warnings The warnings of the converter. The status line shows the first one.
 */
@Immutable
data class PendingDrawing(
    val fileName: String,
    val format: DrawingFormat,
    val document: WireframeDocument,
    val warnings: List<DrawingProblem> = emptyList(),
)

/** One event of the drawing import for the entry that shows it. [id] changes for each new event. */
sealed interface DrawingEvent {
    val id: Long

    /** Open [route] on the back stack of the tab. */
    data class Navigate(override val id: Long, val route: TabRoute) : DrawingEvent

    /** Show [text] in the snackbar of the frame. */
    data class Message(override val id: Long, val text: UiText) : DrawingEvent
}

/**
 * The state of the drawing import of one tab.
 *
 * @property busy True while the app reads a file ("Reading your drawing…").
 * @property pending The drawing for "Set Up Drawing", or null.
 * @property problems The problem list of "Drawing Not Imported".
 * @property event The next event to show, or null.
 */
@Immutable
data class DrawingImportUiState(
    val busy: Boolean = false,
    val pending: PendingDrawing? = null,
    val problems: List<DrawingProblem> = emptyList(),
    val event: DrawingEvent? = null,
)

/**
 * The drawing import and the actions of the drawing layer of one tab. The tab owns this
 * presenter: an entry gets it with [drawingController], so the pending drawing goes from the
 * import to "Set Up Drawing".
 *
 * Rules:
 * - The import reads the file on the device only. SuperDRO sends no drawing to a network service.
 * - A file with problems opens "Drawing Not Imported" with the problem list. Nothing changes.
 * - A file that is not a drawing, a file that is too large, and a drawing with no shapes give a
 *   message.
 * - The DXF export writes the workspace with its sub-datums, its guides, and its drawing. The
 *   units of the file are the display units of X, and `$MEASUREMENT` follows them. The
 *   hidden layers stay out of the file unless the user selects them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawingImportController(
    private val deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow(DrawingImportUiState())
    private var nextEventId = 0L

    /** The state of the import. */
    val uiState: StateFlow<DrawingImportUiState> = mutable.asStateFlow()

    /** The drawing of the active workspace of the tab, or null. */
    val drawing: StateFlow<Drawing?> = readout.frame
        .map { frame -> frame?.workspace?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else deps.drawings.drawing(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Lets the user pick a file, then imports it. */
    fun importFile() {
        if (mutable.value.busy) return
        mutable.update { state -> state.copy(busy = true) }
        scope.launch {
            val file = deps.filePicker.openDocument(listOf(ANY_MIME_TYPE))
            if (file == null) {
                mutable.update { state -> state.copy(busy = false) }
            } else {
                importNow(file.name, file.content)
            }
        }
    }

    /** Imports the file [bytes] with the display name [fileName]. */
    fun importBytes(fileName: String, bytes: ByteArray) {
        mutable.update { state -> state.copy(busy = true) }
        scope.launch { importNow(fileName, bytes) }
    }

    private fun importNow(fileName: String, bytes: ByteArray) {
        val result = DrawingImporter.import(bytes, deps.dxfImportSettings(), now = deps.wallClock.now())
        mutable.update { state ->
            val base = state.copy(busy = false)
            when (result) {
                is DrawingImportResult.Imported -> base.copy(
                    pending = PendingDrawing(fileName, result.format, result.document, result.warnings),
                    problems = emptyList(),
                    event = DrawingEvent.Navigate(++nextEventId, DrawingSetupRoute),
                )
                is DrawingImportResult.NotImported -> base.copy(
                    problems = result.problems,
                    event = DrawingEvent.Navigate(++nextEventId, DrawingProblemsRoute),
                )
                is DrawingImportResult.NotADrawing -> base.copy(event = message(UiText.of(Res.string.drawing_not_a_drawing)))
                is DrawingImportResult.TooLarge -> base.copy(event = message(UiText.of(Res.string.drawing_too_large)))
                is DrawingImportResult.NoShapes -> base.copy(event = message(UiText.of(Res.string.drawing_no_shapes)))
            }
        }
    }

    /** The entry showed the event [id]. */
    fun onEventHandled(id: Long) {
        mutable.update { state -> if (state.event?.id == id) state.copy(event = null) else state }
    }

    /** "Set Up Drawing" applied or cancelled the pending drawing. */
    fun clearPending() {
        mutable.update { state -> state.copy(pending = null) }
    }

    /** "Drawing Not Imported" closed. */
    fun clearProblems() {
        mutable.update { state -> state.copy(problems = emptyList()) }
    }

    /** Shows [text] as an event. "Set Up Drawing" uses it for its result after it closes. */
    fun showMessage(text: UiText) {
        mutable.update { state -> state.copy(event = message(text)) }
    }

    /** Deletes the shape at the list position [index] of the drawing ("Delete Geometry?"). */
    fun deleteShape(index: Int) {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        scope.launch { deps.drawings.deleteShape(workspaceId, index) }
    }

    /** Deletes the drawing of the workspace ("Delete Drawing?"). The sub-datums stay. */
    fun deleteDrawing() {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        scope.launch { deps.drawings.deleteDrawing(workspaceId) }
    }

    /** Shows or hides the layer [layerIndex] of the drawing. */
    fun setLayerHidden(layerIndex: Int, hidden: Boolean) {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        scope.launch { deps.drawings.setLayerHidden(workspaceId, layerIndex, hidden) }
    }

    /**
     * Exports the workspace as a DXF file and lets the user save it. The result is a
     * message.
     */
    fun exportDxf(includeHiddenLayers: Boolean = false) {
        val frame = readout.frame.value
        if (frame == null) {
            showMessage(UiText.of(Res.string.drawing_export_no_workspace))
            return
        }
        scope.launch {
            val workspace = deps.machines.loadWorkspace(frame.workspace.id)
            if (workspace == null) {
                showMessage(UiText.of(Res.string.drawing_export_no_workspace))
                return@launch
            }
            val drawing = deps.drawings.loadDrawing(workspace.id)
            val request = DxfExportRequest(
                workspace = workspace,
                drawing = drawing,
                metric = frame.metric(AxisId.X),
                includeHiddenLayers = includeHiddenLayers,
            )
            val bytes = DxfExporter.export(request)
            val name = DxfExporter.fileName(
                workspace.name,
                frame.profile.name,
                frame.profile.machineType,
                localStamp(deps.wallClock.now(), deps.utcOffsetSeconds()),
            )
            val saved = deps.filePicker.createDocument(name, DXF_MIME_TYPE, bytes)
            showMessage(UiText.of(if (saved) Res.string.drawingui_export_saved else Res.string.drawingui_export_not_saved))
        }
    }

    private fun message(text: UiText): DrawingEvent = DrawingEvent.Message(++nextEventId, text)

    companion object {
        /** The MIME type of the import picker: each file, because the type test reads the content. */
        const val ANY_MIME_TYPE: String = "*/*"

        /** The MIME type of a DXF file. */
        const val DXF_MIME_TYPE: String = "image/vnd.dxf"

        /** The name of this presenter in the presenters of the tab. */
        const val TAB_PRESENTER: String = "graphics-drawing"

        /** Gives the local time `yyyyMMdd_HHmmss` of [instant] with the offset [utcOffsetSeconds]. */
        fun localStamp(instant: Instant, utcOffsetSeconds: Int): String {
            val local = (instant + utcOffsetSeconds.seconds).toLocalDateTime(TimeZone.UTC)
            fun two(value: Int): String = value.toString().padStart(2, '0')
            return "${local.year.toString().padStart(4, '0')}${two(local.month.ordinal + 1)}${two(local.day)}_" +
                "${two(local.hour)}${two(local.minute)}${two(local.second)}"
        }
    }
}

/** Gives the result of a store write as true for success. */
internal fun StoreResult<*>.isOk(): Boolean = this is StoreResult.Ok<*>
