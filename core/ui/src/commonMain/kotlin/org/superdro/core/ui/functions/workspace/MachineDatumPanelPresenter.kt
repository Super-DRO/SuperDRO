package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineDatum
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.MachineDatumPlan
import org.superdro.core.readout.operations.MachineDatums
import org.superdro.core.resources.Res
import org.superdro.core.resources.ws_datum_apply_noop
import org.superdro.core.resources.ws_datum_captured
import org.superdro.core.resources.ws_datum_deleted
import org.superdro.core.resources.ws_datum_position_axis
import org.superdro.core.resources.ws_datum_undo_apply
import org.superdro.core.resources.ws_position_not_available
import org.superdro.core.resources.ws_position_not_available_title
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** One row of the machine datums tab: the name and the stored position. */
@Immutable
data class MachineDatumRowUiState(val id: String, val name: String, val position: List<UiText>, val unit: UiText?)

/**
 * The confirmation of "Set Absolute Origin to Machine Datum": the shift of each axis.
 */
@Immutable
data class DatumApplyUiState(val datumId: String, val name: String, val shifts: List<UiText>)

/**
 * The machine datums tab.
 *
 * @property apply The confirmation that shows, or null.
 * @property problem A refusal dialog (title and text), for example "Position Not Available".
 */
@Immutable
data class MachineDatumPanelUiState(
    val loaded: Boolean = false,
    val rows: List<MachineDatumRowUiState> = emptyList(),
    val apply: DatumApplyUiState? = null,
    val problem: Pair<UiText, UiText>? = null,
)

/**
 * The presenter of the machine datums tab of the side panel.
 *
 * - The list in the natural order of the names (the store sorts it).
 * - A tap on a datum: "Set Absolute Origin to Machine Datum" with the shift list, the no-op test
 *   (linear < 0.5 µm, angular < 0.5″), and the undo through the readout of the tab.
 * - The add button: a tap opens the edit sheet with the current machine position; a long press is
 *   the quick capture "Datum <n>" at once. The machine position has no tool offset and no
 *   instrument offset. A is stored when the angular axis is on.
 * - Delete with undo for 10 s.
 *
 * The rows use no live position: only a change of the datums, the profile, or the workspace makes
 * them again. [uiState] runs only while the panel collects it.
 *
 * @param messages Shows a short message (the message line of the readout).
 */
class MachineDatumPanelPresenter(
    private val profileId: String,
    private val services: FunctionServices,
    private val readout: TabReadout,
    private val undo: UndoOffers,
    private val messages: (UiText) -> Unit,
) : ScopedPresenter(services.context) {

    private val local = MutableStateFlow(Local())
    private val datums = services.machines.machineDatums(profileId).stateIn(scope, SharingStarted.Eagerly, null)

    /** The UI state of the tab. It runs only while a collector is present. */
    val uiState: StateFlow<MachineDatumPanelUiState> = combine(datums, readout.frame.structure(), local) { list, frame, ui ->
        if (list == null) {
            MachineDatumPanelUiState()
        } else {
            MachineDatumPanelUiState(
                loaded = true,
                rows = list.map { datum -> rowOf(datum, frame) },
                apply = ui.apply,
                problem = ui.problem,
            )
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), MachineDatumPanelUiState())

    /** A tap on the datum [id]: the confirmation of "Set Absolute Origin to Machine Datum". */
    fun onTap(id: String) {
        val datum = datums.value.orEmpty().firstOrNull { it.id == id } ?: return
        readout.positionUnavailable()?.let { refusal ->
            messages(refusal)
            return
        }
        val frame = readout.latestFrame() ?: return
        when (val plan = MachineDatums.plan(frame, datum)) {
            is MachineDatumPlan.PositionNotAvailable -> local.update { it.copy(problem = positionNotAvailable()) }
            is MachineDatumPlan.Apply -> if (plan.noOp) {
                messages(UiText.of(Res.string.ws_datum_apply_noop, datum.name))
            } else {
                val shifts = plan.shifts.map { (axis, shift) -> WorkspaceTexts.shiftLine(frame, axis, shift) }
                local.update { it.copy(apply = DatumApplyUiState(datum.id, datum.name, shifts)) }
            }
        }
    }

    /** "Yes" of the confirmation: the new absolute offsets `−D`, with undo. */
    fun onApplyConfirm() {
        val pending = local.value.apply ?: return
        local.update { it.copy(apply = null) }
        val datum = datums.value.orEmpty().firstOrNull { it.id == pending.datumId } ?: return
        val frame = readout.latestFrame() ?: return
        when (val plan = MachineDatums.plan(frame, datum)) {
            is MachineDatumPlan.PositionNotAvailable -> local.update { it.copy(problem = positionNotAvailable()) }
            is MachineDatumPlan.Apply -> readout.apply(plan.result, UiText.of(Res.string.ws_datum_undo_apply, datum.name))
        }
    }

    fun onApplyCancel() = local.update { it.copy(apply = null) }

    fun onProblemShown() = local.update { it.copy(problem = null) }

    /**
     * A tap on the add button: gives true when the edit sheet of a new datum can open. The session
     * must be `Connected`.
     */
    fun onAdd(): Boolean {
        readout.positionUnavailable()?.let { refusal ->
            messages(refusal)
            return false
        }
        return true
    }

    /** A long press on the add button: the quick capture "Datum <n>" at the machine position. */
    fun onQuickCapture() {
        val device = DeviceId(profileId)
        readout.positionUnavailable()?.let { refusal ->
            services.sounds.error(device)
            messages(refusal)
            return
        }
        val frame = readout.latestFrame() ?: return
        val name = nextName(datums.value.orEmpty())
        val datum = MachineDatums.capture(frame, name)
        if (datum == null) {
            services.sounds.error(device)
            local.update { it.copy(problem = positionNotAvailable()) }
            return
        }
        scope.launch {
            when (val result = services.machines.saveMachineDatum(profileId, datum)) {
                is StoreResult.Refused -> messages(result.refusal.toUiText())
                is StoreResult.Ok -> {
                    services.sounds.tap(device)
                    messages(UiText.of(Res.string.ws_datum_captured, result.value.name))
                }
            }
        }
    }

    /** Deletes the datum [id], with undo for 10 s. */
    fun onDelete(id: String) {
        val datum = datums.value.orEmpty().firstOrNull { it.id == id } ?: return
        scope.launch {
            services.machines.deleteMachineDatum(id)
            undo.offer(UiText.of(Res.string.ws_datum_deleted, datum.name)) { services.machines.saveMachineDatum(profileId, datum) }
        }
    }

    private fun rowOf(datum: MachineDatum, frame: ReadoutFrame?): MachineDatumRowUiState {
        if (frame == null) return MachineDatumRowUiState(datum.id, datum.name, emptyList(), null)
        val parts = listOf(AxisId.X to datum.x, AxisId.Y to datum.y, AxisId.Z to datum.z, AxisId.Angular to datum.a)
            .mapNotNull { (axis, value) ->
                value?.let { UiText.of(Res.string.ws_datum_position_axis, WorkspaceTexts.label(frame.profile, axis), WorkspaceTexts.value(frame, axis, it)) }
            }
        val linear = datum.x ?: datum.y ?: datum.z
        val axis = when {
            datum.x != null -> AxisId.X
            datum.y != null -> AxisId.Y
            else -> AxisId.Z
        }
        return MachineDatumRowUiState(datum.id, datum.name, parts, if (linear != null) WorkspaceTexts.unit(frame, axis) else null)
    }

    private fun positionNotAvailable(): Pair<UiText, UiText> =
        UiText.of(Res.string.ws_position_not_available_title) to UiText.of(Res.string.ws_position_not_available)

    private data class Local(val apply: DatumApplyUiState? = null, val problem: Pair<UiText, UiText>? = null)

    companion object {
        /**
         * Gives the name of a quick capture: `Datum <n>`. n starts
         * at 1 and increments while a datum with the name `datum <n>` exists (the compare ignores
         * the case).
         */
        fun nextName(datums: List<MachineDatum>, base: String = "Datum"): String {
            val names = datums.mapTo(HashSet()) { it.name.trim().lowercase() }
            var n = 1
            while ("${base.lowercase()} $n" in names) n++
            return "$base $n"
        }
    }
}
