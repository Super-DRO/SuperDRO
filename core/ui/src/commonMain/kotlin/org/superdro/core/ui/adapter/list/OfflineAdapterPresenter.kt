package org.superdro.core.ui.adapter.list

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.ProbeContactType
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_input_probe
import org.superdro.core.resources.adapter_kind_disabled
import org.superdro.core.resources.adapter_probe_auto
import org.superdro.core.resources.adapter_probe_nc
import org.superdro.core.resources.adapter_probe_no
import org.superdro.core.resources.adapter_probe_summary
import org.superdro.core.resources.adapter_resolution_inch
import org.superdro.core.resources.adapter_resolution_rev
import org.superdro.core.resources.adapter_status_calibrated
import org.superdro.core.resources.adapter_status_ignored
import org.superdro.core.resources.adapter_status_not_calibrated
import org.superdro.core.resources.adapter_status_user_set
import org.superdro.core.resources.adapter_tach_summary
import org.superdro.core.resources.adapter_type_leadscrew
import org.superdro.core.resources.adapter_type_rack
import org.superdro.core.resources.adapter_type_rotary
import org.superdro.core.resources.adapter_type_scale
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.inputs.numberText
import org.superdro.core.ui.adapter.profile.AdapterProfileActions
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/** The settings screen of a row of the offline adapter details. */
sealed interface OfflineRowTarget {
    /** The offline encoder settings of [input]. */
    data class Encoder(val input: Char) : OfflineRowTarget

    /** The offline tach pulses. */
    data object Tach : OfflineRowTarget

    /** The offline probe input settings. */
    data object Probe : OfflineRowTarget
}

/** One input row of the offline adapter details: the name, a summary, and the settings button. */
@Immutable
data class OfflineInputRowUi(val key: String, val name: UiText, val summary: List<UiText>, val target: OfflineRowTarget)

/**
 * The state of the offline adapter details (`DialogOfflineAdapterDetails`).
 *
 * @property aliasEdit The text of the name while the user edits it, or null.
 * @property gone True after "Forget" or when the profile is not in the store: the screen closes.
 */
@Immutable
data class OfflineAdapterUiState(
    val loaded: Boolean = false,
    val gone: Boolean = false,
    val alias: String = "",
    val aliasEdit: String? = null,
    val aliasError: UiText? = null,
    val model: String = AdapterTexts.NOT_VALID,
    val bluetoothId: String = AdapterTexts.NOT_VALID,
    val legacy: Boolean = false,
    val declared: Set<DeclaredInput> = emptySet(),
    val rows: List<OfflineInputRowUi> = emptyList(),
)

/**
 * The presenter of the offline adapter details: one stored adapter profile with no link. The
 * screen uses only the store and sends no byte to an adapter. It shows the name (with edit), the
 * model, the Bluetooth ID, the declared inputs of a legacy adapter, and one row for each input with its
 * settings button; and the actions copy, export, import, and forget.
 */
class OfflineAdapterPresenter(
    private val adapterProfileId: String,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    private val profile: StateFlow<AdapterProfile?> = deps.adapters.adapterProfile(adapterProfileId).stateIn(scope, SharingStarted.Eagerly, null)
    private val edit = MutableStateFlow<Pair<String?, UiText?>>(null to null)
    private val forgotten = MutableStateFlow(false)
    private val messageFlow = MutableSharedFlow<UiText>(extraBufferCapacity = 16)
    private var seen = false

    /** The copy, export, import, and forget actions of the profile. */
    val actions: AdapterProfileActions =
        AdapterProfileActions(scope, deps, { profile.value }, { text -> messageFlow.tryEmit(text) }, onForgotten = { forgotten.value = true })

    /** The short messages for the snackbar of the frame. */
    val messages: SharedFlow<UiText> = messageFlow.asSharedFlow()

    /** The state of the screen. */
    val uiState: StateFlow<OfflineAdapterUiState> = combine(profile, edit, forgotten) { stored, (text, error), gone ->
        if (stored != null) seen = true
        when {
            gone || (stored == null && seen) -> OfflineAdapterUiState(loaded = true, gone = true)
            stored == null -> OfflineAdapterUiState()
            else -> OfflineAdapterUiState(
                loaded = true,
                alias = stored.alias,
                aliasEdit = text,
                aliasError = error,
                model = stored.model ?: AdapterTexts.NOT_VALID,
                bluetoothId = stored.identity ?: AdapterTexts.NOT_VALID,
                legacy = stored.legacy,
                declared = stored.declaredInputs,
                rows = rowsOf(stored),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, OfflineAdapterUiState())

    /** Starts the edit of the name. */
    fun onEditAlias() {
        edit.value = (profile.value?.alias ?: return) to null
    }

    /** The user typed the name. */
    fun onAliasText(text: String) = edit.update { (_, _) -> text to null }

    /** Writes the new name. A refused name shows the text of the store (blank, too long, exists). */
    fun onSaveAlias() {
        val text = edit.value.first ?: return
        val stored = profile.value ?: return
        scope.launch {
            when (val result = deps.adapters.saveAdapterProfile(stored.copy(alias = text))) {
                is StoreResult.Ok -> edit.value = null to null
                is StoreResult.Refused -> edit.value = text to result.refusal.toUiText()
            }
        }
    }

    /** Stops the edit of the name with no write. */
    fun onCancelAlias() {
        edit.value = null to null
    }

    /** Changes the declared input [input] of a legacy adapter. */
    fun onToggleDeclared(input: DeclaredInput) {
        val stored = profile.value ?: return
        val declared = if (input in stored.declaredInputs) stored.declaredInputs - input else stored.declaredInputs + input
        scope.launch { deps.adapters.saveAdapterProfile(stored.copy(declaredInputs = declared)) }
    }

    private fun rowsOf(stored: AdapterProfile): List<OfflineInputRowUi> {
        val encoders = Channels.axisChannels.map { input ->
            val encoder = stored.encoders[input]
            OfflineInputRowUi(input.toString(), AdapterTexts.encoderInputName(input), encoderSummary(encoder), OfflineRowTarget.Encoder(input))
        }
        val tach = stored.encoders[Channels.T]
        val tachRow = OfflineInputRowUi(
            key = Channels.T.toString(),
            name = AdapterTexts.encoderInputName(Channels.T),
            summary = listOf(UiText.of(Res.string.adapter_tach_summary, numberText(tach?.resolution ?: 1.0))),
            target = OfflineRowTarget.Tach,
        )
        val probeRow = OfflineInputRowUi(
            key = "probe",
            name = UiText.of(Res.string.adapter_input_probe),
            summary = listOf(UiText.of(Res.string.adapter_probe_summary, contactText(stored.probeContactType), stored.probeDebounceMs)),
            target = OfflineRowTarget.Probe,
        )
        return encoders + tachRow + probeRow
    }

    companion object {
        /** The summary of an encoder profile: the type, the resolution, and the calibration status. */
        fun encoderSummary(encoder: EncoderProfile?): List<UiText> {
            val profile = encoder ?: return listOf(UiText.of(Res.string.adapter_status_not_calibrated))
            if (profile.kind == EncoderKind.Disabled) return listOf(UiText.of(Res.string.adapter_kind_disabled))
            val type = UiText.of(
                when (profile.type) {
                    EncoderType.Scale -> Res.string.adapter_type_scale
                    EncoderType.LeadscrewEncoder -> Res.string.adapter_type_leadscrew
                    EncoderType.RotaryEncoder -> Res.string.adapter_type_rotary
                    EncoderType.RackPinion -> Res.string.adapter_type_rack
                },
            )
            val resolution = UiText.of(
                if (profile.kind == EncoderKind.Angular) Res.string.adapter_resolution_rev else Res.string.adapter_resolution_inch,
                numberText(profile.resolution),
            )
            val status = UiText.of(
                when (profile.calibration.status) {
                    CalibrationStatus.NotCalibrated -> Res.string.adapter_status_not_calibrated
                    CalibrationStatus.Calibrated -> Res.string.adapter_status_calibrated
                    CalibrationStatus.UserSet -> Res.string.adapter_status_user_set
                    CalibrationStatus.Ignored -> Res.string.adapter_status_ignored
                },
            )
            return listOf(type, resolution, status)
        }

        /** The text of a probe contact type. */
        fun contactText(type: ProbeContactType): UiText = UiText.of(
            when (type) {
                ProbeContactType.Auto -> Res.string.adapter_probe_auto
                ProbeContactType.NormallyClosed -> Res.string.adapter_probe_nc
                ProbeContactType.NormallyOpen -> Res.string.adapter_probe_no
            },
        )
    }
}
