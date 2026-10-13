package org.superdro.core.ui.functions.subdatums

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.model.AxisId
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.Units
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_value_not_number
import org.superdro.core.resources.sd_error_name
import org.superdro.core.resources.sd_error_no_coordinate
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The fields of the sub-datum edit sheet. */
enum class SubDatumField { Name, X, Y, Z, A, Coordinates }

/**
 * The UI state of the sub-datum edit sheet.
 *
 * @property xDiameter True on a lathe in the diameter mode: the X field shows and takes a diameter.
 * @property showY True when the point can have Y: not a lathe, and Y is enabled.
 * @property showZ True when Z is enabled.
 * @property showA True when the angular axis is enabled.
 * @property thread True for a point of a thread: its coordinates do not change here.
 */
@Immutable
data class SubDatumEditUiState(
    val loaded: Boolean = false,
    val newPoint: Boolean = true,
    val name: String = "",
    val note: String = "",
    val tags: String = "",
    val x: LengthFieldState = LengthFieldState(),
    val y: LengthFieldState = LengthFieldState(),
    val z: LengthFieldState = LengthFieldState(),
    val a: NumberFieldState = NumberFieldState(),
    val xLabel: String = "X",
    val yLabel: String = "Y",
    val zLabel: String = "Z",
    val aLabel: String = "A",
    val lathe: Boolean = false,
    val xDiameter: Boolean = false,
    val showY: Boolean = true,
    val showZ: Boolean = true,
    val showA: Boolean = false,
    val thread: Boolean = false,
    val errors: Map<SubDatumField, UiText> = emptyMap(),
)

/**
 * The presenter of the sub-datum edit sheet:
 *
 * - The name has 1 to 15 characters. A new point with a blank name gets the name `Point <n>` of
 *   the store.
 * - The tags are one field: the tags are separated by spaces. All tags stay.
 * - X, Y (not on a lathe), Z, and A (when the angular axis is enabled; stored). A blank field
 *   gives no coordinate. In the lathe diameter mode the X field shows and takes a diameter, and the
 *   store gets X / 2.
 * - A new point (a double tap on "+", the custom point) starts at the current position.
 *
 * @param subDatumId The point to edit, or null for a new point.
 */
class SubDatumEditPresenter(
    private val subDatumId: String?,
    private val services: FunctionServices,
    private val readout: TabReadout,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(SubDatumEditUiState())
    private var base: SubDatum? = null
    private var workspaceId: String = ""

    /** The UI state of the sheet. */
    val uiState: StateFlow<SubDatumEditUiState> = mutable.asStateFlow()

    /** Loads the point from [frame] (the readout of the tab). The entry calls it when the readout has a frame. */
    fun load(frame: ReadoutFrame) {
        if (mutable.value.loaded) return
        val profile = frame.profile
        val workspace = frame.workspace
        workspaceId = workspace.id
        val point = subDatumId?.let { id -> workspace.subDatums.firstOrNull { it.id == id } }
        base = point
        val start = point ?: AddPointRule.point(frame) ?: SubDatum()
        val lathe = profile.machineType.isLathe
        val diameter = lathe && frame.state(AxisId.X).diameterMode
        val xFactor = if (diameter) 2.0 else 1.0
        mutable.value = SubDatumEditUiState(
            loaded = true,
            newPoint = point == null,
            name = point?.name.orEmpty(),
            note = point?.note.orEmpty(),
            tags = SubDatumNames.tagText(point?.tags.orEmpty()),
            x = LengthFieldState.of(start.x?.times(xFactor), frame.metric(AxisId.X)),
            y = LengthFieldState.of(start.y, frame.metric(AxisId.Y)),
            z = LengthFieldState.of(start.z, frame.metric(AxisId.Z)),
            a = NumberFieldState.ofArcSec(start.a),
            xLabel = profile.x.label,
            yLabel = profile.y.label,
            zLabel = profile.z.label,
            aLabel = profile.angular.label,
            lathe = lathe,
            xDiameter = diameter,
            showY = !lathe && profile.y.enabled,
            showZ = profile.z.enabled,
            showA = profile.angular.enabled,
            thread = point?.groupKind == SubDatumGroupKind.Thread,
        )
    }

    fun onName(text: String) = mutable.update { it.copy(name = text, errors = it.errors - SubDatumField.Name) }

    fun onNote(text: String) = mutable.update { it.copy(note = text) }

    fun onTags(text: String) = mutable.update { it.copy(tags = text) }

    fun onText(field: SubDatumField, text: String) = mutable.update { state ->
        val errors = state.errors - field - SubDatumField.Coordinates
        when (field) {
            SubDatumField.X -> state.copy(x = state.x.withText(text), errors = errors)
            SubDatumField.Y -> state.copy(y = state.y.withText(text), errors = errors)
            SubDatumField.Z -> state.copy(z = state.z.withText(text), errors = errors)
            SubDatumField.A -> state.copy(a = state.a.withText(text), errors = errors)
            else -> state
        }
    }

    fun onUnit(field: SubDatumField, metric: Boolean) = mutable.update { state ->
        when (field) {
            SubDatumField.X -> state.copy(x = state.x.withUnit(metric))
            SubDatumField.Y -> state.copy(y = state.y.withUnit(metric))
            SubDatumField.Z -> state.copy(z = state.z.withUnit(metric))
            else -> state
        }
    }

    /**
     * Checks the fields and writes the point. Gives true when the point is stored and the sheet
     * can close. A new point becomes active when "Activate New Subdatum" is on.
     */
    suspend fun save(): Boolean {
        val state = mutable.value
        if (!state.loaded) return false
        val errors = LinkedHashMap<SubDatumField, UiText>()
        val name = state.name.trim()
        if (!(state.newPoint && name.isEmpty()) && !SubDatumNames.valid(name)) {
            errors[SubDatumField.Name] = UiText.of(Res.string.sd_error_name, SubDatumNames.MAX_NAME_LENGTH)
        }
        fun length(field: SubDatumField, value: LengthFieldState, label: String, shown: Boolean): Double? {
            if (!shown) return null
            val um = value.valueUm ?: return null
            if (um.isNaN()) {
                errors[field] = UiText.of(Res.string.fn_value_not_number, label)
                return null
            }
            return um
        }
        val point = base
        val thread = state.thread
        var x = length(SubDatumField.X, state.x, state.xLabel, true)?.let { if (state.xDiameter) it / 2.0 else it }
        var y = length(SubDatumField.Y, state.y, state.yLabel, state.showY)
        var z = length(SubDatumField.Z, state.z, state.zLabel, state.showZ)
        var a = if (state.showA) {
            state.a.value?.let { degrees ->
                if (degrees.isNaN()) {
                    errors[SubDatumField.A] = UiText.of(Res.string.fn_value_not_number, state.aLabel)
                    null
                } else {
                    degrees * Units.ARCSEC_PER_DEGREE
                }
            }
        } else {
            null
        }
        if (thread && point != null) {
            // The position of a thread point belongs to the thread.
            x = point.x
            y = point.y
            z = point.z
            a = point.a
        }
        if (errors.isEmpty() && x == null && y == null && z == null && a == null) {
            errors[SubDatumField.Coordinates] = UiText.of(Res.string.sd_error_no_coordinate)
        }
        if (errors.isNotEmpty()) {
            mutable.update { it.copy(errors = errors) }
            return false
        }
        val tags = SubDatumNames.parseTags(state.tags)
        if (point == null) {
            val added = services.machines.addSubDatum(workspaceId, SubDatum(name = name, note = state.note.trim(), tags = tags, x = x, y = y, z = z, a = a))
            val stored = (added as? StoreResult.Ok)?.value ?: run {
                mutable.update { it.copy(errors = mapOf(SubDatumField.Name to (added as StoreResult.Refused).refusal.toUiText())) }
                return false
            }
            if (readout.frame.value?.profile?.display?.activateNewSubDatum == true) activateWhenLoaded(stored.id)
            return true
        }
        val result = services.machines.updateSubDatum(
            point.copy(name = name, note = state.note.trim(), tags = tags, x = x, y = y, z = z, a = a),
        )
        if (result is StoreResult.Refused) {
            mutable.update { it.copy(errors = mapOf(SubDatumField.Name to result.refusal.toUiText())) }
            return false
        }
        return true
    }

    private suspend fun activateWhenLoaded(id: String) {
        val next = withTimeoutOrNull(5_000L) { readout.frame.first { frame -> frame?.workspace?.subDatums?.any { it.id == id } == true } }
            ?: return
        readout.apply(org.superdro.core.readout.operations.SubDatumOperations.activate(next, id), null)
    }
}
