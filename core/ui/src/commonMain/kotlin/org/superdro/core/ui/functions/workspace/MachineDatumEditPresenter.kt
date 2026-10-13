package org.superdro.core.ui.functions.workspace

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Units
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.MachineDatums
import org.superdro.core.resources.Res
import org.superdro.core.resources.ws_datum_error_name
import org.superdro.core.resources.ws_datum_error_no_value
import org.superdro.core.resources.ws_datum_error_number
import org.superdro.core.resources.ws_datum_saved
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The state of the machine datum edit sheet.
 *
 * @property axes The axes of the sheet: X always; Y when enabled and not a lathe; Z when enabled;
 *   A when the angular axis is enabled.
 * @property xDiameter True on a lathe with the X diameter mode: the X field shows and takes a
 *   diameter. The store keeps the radius.
 */
@Immutable
data class MachineDatumEditUiState(
    val loaded: Boolean = false,
    val newDatum: Boolean = true,
    val name: String = "",
    val note: String = "",
    val axes: List<AxisId> = listOf(AxisId.X),
    val x: LengthFieldState = LengthFieldState(),
    val y: LengthFieldState = LengthFieldState(),
    val z: LengthFieldState = LengthFieldState(),
    val a: NumberFieldState = NumberFieldState(),
    val xDiameter: Boolean = false,
    val labels: Map<AxisId, String> = emptyMap(),
    val nameError: UiText? = null,
    val fieldErrors: Map<AxisId, UiText> = emptyMap(),
    val error: UiText? = null,
    val message: UiText? = null,
)

/**
 * The presenter of the machine datum edit sheet.
 * A new datum starts with the current machine position (the scale position without the
 * absolute, tool, and instrument offsets). Each axis has a sync button that copies the machine
 * position. The name has 1 to 15 characters.
 *
 * @param datumId The datum to edit, or null for a new datum.
 */
class MachineDatumEditPresenter(
    private val profileId: String,
    private val datumId: String?,
    private val services: FunctionServices,
    private val frame: StateFlow<ReadoutFrame?>,
    private val positionUnavailable: () -> UiText?,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(MachineDatumEditUiState())
    private var base: MachineDatum? = null

    /** The UI state of the sheet. */
    val uiState: StateFlow<MachineDatumEditUiState> = mutable.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val profile = services.machines.loadProfile(profileId) ?: return
        val current = frame.value
        val stored = datumId?.let { id -> services.machines.machineDatums(profileId).first().firstOrNull { it.id == id } }
        base = stored
        val start = stored ?: current?.takeIf { positionUnavailable() == null }?.let { MachineDatums.capture(it, "") }
        val xDiameter = profile.machineType.isLathe && (current?.state(AxisId.X)?.diameterMode ?: false)
        val factor = if (xDiameter) 2.0 else 1.0
        mutable.value = MachineDatumEditUiState(
            loaded = true,
            newDatum = stored == null,
            name = stored?.name ?: MachineDatumPanelPresenter.nextName(services.machines.machineDatums(profileId).first()),
            note = stored?.note.orEmpty(),
            axes = axesOf(profile),
            x = LengthFieldState.of(start?.x?.let { it * factor }, metric(profile, current, AxisId.X)),
            y = LengthFieldState.of(start?.y, metric(profile, current, AxisId.Y)),
            z = LengthFieldState.of(start?.z, metric(profile, current, AxisId.Z)),
            a = NumberFieldState.ofArcSec(start?.a),
            xDiameter = xDiameter,
            labels = AxisId.entries.associateWith { axis -> WorkspaceTexts.label(profile, axis) },
        )
    }

    private fun metric(profile: MachineProfile, frame: ReadoutFrame?, axis: AxisId): Boolean = frame?.metric(axis) ?: profile.metricDefault

    fun onName(text: String) = mutable.update { it.copy(name = text, nameError = null, error = null) }

    fun onNote(text: String) = mutable.update { it.copy(note = text) }

    /** The text of the field of [axis]. */
    fun onText(axis: AxisId, text: String) = mutable.update { state ->
        when (axis) {
            AxisId.X -> state.copy(x = state.x.withText(text))
            AxisId.Y -> state.copy(y = state.y.withText(text))
            AxisId.Z -> state.copy(z = state.z.withText(text))
            AxisId.Angular -> state.copy(a = state.a.withText(text))
            else -> state
        }.copy(fieldErrors = state.fieldErrors - axis, error = null)
    }

    /** The unit of the length field of [axis]. */
    fun onUnit(axis: AxisId, metric: Boolean) = mutable.update { state ->
        when (axis) {
            AxisId.X -> state.copy(x = state.x.withUnit(metric))
            AxisId.Y -> state.copy(y = state.y.withUnit(metric))
            AxisId.Z -> state.copy(z = state.z.withUnit(metric))
            else -> state
        }
    }

    /** The sync button of [axis]: the field gets the machine position of the axis. */
    fun onSync(axis: AxisId) {
        positionUnavailable()?.let { refusal ->
            mutable.update { it.copy(message = refusal) }
            return
        }
        val current = frame.value ?: return
        val value = MachineDatums.machinePosition(current, axis)
        if (!value.isFinite()) return
        mutable.update { state ->
            when (axis) {
                AxisId.X -> state.copy(x = state.x.withValue(if (state.xDiameter) value * 2.0 else value))
                AxisId.Y -> state.copy(y = state.y.withValue(value))
                AxisId.Z -> state.copy(z = state.z.withValue(value))
                AxisId.Angular -> state.copy(a = NumberFieldState.ofArcSec(value))
                else -> state
            }.copy(fieldErrors = state.fieldErrors - axis)
        }
    }

    fun onMessageShown() = mutable.update { it.copy(message = null) }

    /** Checks the fields and writes the datum. Gives true when the sheet can close. */
    suspend fun save(): Boolean {
        val state = mutable.value
        if (!state.loaded) return false
        val name = state.name.trim()
        var nameError: UiText? = null
        if (name.isEmpty() || name.length > MachineDatum.MAX_NAME_LENGTH) {
            nameError = UiText.of(Res.string.ws_datum_error_name, MachineDatum.MAX_NAME_LENGTH)
        }
        val errors = LinkedHashMap<AxisId, UiText>()
        fun length(axis: AxisId, field: LengthFieldState, factor: Double = 1.0): Double? {
            if (axis !in state.axes) return null
            val value = field.valueUm ?: return null
            if (!value.isFinite()) {
                errors[axis] = UiText.of(Res.string.ws_datum_error_number)
                return null
            }
            return value / factor
        }
        val x = length(AxisId.X, state.x, if (state.xDiameter) 2.0 else 1.0)
        val y = length(AxisId.Y, state.y)
        val z = length(AxisId.Z, state.z)
        val a = if (AxisId.Angular in state.axes) {
            state.a.value?.let { degrees ->
                if (degrees.isFinite()) {
                    degrees * Units.ARCSEC_PER_DEGREE
                } else {
                    errors[AxisId.Angular] = UiText.of(Res.string.ws_datum_error_number)
                    null
                }
            }
        } else {
            null
        }
        val noValue = errors.isEmpty() && listOfNotNull(x, y, z, a).isEmpty()
        if (nameError != null || errors.isNotEmpty() || noValue) {
            mutable.update {
                it.copy(nameError = nameError, fieldErrors = errors, error = if (noValue) UiText.of(Res.string.ws_datum_error_no_value) else null)
            }
            return false
        }
        val datum = (base ?: MachineDatum()).copy(name = name, note = state.note.trim(), x = x, y = y, z = z, a = a)
        return when (val result = services.machines.saveMachineDatum(profileId, datum)) {
            is StoreResult.Refused -> {
                mutable.update { it.copy(error = result.refusal.toUiText()) }
                false
            }
            is StoreResult.Ok -> {
                mutable.update { it.copy(message = UiText.of(Res.string.ws_datum_saved, result.value.name)) }
                true
            }
        }
    }

    companion object {
        /** The axes of a datum of [profile]: X always; Y when enabled and not a lathe; Z when enabled; A when enabled. */
        fun axesOf(profile: MachineProfile): List<AxisId> = buildList {
            add(AxisId.X)
            if (profile.y.enabled && !profile.machineType.isLathe) add(AxisId.Y)
            if (profile.z.enabled) add(AxisId.Z)
            if (profile.angular.enabled) add(AxisId.Angular)
        }
    }
}
