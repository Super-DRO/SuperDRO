package org.superdro.core.ui.graphics.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.zreference.ZReference
import org.superdro.core.functions.zreference.ZReferenceResult
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeProfile
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.resources.Res
import org.superdro.core.resources.edgefinder_off
import org.superdro.core.resources.edgefinder_on
import org.superdro.core.resources.edgefinder_zauto_armed
import org.superdro.core.resources.edgefinder_zauto_button
import org.superdro.core.resources.edgefinder_zauto_no_port
import org.superdro.core.resources.edgefinder_zauto_setter_height
import org.superdro.core.resources.edgefinder_zauto_title
import org.superdro.core.resources.edgefinder_zauto_undo
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.common.LengthTexts
import org.superdro.core.ui.graphics.common.unitText
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.DroButton
import org.superdro.core.ui.readout.DroButtonStyle
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.adapter.probe.ProbeFunction
import org.superdro.core.ui.adapter.probe.ProbeSelector
import org.superdro.core.ui.adapter.probe.ProbeSelectorDelegate
import org.superdro.core.ui.adapter.probe.ProbeSelectorUiState
import org.superdro.core.ui.adapter.probe.ProbeSounds

/** The test tag of the Auto button of the Z reference. */
const val Z_AUTO_BUTTON_TAG: String = "z-reference-auto"

/** The test tag of the setter height text of the automatic Z reference. */
const val Z_AUTO_HEIGHT_TAG: String = "z-reference-auto-height"

/**
 * The UI state of the automatic Z reference.
 *
 * @property visible True when the probe and `probe_tool_setter_enable` are on: "Auto" shows.
 * @property enabled True when the start checks agree (Z enabled, `Connected`) and the probe has a
 *   trigger input.
 * @property setterHeightUm The setter height A of the selected profile (its `ZOffset`), or null.
 * @property setterHeightText The text of A in the unit of Z, or null.
 * @property closeRequests The count of the successful sets. The host sheet closes when it changes.
 */
@Immutable
data class ZReferenceAutoUiState(
    val loaded: Boolean = false,
    val visible: Boolean = false,
    val enabled: Boolean = false,
    val armed: Boolean = false,
    val message: UiText? = null,
    val setterHeightUm: Double? = null,
    val setterHeightText: String? = null,
    val selector: ProbeSelectorUiState = ProbeSelectorUiState(ProbeFunction.ZReference),
    val metric: Boolean = true,
    val closeRequests: Int = 0,
) {
    /** The selected Z reference profile, or null. */
    val selectedProbeId: String? get() = selector.selectedId
}

/**
 * The automatic mode of "Set Z Reference". The Z reference sheet of the functions area embeds it
 * with [ZReferenceAutoSection].
 *
 * - "Auto" shows when the probe and `probe_tool_setter_enable` are on. A tap arms the probe; a
 *   second tap disarms it.
 * - The setter height A is the `ZOffset` of the Z reference profile (`ProbeRoles.zReferenceProfileId`;
 *   a touch probe or a tool height setter). The probe selector of the adapter area
 *   ([ProbeSelectorDelegate]) writes it: a new profile from "New profile…" becomes the selection. With no profile, A is the typed value of the host
 *   sheet ([setManualValues]). B is the typed desired Z value; empty is 0.
 * - At a probe closure, [ZReference.applyAtTrigger] runs the checks of the manual mode and
 *   refuses a height that is not more than 0. The Z position is the latch at the trigger
 *   time, so Z reads `A + B` at the moment of the contact.
 * - Success: the Z absolute offset changes with undo, Z changes to the absolute mode, a typed A goes
 *   into the machine profile, the short beep plays, the probe disarms, and [closeRequests] counts
 *   up. Failure: the error sound and the message; the probe stays armed.
 * - A disconnection disarms the probe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZReferenceAutoPresenter(
    private val deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val profileId: String = deviceId.value
    private val local = MutableStateFlow(Local())
    private val probeSounds = ProbeSounds(deps.sounds, deviceId)
    private val selector = ProbeSelectorDelegate(deps.machines, deps.probes, deps.wallClock, deviceId, ProbeFunction.ZReference, scope)

    private val session: StateFlow<DeviceSession?> = deps.connections.sessions
        .map { sessions -> sessions[deviceId] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, deps.connections.sessions.value[deviceId])

    private val library: StateFlow<List<ProbeProfile>> = deps.probes.library(profileId)
        .map { list -> list.filter { probe -> ZReference.accepts(probe.type) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The UI state of the automatic mode. */
    val uiState: StateFlow<ZReferenceAutoUiState> = combine(readout.frame, readout.sessionState, library, local, selector.state) { frame, state, probes, ui, probeSelector ->
        uiStateOf(frame, state, probes, ui, probeSelector)
    }.stateIn(scope, SharingStarted.Eagerly, ZReferenceAutoUiState())

    init {
        scope.launch {
            session.flatMapLatest { current -> current?.probeEvents ?: emptyFlow() }.collect { event -> onProbeEvent(event) }
        }
        scope.launch {
            readout.sessionState.collect { state ->
                if (state !is SessionState.Connected) local.update { ui -> ui.copy(armed = false) }
            }
        }
    }

    /**
     * The typed values of the host sheet: the setter height A ([setterHeightUm]) and the desired Z
     * value B ([desiredZUm]), in µm, or null for an empty field. [setterMetric] is the unit of the A
     * field; the profile keeps it with A.
     */
    fun setManualValues(setterHeightUm: Double?, desiredZUm: Double?, setterMetric: Boolean = true) {
        local.update { ui -> ui.copy(typedHeightUm = setterHeightUm, desiredZUm = desiredZUm, typedMetric = setterMetric) }
    }

    /** "Auto": arms the probe, or disarms it. A blocked state does not arm. */
    fun toggle() {
        val current = uiState.value
        if (current.armed) {
            local.update { ui -> ui.copy(armed = false) }
            return
        }
        if (!current.visible || !current.enabled) {
            probeSounds.refused()
            return
        }
        local.update { ui -> ui.copy(armed = true, failure = null) }
    }

    /** Selects the Z reference profile [id]. Null clears the selection. */
    fun onSelectProbe(id: String?) = selector.select(id)

    /**
     * "New profile…" of the probe selector: the caller opens the new probe profile screen. The
     * next new accepted profile becomes the Z reference profile.
     */
    fun onNewProbe() = selector.requestNewProfile()

    private fun onProbeEvent(event: ProbeEvent) {
        val ui = local.value
        if (!ui.armed) return
        val frame = readout.frame.value ?: return
        if (event.port != probePort(frame.profile, library.value)) return
        val probe = selectedProbe(frame.profile, library.value)
        val profileHeight = ZReference.setterHeightFromProfile(probe)
        val fromProfile = probe != null
        val setterHeight = if (fromProfile) profileHeight else ui.typedHeightUm
        val latchedZ = ProbeTriggers.latched(frame, event)[AxisId.Z]
        val result = ZReference.applyAtTrigger(
            setterHeightUm = setterHeight,
            desiredZUm = ui.desiredZUm,
            zEnabled = frame.profile.z.enabled,
            connected = readout.sessionState.value is SessionState.Connected,
            latchedAbsoluteZUm = latchedZ,
            fromProfile = fromProfile,
        )
        when (result) {
            is ZReferenceResult.Applied -> {
                val delta = result.offsetDeltaUm ?: return
                readout.applyWorkspace(UiText.of(Res.string.edgefinder_zauto_undo)) { workspace ->
                    val z = workspace.axisStates[AxisId.Z] ?: AxisState()
                    workspace.copy(axisStates = workspace.axisStates + (AxisId.Z to z.copy(absoluteOffset = z.absoluteOffset + delta, relative = false)))
                }
                val typed = result.setterHeightUm
                if (typed != null) {
                    scope.launch { deps.machines.updateProfile(profileId) { profile -> ZReference.storeSetterHeight(profile, typed, ui.typedMetric) } }
                }
                probeSounds.play(result.sound)
                local.update { current -> current.copy(armed = false, failure = null, closeRequests = current.closeRequests + 1) }
            }
            is ZReferenceResult.Failure -> {
                probeSounds.play(result.sound)
                local.update { current -> current.copy(failure = result.message.toUiText()) }
            }
        }
    }

    private fun probePort(profile: MachineProfile, probes: List<ProbeProfile>): Char? {
        val probe = selectedProbe(profile, probes) ?: return profile.probe.channel
        return probe.port
    }

    private fun selectedProbe(profile: MachineProfile, probes: List<ProbeProfile>): ProbeProfile? =
        profile.probeRoles.zReferenceProfileId?.let { id -> probes.firstOrNull { probe -> probe.id == id } }

    private fun uiStateOf(
        frame: ReadoutFrame?,
        session: SessionState?,
        probes: List<ProbeProfile>,
        ui: Local,
        probeSelector: ProbeSelectorUiState,
    ): ZReferenceAutoUiState {
        if (frame == null) return ZReferenceAutoUiState(selector = probeSelector, closeRequests = ui.closeRequests)
        val profile = frame.profile
        val connected = session is SessionState.Connected
        val probe = selectedProbe(profile, probes)
        val port = probePort(profile, probes)
        val block = ZReference.block(profile.z.enabled, connected)
        val metric = frame.metric(AxisId.Z)
        val height = ZReference.setterHeightFromProfile(probe)
        val message = ui.failure
            ?: block?.toUiText()
            ?: if (port == null) UiText.of(Res.string.edgefinder_zauto_no_port) else null
        return ZReferenceAutoUiState(
            loaded = true,
            visible = ZReference.autoVisible(profile.probe),
            enabled = block == null && port != null,
            armed = ui.armed,
            message = message,
            setterHeightUm = height,
            setterHeightText = height?.let { value -> LengthTexts.format(value, metric, profile.display.readoutFormat) },
            selector = probeSelector,
            metric = metric,
            closeRequests = ui.closeRequests,
        )
    }

    private data class Local(
        val armed: Boolean = false,
        val failure: UiText? = null,
        val typedHeightUm: Double? = null,
        val desiredZUm: Double? = null,
        val typedMetric: Boolean = true,
        val closeRequests: Int = 0,
    )
}

/**
 * The automatic part of the Z reference sheet: the Z reference probe selector, the
 * setter height of the profile, the "Auto" button with its state, and the message line. The
 * functions area embeds it in its `ZReferenceRoute` sheet and closes the sheet when
 * [ZReferenceAutoUiState.closeRequests] changes.
 */
@Composable
fun ZReferenceAutoSection(
    state: ZReferenceAutoUiState,
    onToggle: () -> Unit,
    onSelectProbe: (String?) -> Unit,
    onNewProbe: () -> Unit,
    modifier: Modifier = Modifier,
    onEditProbe: (String) -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.edgefinder_zauto_title), style = MaterialTheme.typography.titleMedium)
        ProbeSelector(
            state = state.selector,
            onSelect = onSelectProbe,
            onNewProfile = onNewProbe,
            onEdit = onEditProbe,
            modifier = Modifier.fillMaxWidth(),
        )
        state.setterHeightText?.let { height ->
            Text(
                stringResource(Res.string.edgefinder_zauto_setter_height, height, unitText(state.metric)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(Z_AUTO_HEIGHT_TAG),
            )
        }
        if (state.visible) {
            val label = stringResource(Res.string.edgefinder_zauto_button)
            val status = stringResource(if (state.armed) Res.string.edgefinder_zauto_armed else Res.string.edgefinder_off)
            DroButton(
                onClick = onToggle,
                contentDescription = label,
                stateDescription = stringResource(if (state.armed) Res.string.edgefinder_on else Res.string.edgefinder_off),
                style = when {
                    state.armed -> DroButtonStyle.Active
                    state.enabled -> DroButtonStyle.Primary
                    else -> DroButtonStyle.Neutral
                },
                modifier = Modifier.align(Alignment.Start).testTag(Z_AUTO_BUTTON_TAG),
            ) {
                Column {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        MessageLine(state.message)
    }
}
