package org.superdro.core.ui.adapter.inputs

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.ProbeContactType
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_probe_debounce_invalid
import org.superdro.core.resources.adapter_tach_offsets_reset
import org.superdro.core.resources.adapter_tach_pulses_invalid
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The adapter profile that a settings screen of the adapter area edits. */
sealed interface AdapterSource {
    /** Online: the adapter profile that the machine profile of the tab of [deviceId] selects. */
    data class Tab(val deviceId: DeviceId) : AdapterSource

    /** Offline: the stored adapter profile [adapterProfileId] (the adapters list). */
    data class Stored(val adapterProfileId: String) : AdapterSource
}

/** The adapter profile of [source], with all its maps, or null. */
fun AdapterUiDeps.adapterOf(source: AdapterSource): Flow<AdapterProfile?> = when (source) {
    is AdapterSource.Tab -> tabAdapter(source.deviceId)
    is AdapterSource.Stored -> adapters.adapterProfile(source.adapterProfileId)
}

/**
 * The state of the tach pulses sheet.
 *
 * @property hasProfile False when the tab has no adapter profile: the sheet shows 1 and cannot save.
 * @property done True after the save: the sheet closes.
 */
@Immutable
data class TachPulsesUiState(
    val loaded: Boolean = false,
    val hasProfile: Boolean = false,
    val text: String = "1",
    val error: UiText? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * The presenter of the tach pulses sheet. The pulses per revolution are the resolution of the
 * encoder
 * profile of the input `t` of the adapter. An input `t` with no encoder profile gets the default
 * profile of `t` (a rotary encoder, kind angular). The sheet sends no command to the adapter.
 *
 * Rules:
 * - The value is a decimal number more than 0. Else the text "Enter a number more than 0." shows.
 * - With no adapter profile, the sheet shows 1.
 * - A change to a value that is not 0 sets the tachometer offsets to 0, with the
 *   undo snackbar of the readout.
 * - A typed value of a profile with the status "not calibrated" changes the status to "user set".
 *
 * @param readout The readout of the tab, or null offline: the tachometer offsets.
 */
class TachPulsesPresenter(
    private val source: AdapterSource,
    private val deps: AdapterUiDeps,
    private val readout: TabReadout? = null,
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow(TachPulsesUiState())
    private val adapter: StateFlow<AdapterProfile?> = deps.adapterOf(source).stateIn(scope, SharingStarted.Eagerly, null)

    /** The state of the sheet. */
    val uiState: StateFlow<TachPulsesUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            val profile = deps.adapterOf(source).first()
            val pulses = profile?.encoders?.get(Channels.T)?.resolution ?: DEFAULT_PULSES
            mutable.update { state -> state.copy(loaded = true, hasProfile = profile != null, text = numberText(pulses)) }
        }
    }

    /** The user typed [text]. */
    fun onText(text: String) = mutable.update { state -> state.copy(text = text, error = null) }

    /** "Set Pulses": checks and writes the value. */
    fun onSet() {
        val state = mutable.value
        if (state.busy) return
        val profile = adapter.value ?: return
        val value = parseNumber(state.text)
        if (value == null || value <= 0.0) {
            mutable.update { old -> old.copy(error = UiText.of(Res.string.adapter_tach_pulses_invalid)) }
            return
        }
        mutable.update { old -> old.copy(busy = true) }
        scope.launch {
            val old = profile.encoders[Channels.T] ?: EncoderProfile.defaultFor(Channels.T)
            val status = if (old.calibration.status == CalibrationStatus.NotCalibrated) CalibrationStatus.UserSet else old.calibration.status
            val changed = old.resolution != value
            val encoder = old.copy(resolution = value, calibration = old.calibration.copy(status = status))
            when (val result = deps.adapters.saveEncoderProfile(profile.id, encoder)) {
                is StoreResult.Refused -> mutable.update { current -> current.copy(busy = false, error = result.refusal.toUiText()) }
                is StoreResult.Ok -> {
                    if (changed) resetTachometerOffsets()
                    mutable.update { current -> current.copy(busy = false, done = true) }
                }
            }
        }
    }

    private fun resetTachometerOffsets() {
        val tab = readout ?: return
        tab.applyWorkspace(UiText.of(Res.string.adapter_tach_offsets_reset)) { workspace ->
            val state = workspace.axisStates[AxisId.Tachometer] ?: return@applyWorkspace workspace
            workspace.copy(axisStates = workspace.axisStates + (AxisId.Tachometer to state.copy(absoluteOffset = 0.0, incrementalOffset = 0.0)))
        }
    }

    companion object {
        /** The pulses of an adapter with no profile. */
        const val DEFAULT_PULSES: Double = 1.0
    }
}

/**
 * The state of the probe input sheet.
 *
 * @property done True after the save: the sheet closes.
 */
@Immutable
data class ProbeInputUiState(
    val loaded: Boolean = false,
    val hasProfile: Boolean = false,
    val contactType: ProbeContactType = ProbeContactType.Auto,
    val debounceText: String = AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS.toString(),
    val error: UiText? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * The presenter of the probe input sheet. The sheet sets the contact type (Auto, Normally
 * closed, Normally open) and the debounce time in ms of the adapter. The app applies the values to
 * the probe ports `p` and `q`. The sheet sends no command: the adapter firmware does not get the
 * values. The session uses the new values at once (the stored setup of the device).
 *
 * The debounce is a whole number of ms, 0 or more. Else the text "Enter a whole number of 0 or
 * more." shows.
 */
class ProbeInputPresenter(
    private val source: AdapterSource,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow(ProbeInputUiState())
    private val adapter: StateFlow<AdapterProfile?> = deps.adapterOf(source).stateIn(scope, SharingStarted.Eagerly, null)

    /** The state of the sheet. */
    val uiState: StateFlow<ProbeInputUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            val profile = deps.adapterOf(source).first()
            mutable.update { state ->
                state.copy(
                    loaded = true,
                    hasProfile = profile != null,
                    contactType = profile?.probeContactType ?: ProbeContactType.Auto,
                    debounceText = (profile?.probeDebounceMs ?: AdapterProfile.DEFAULT_PROBE_DEBOUNCE_MS).toString(),
                )
            }
        }
    }

    /** The user selected [type]. */
    fun onContactType(type: ProbeContactType) = mutable.update { state -> state.copy(contactType = type) }

    /** The user typed the debounce [text]. */
    fun onDebounce(text: String) = mutable.update { state -> state.copy(debounceText = text, error = null) }

    /** "Save": checks and writes the values. */
    fun onSave() {
        val state = mutable.value
        if (state.busy) return
        val profile = adapter.value ?: return
        val debounce = state.debounceText.trim().toLongOrNull()
        if (debounce == null || debounce < 0L) {
            mutable.update { old -> old.copy(error = UiText.of(Res.string.adapter_probe_debounce_invalid)) }
            return
        }
        mutable.update { old -> old.copy(busy = true) }
        scope.launch {
            val result = deps.adapters.saveAdapterProfile(profile.copy(probeContactType = state.contactType, probeDebounceMs = debounce))
            mutable.update { current ->
                when (result) {
                    is StoreResult.Ok -> current.copy(busy = false, done = true)
                    is StoreResult.Refused -> current.copy(busy = false, error = result.refusal.toUiText())
                }
            }
        }
    }
}

/** Reads a decimal number. A comma is also a decimal separator. A blank text gives null. */
fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { value -> value.isFinite() }

/** The text of [value] with no ".0" for a whole number: 4.0 gives "4", 2.5 gives "2.5". */
fun numberText(value: Double): String =
    if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
