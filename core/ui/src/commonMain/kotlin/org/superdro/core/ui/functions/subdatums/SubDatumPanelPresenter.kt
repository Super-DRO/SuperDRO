package org.superdro.core.ui.functions.subdatums

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.functions.patterns.PatternTags
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.SubDatumOperations
import org.superdro.core.resources.Res
import org.superdro.core.resources.sd_added
import org.superdro.core.resources.sd_deleted
import org.superdro.core.resources.sd_export_empty
import org.superdro.core.resources.sd_export_failed
import org.superdro.core.resources.sd_exported
import org.superdro.core.resources.sd_no_position
import org.superdro.core.resources.sd_transform_undone
import org.superdro.core.resources.sd_undo_activate
import org.superdro.core.resources.sd_undo_deactivate
import org.superdro.core.resources.sd_undo_origin
import org.superdro.core.store.files.subdatum.SubDatumCsvExporter
import org.superdro.core.store.repository.TransformUndo
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthTexts
import org.superdro.core.ui.functions.common.SubDatumBlocker
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.graphics.view.OperatorFrame

/**
 * The mark of one tag of a row. [index] is the place of the tag in the sorted tags of the
 * workspace: the composable gives the color of [TagColors] for it.
 */
@Immutable
data class TagMarkUi(val tag: String, val index: Int)

/**
 * One row of the sub-datum panel: a point or a guide.
 *
 * @property coordinates The text of the coordinates, for example "X 1.0000 • Y 0.5000".
 * @property kind The group kind of the point: a pattern, a thread, or a measured point.
 */
@Immutable
data class SubDatumRowUi(
    val id: String,
    val name: String,
    val note: String,
    val coordinates: String,
    val tags: List<TagMarkUi> = emptyList(),
    val active: Boolean = false,
    val selected: Boolean = false,
    val guide: Boolean = false,
    val kind: SubDatumGroupKind? = null,
)

/** One row of the tag filter. A null [tag] is the row "Untagged". */
@Immutable
data class TagFilterRowUi(val tag: String?, val index: Int, val hidden: Boolean)

/**
 * The menu of one point (a long press). [pairWith] is the name of the other point of the pair
 * commands, or null when they are not available.
 */
@Immutable
data class PointMenuUi(val id: String, val name: String, val pairWithId: String?, val pairWith: String?)

/** The UI state of the sub-datum panel. */
@Immutable
data class SubDatumPanelUiState(
    val loaded: Boolean = false,
    val workspaceId: String = "",
    val workspaceName: String = "",
    val lathe: Boolean = false,
    val rows: List<SubDatumRowUi> = emptyList(),
    val pointCount: Int = 0,
    val selecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val activeId: String? = null,
    val use3d: Boolean = true,
    val filterOpen: Boolean = false,
    val filterOn: Boolean = false,
    val filterRows: List<TagFilterRowUi> = emptyList(),
    val canUndoTransform: Boolean = false,
    val menu: PointMenuUi? = null,
) {
    /** True when the list shows no row. */
    val empty: Boolean get() = rows.isEmpty()
}

/** The result of "+". */
sealed interface AddPointOutcome {
    /** The store added [subDatum]. */
    data class Added(val subDatum: SubDatum) : AddPointOutcome

    /** The blocker ([SubDatumBlocker]): the dialog "Not Available Right Now" shows [message]. */
    data class Blocked(val message: UiText) : AddPointOutcome

    /** The session is not `Connected`, or an axis has no value: the readout shows [message]. */
    data class Unavailable(val message: UiText) : AddPointOutcome
}

/**
 * The presenter of the sub-datum panel of one tab. The tab owns it, so the selection and the tag
 * filter stay while the panel closes and opens.
 *
 * The changes of the offsets (the activation, "Set Absolute Origin Here") go through the readout
 * of the tab, with the undo. The delete of points goes through the store, with the undo of
 * [undo] for 10 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubDatumPanelPresenter(
    private val profileId: String,
    private val services: FunctionServices,
    private val readout: TabReadout,
    private val undo: UndoOffers,
) : ScopedPresenter(services.context) {

    private val local = MutableStateFlow(Local())

    private val transformUndo = readout.frame
        .map { frame -> frame?.workspace?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else services.machines.transformUndo(id) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The UI state of the panel. */
    val uiState: StateFlow<SubDatumPanelUiState> = combine(readout.frame, local, transformUndo) { frame, ui, undoCopy ->
        if (frame == null) SubDatumPanelUiState() else stateOf(frame, ui, undoCopy)
    }.stateIn(scope, SharingStarted.Eagerly, SubDatumPanelUiState())

    // -----------------------------------------------------------------------------------------
    // The rows

    /**
     * A tap on the row [id]. In the selection mode the tap selects or deselects the row. Else a tap
     * on a point activates it, and a tap on the active point deactivates it.
     */
    fun onTap(id: String) {
        if (local.value.selecting) {
            onToggleSelected(id)
            return
        }
        val frame = readout.frame.value ?: return
        val point = frame.workspace.subDatums.firstOrNull { it.id == id } ?: return
        if (frame.workspace.activeSubDatumId == id) {
            readout.apply(SubDatumOperations.deactivate(frame), UiText.of(Res.string.sd_undo_deactivate, point.name))
        } else {
            readout.apply(SubDatumOperations.activate(frame, id), UiText.of(Res.string.sd_undo_activate, point.name))
        }
    }

    /** A long press on the row [id]: the menu of the point. A guide has no menu. */
    fun onLongPress(id: String) {
        val frame = readout.frame.value ?: return
        val point = frame.workspace.subDatums.firstOrNull { it.id == id } ?: return
        val partner = pairPartner(frame, point)
        local.update { state -> state.copy(menu = PointMenuUi(id, point.name, partner?.id, partner?.name)) }
    }

    /** The menu closed. */
    fun onMenuDismiss() = local.update { state -> state.copy(menu = null) }

    /**
     * The other point A of the pair commands for the point [point] (B): the one selected
     * point, else the active point. A and B must be different and both need the two plane
     * coordinates (X and Y on a mill, X and Z on a lathe).
     */
    private fun pairPartner(frame: ReadoutFrame, point: SubDatum): SubDatum? {
        val points = frame.workspace.subDatums
        val selected = local.value.selected.singleOrNull()?.let { id -> points.firstOrNull { it.id == id } }
        val candidate = selected ?: frame.workspace.activeSubDatumId?.let { id -> points.firstOrNull { it.id == id } }
        if (candidate == null || candidate.id == point.id) return null
        val lathe = frame.profile.machineType.isLathe
        fun planar(subDatum: SubDatum) = subDatum.x != null && (if (lathe) subDatum.z != null else subDatum.y != null)
        return candidate.takeIf { planar(it) && planar(point) }
    }

    // -----------------------------------------------------------------------------------------
    // The selection

    /** Turns the selection mode on or off. Off clears the selection. */
    fun onSelecting(on: Boolean) = local.update { state -> state.copy(selecting = on, selected = if (on) state.selected else emptySet()) }

    /** Selects or deselects the row [id]. The selection mode turns on. */
    fun onToggleSelected(id: String) = local.update { state ->
        state.copy(selecting = true, selected = if (id in state.selected) state.selected - id else state.selected + id, menu = null)
    }

    /** Selects all shown rows. */
    fun onSelectAll() = local.update { state -> state.copy(selecting = true, selected = uiState.value.rows.mapTo(LinkedHashSet()) { it.id }) }

    /** "Deselect": clears the selection and leaves the selection mode. */
    fun onDeselect() = local.update { state -> state.copy(selecting = false, selected = emptySet()) }

    /** The selected points and guides that the workspace has now. */
    fun selection(): Set<String> {
        val frame = readout.frame.value ?: return emptySet()
        val ids = frame.workspace.subDatums.mapTo(HashSet()) { it.id } + frame.workspace.guides.map { it.id }
        return local.value.selected.filterTo(LinkedHashSet()) { it in ids }
    }

    /** The selected points (not the guides), in the order of the list. */
    fun selectedPoints(): List<SubDatum> {
        val frame = readout.frame.value ?: return emptyList()
        val selected = selection()
        return frame.workspace.subDatums.filter { it.id in selected }
    }

    // -----------------------------------------------------------------------------------------
    // The delete

    /** Deletes the selected rows, with undo for 10 s. */
    fun onDeleteSelected() {
        val ids = selection()
        if (ids.isEmpty()) return
        local.update { state -> state.copy(selecting = false, selected = emptySet()) }
        delete(ids)
    }

    /** Deletes the point or the guide [id], with undo for 10 s. */
    fun onDelete(id: String) {
        local.update { state -> state.copy(menu = null, selected = state.selected - id) }
        delete(setOf(id))
    }

    /** Deletes all points and guides of the workspace, with undo for 10 s. */
    fun onDeleteAll() {
        val frame = readout.frame.value ?: return
        local.update { state -> state.copy(selecting = false, selected = emptySet()) }
        delete(frame.workspace.subDatums.mapTo(LinkedHashSet()) { it.id } + frame.workspace.guides.map { it.id })
    }

    private fun delete(ids: Set<String>) {
        val frame = readout.frame.value ?: return
        val workspace = frame.workspace
        val pointIds = workspace.subDatums.filter { it.id in ids }.map { it.id }
        val guides = workspace.guides.filter { it.id in ids }
        if (pointIds.isEmpty() && guides.isEmpty()) return
        val activeId = workspace.activeSubDatumId
        val ordinal = workspace.lastOrdinal
        scope.launch {
            val deleted = if (pointIds.isEmpty()) emptyList() else services.machines.deleteSubDatums(workspace.id, pointIds)
            if (guides.isNotEmpty()) services.machines.deleteGuides(guides.map { it.id })
            val count = deleted.size + guides.size
            undo.offer(UiText.of(Res.string.sd_deleted, count)) { restore(workspace.id, deleted, guides, activeId, ordinal) }
        }
    }

    /** The undo of a delete: the points, the guides, the active point, and the ordinal come back. */
    private suspend fun restore(workspaceId: String, points: List<SubDatum>, guides: List<Guide>, activeId: String?, ordinal: Int) {
        services.machines.restoreSubDatums(workspaceId, points, guides, activeId, ordinal)
    }

    // -----------------------------------------------------------------------------------------
    // The point commands

    /**
     * "Set Absolute Origin Here": for each coordinate c, `L(axis, abs − c)`. The absolute
     * origin moves to the point. It needs a `Connected` session. The readout gives the undo.
     */
    fun onSetOrigin(id: String) {
        local.update { state -> state.copy(menu = null) }
        readout.positionUnavailable()?.let { refusal ->
            readout.showMessage(refusal)
            return
        }
        val frame = readout.frame.value ?: return
        val point = frame.workspace.subDatums.firstOrNull { it.id == id } ?: return
        readout.apply(SubDatumOperations.setOriginAt(frame, id), UiText.of(Res.string.sd_undo_origin, point.name))
    }

    // -----------------------------------------------------------------------------------------
    // The navigation: first, previous, next, last. The guides are not in the order.

    fun onFirst() = navigate { points, _ -> points.firstOrNull() }

    fun onLast() = navigate { points, _ -> points.lastOrNull() }

    fun onNext() = navigate { points, index -> if (index < 0) points.firstOrNull() else points.getOrNull(index + 1) ?: points.lastOrNull() }

    fun onPrevious() = navigate { points, index -> if (index < 0) points.lastOrNull() else points.getOrNull(index - 1) ?: points.firstOrNull() }

    private fun navigate(pick: (List<SubDatumRowUi>, Int) -> SubDatumRowUi?) {
        val frame = readout.frame.value ?: return
        val points = uiState.value.rows.filter { !it.guide }
        val index = points.indexOfFirst { it.id == frame.workspace.activeSubDatumId }
        val target = pick(points, index) ?: return
        if (target.id == frame.workspace.activeSubDatumId) return
        readout.apply(SubDatumOperations.activate(frame, target.id), UiText.of(Res.string.sd_undo_activate, target.name))
    }

    // -----------------------------------------------------------------------------------------
    // The 3D switch

    /**
     * Turns the 3D sub-datums on or off. The active point then activates again, so that its Z
     * offset follows the switch.
     */
    fun onToggle3d() {
        val frame = readout.frame.value ?: return
        val on = !frame.profile.use3dSubDatums
        val activeId = frame.workspace.activeSubDatumId
        scope.launch {
            services.machines.updateProfile(profileId) { profile -> profile.copy(use3dSubDatums = on) }
            if (activeId == null) return@launch
            val next = withTimeoutOrNull(FRAME_WAIT_MS) { readout.frame.first { it?.profile?.use3dSubDatums == on } } ?: return@launch
            readout.apply(SubDatumOperations.activate(next, activeId), null)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The tag filter

    /** Opens or closes the filter rows. A close shows all tags again. */
    fun onFilterToggle() = local.update { state ->
        if (state.filterOpen) state.copy(filterOpen = false, hiddenTags = emptySet(), hideUntagged = false) else state.copy(filterOpen = true)
    }

    /** A long press on the filter button: with the filter on, show all; else hide all tags and the untagged points. */
    fun onFilterLongPress() {
        val tags = uiState.value.filterRows.mapNotNull { it.tag }
        local.update { state ->
            val on = state.hiddenTags.isNotEmpty() || state.hideUntagged
            if (on) {
                state.copy(hiddenTags = emptySet(), hideUntagged = false)
            } else {
                state.copy(filterOpen = true, hiddenTags = tags.mapTo(HashSet(), PatternTags::key), hideUntagged = true)
            }
        }
    }

    /** Hides or shows the points of [tag], or the untagged points for a null [tag]. */
    fun onFilterRow(tag: String?) = local.update { state ->
        if (tag == null) {
            state.copy(hideUntagged = !state.hideUntagged)
        } else {
            val key = PatternTags.key(tag)
            state.copy(hiddenTags = if (key in state.hiddenTags) state.hiddenTags - key else state.hiddenTags + key)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The overflow menu

    /**
     * Exports the selected rows, or all shown rows when none is selected, to a CSV file. The file
     * has the SuperDRO metadata and no device identity.
     *
     * @param messages Shows the result.
     */
    fun onExport(messages: (UiText) -> Unit) {
        val frame = readout.frame.value ?: return
        val shownIds = uiState.value.rows.mapTo(HashSet()) { it.id }
        val points = frame.workspace.subDatums.filter { it.id in shownIds }
        val guides = frame.workspace.guides.filter { it.id in shownIds }
        val selected = selection()
        val count = if (selected.isEmpty()) points.size + guides.size else selected.size
        if (count == 0) {
            messages(UiText.of(Res.string.sd_export_empty))
            return
        }
        scope.launch {
            val file = SubDatumCsvExporter.export(frame.profile, points, guides, services.exportContext(), selected)
            val written = try {
                services.filePicker.createDocument(file.fileName, file.mimeType, file.bytes())
            } catch (error: Exception) {
                false
            }
            messages(if (written) UiText.of(Res.string.sd_exported, count, file.fileName) else UiText.of(Res.string.sd_export_failed))
        }
    }

    /** "Undo last transform". */
    fun onUndoLastTransform(messages: (UiText) -> Unit) {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        scope.launch {
            if (services.machines.undoLastTransform(workspaceId)) messages(UiText.of(Res.string.sd_transform_undone))
        }
    }

    // -----------------------------------------------------------------------------------------
    // "+"

    /**
     * Adds a sub-datum at the current position: the blocker and the live check come first. The point
     * goes through the inverse display transform. With "Activate New Subdatum" on, the new point
     * becomes active.
     */
    suspend fun addCurrentPoint(): AddPointOutcome {
        val frame = readout.frame.value ?: return AddPointOutcome.Unavailable(UiText.of(Res.string.sd_no_position))
        SubDatumBlocker.message(frame.workspace)?.let { message -> return AddPointOutcome.Blocked(message) }
        readout.positionUnavailable()?.let { refusal -> return AddPointOutcome.Unavailable(refusal) }
        val shown = AddPointRule.point(frame) ?: return AddPointOutcome.Unavailable(UiText.of(Res.string.sd_no_position))
        // With a part rotation or an axis alignment on, the new point goes through the
        // inverse display transform, so that the graphical view shows it at the tool.
        val point = OperatorFrame.toStored(frame, listOf(shown)).single()
        val added = when (val result = services.machines.addSubDatum(frame.workspace.id, point)) {
            is org.superdro.core.store.StoreResult.Ok -> result.value
            is org.superdro.core.store.StoreResult.Refused -> return AddPointOutcome.Unavailable(UiText.of(Res.string.sd_no_position))
        }
        if (frame.profile.display.activateNewSubDatum) activateWhenLoaded(added.id)
        readout.showMessage(UiText.of(Res.string.sd_added, added.name))
        return AddPointOutcome.Added(added)
    }

    /** Activates the new point [id] when the readout has it. */
    internal suspend fun activateWhenLoaded(id: String) {
        val next = withTimeoutOrNull(FRAME_WAIT_MS) { readout.frame.first { frame -> frame?.workspace?.subDatums?.any { it.id == id } == true } }
            ?: return
        readout.apply(SubDatumOperations.activate(next, id), null)
    }

    // -----------------------------------------------------------------------------------------
    // The UI state

    private fun stateOf(frame: ReadoutFrame, ui: Local, undoCopy: TransformUndo?): SubDatumPanelUiState {
        val workspace = frame.workspace
        val tags = workspace.subDatums.flatMap { it.tags }.distinctBy(PatternTags::key).sortedWith(String.CASE_INSENSITIVE_ORDER)
        val tagIndex = tags.withIndex().associate { (index, tag) -> PatternTags.key(tag) to index }
        val untagged = workspace.subDatums.any { it.tags.isEmpty() }
        // The order of the creation: a point that an undo brings back takes its old place. The
        // sort is stable, so the points of one pattern keep the order of the store.
        val visible = workspace.subDatums.sortedBy { it.createdOn }.filter { point ->
            if (point.tags.isEmpty()) !ui.hideUntagged else point.tags.any { PatternTags.key(it) !in ui.hiddenTags }
        }
        val ids = workspace.subDatums.mapTo(HashSet()) { it.id } + workspace.guides.map { it.id }
        val selected = ui.selected.filterTo(LinkedHashSet()) { it in ids }
        val pointRows = visible.map { point ->
            SubDatumRowUi(
                id = point.id,
                name = point.name,
                note = point.note,
                coordinates = coordinates(frame, point),
                tags = point.tags.map { tag -> TagMarkUi(tag, tagIndex[PatternTags.key(tag)] ?: 0) },
                active = point.id == workspace.activeSubDatumId,
                selected = point.id in selected,
                kind = point.groupKind,
            )
        }
        val guideRows = if (ui.hiddenTags.isNotEmpty() || ui.hideUntagged) {
            emptyList()
        } else {
            workspace.guides.map { guide -> SubDatumRowUi(guide.id, GUIDE_NAME, "", guideText(frame, guide), guide = true, selected = guide.id in selected) }
        }
        val filterRows = buildList {
            if (untagged || ui.hideUntagged) add(TagFilterRowUi(null, -1, ui.hideUntagged))
            tags.forEachIndexed { index, tag -> add(TagFilterRowUi(tag, index, PatternTags.key(tag) in ui.hiddenTags)) }
        }
        return SubDatumPanelUiState(
            loaded = true,
            workspaceId = workspace.id,
            workspaceName = workspace.name,
            lathe = frame.profile.machineType.isLathe,
            rows = pointRows + guideRows,
            pointCount = workspace.subDatums.size,
            selecting = ui.selecting,
            selectedIds = selected,
            activeId = workspace.activeSubDatumId,
            use3d = frame.profile.use3dSubDatums,
            filterOpen = ui.filterOpen,
            filterOn = ui.hiddenTags.isNotEmpty() || ui.hideUntagged,
            filterRows = filterRows,
            canUndoTransform = undoCopy != null,
            menu = ui.menu,
        )
    }

    private data class Local(
        val selecting: Boolean = false,
        val selected: Set<String> = emptySet(),
        val filterOpen: Boolean = false,
        val hiddenTags: Set<String> = emptySet(),
        val hideUntagged: Boolean = false,
        val menu: PointMenuUi? = null,
    )

    companion object {
        /** The name of the presenter in the presenters of the tab. */
        const val NAME: String = "functions.subDatums"

        /** The name of a guide row. */
        const val GUIDE_NAME: String = "Guide"

        /** The longest wait for the readout to show a change of the store. */
        private const val FRAME_WAIT_MS: Long = 5_000L

        /**
         * The text of the coordinates of [point] in the units of the readout: "X 1.0000 • Y 0.5000".
         * A lathe X shows the diameter in the diameter mode. The angle A is in degrees.
         */
        fun coordinates(frame: ReadoutFrame, point: SubDatum): String {
            val format = frame.profile.display.readoutFormat
            val parts = ArrayList<String>(4)
            fun linear(axis: AxisId, label: String, value: Double?) {
                if (value == null) return
                val shown = frame.postProcessor(axis).toShown(value)
                parts += "$label ${LengthTexts.format(shown, frame.metric(axis), format)}"
            }
            linear(AxisId.X, frame.profile.x.label, point.x)
            linear(AxisId.Y, frame.profile.y.label, point.y)
            linear(AxisId.Z, frame.profile.z.label, point.z)
            point.a?.let { a -> parts += "${frame.profile.angular.label} ${LengthTexts.degrees(a)}°" }
            return parts.joinToString(" • ")
        }

        /** The text of a guide: "X = 10.000". */
        fun guideText(frame: ReadoutFrame, guide: Guide): String {
            val label = when (guide.axis) {
                AxisId.X -> frame.profile.x.label
                AxisId.Y -> frame.profile.y.label
                AxisId.Z -> frame.profile.z.label
                else -> guide.axis.name
            }
            return "$label = ${LengthTexts.format(guide.valueUm, frame.metric(guide.axis), frame.profile.display.readoutFormat)}"
        }
    }
}
