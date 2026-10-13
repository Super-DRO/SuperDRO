package org.superdro.core.ui.adapter.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Channels
import org.superdro.core.resources.Res
import org.superdro.core.resources.integration_calibration_no_axis
import org.superdro.core.resources.integration_calibration_opening
import org.superdro.core.resources.route_calibration
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.InputCalibrationRoute
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.readout.SetupReminder
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.accentColor

/** The test tag of the wait text of [CalibrationWizardRoute]. */
const val CALIBRATION_WIZARD_START_TAG: String = "calibration-wizard-start"

/** The result of [CalibrationWizardStartPresenter]: null while the data loads. */
@Immutable
sealed interface CalibrationWizardStartState {
    /** The wizard of the adapter input starts: [route] replaces [CalibrationWizardRoute]. */
    data class Open(val route: InputCalibrationRoute) : CalibrationWizardStartState

    /** The wizard cannot start. The entry shows [message] and closes. */
    data class Refused(val message: UiText) : CalibrationWizardStartState
}

/**
 * The start of the calibration from an axis: the action
 * "Calibrate" of the setup reminder banner opens [CalibrationWizardRoute] with the axis of the
 * banner. This presenter selects the adapter input of that axis and gives the route of the
 * calibration wizard of the adapter area ([InputCalibrationRoute]).
 *
 * - The input is the first input of the axis that needs a calibration: the primary input, then
 *   the secondary input (`SetupReminder`). With no such input, it is the primary input.
 * - With no axis, the axis is the first axis of the setup reminder.
 * - The checks of [InputCalibrationStart] apply: "Secondary Input Disabled", "Input Prefix
 *   Missing", the tachometer input, and a disabled encoder profile.
 */
class CalibrationWizardStartPresenter(
    private val deviceId: DeviceId,
    private val axis: AxisId?,
    private val deps: AdapterUiDeps,
) : ScopedPresenter(deps.context) {

    private val mutable = MutableStateFlow<CalibrationWizardStartState?>(null)

    /** The start, or null while the machine profile and the adapter profile load. */
    val uiState: StateFlow<CalibrationWizardStartState?> = mutable.asStateFlow()

    init {
        scope.launch {
            val (profile, adapter) = combine(deps.machine(deviceId), deps.tabAdapter(deviceId).map(::Loaded)) { machine, loaded -> machine to loaded }
                .first { (machine, _) -> machine != null }
            mutable.value = start(profile!!, adapter.profile, axis)
        }
    }

    private class Loaded(val profile: AdapterProfile?)

    companion object {
        /** Gives the start of the calibration of [axis] (or of the first axis that needs one) of [profile]. */
        fun start(profile: MachineProfile, adapter: AdapterProfile?, axis: AxisId?): CalibrationWizardStartState {
            val target = axis ?: SetupReminder.axisNeedingSetup(profile, adapter)
                ?: return CalibrationWizardStartState.Refused(UiText.of(Res.string.integration_calibration_no_axis))
            val inputs = SetupReminder.inputs(profile, target)
            val secondary = inputs.size > 1 && inputs.indexOfFirst { input -> needsSetup(adapter, input.channel) } == 1
            return when (val result = InputCalibrationStart.forAxis(profile, target, secondary)) {
                is InputCalibrationStart.AxisStart.Refused -> CalibrationWizardStartState.Refused(result.message)
                is InputCalibrationStart.AxisStart.Ready -> {
                    val letter = result.route.input
                    InputCalibrationStart.inputRefusal(letter, adapter?.encoders?.get(letter))
                        ?.let(CalibrationWizardStartState::Refused)
                        ?: CalibrationWizardStartState.Open(result.route)
                }
            }
        }

        private fun needsSetup(adapter: AdapterProfile?, channel: Char): Boolean =
            EncoderProfile.needsSetupReminder(adapter?.encoders?.get(Channels.normalize(channel)))
    }
}

/**
 * The entry of [CalibrationWizardRoute]: a short wait screen. Then the calibration wizard
 * of the adapter input replaces it, or the snackbar tells why the wizard cannot start and the entry
 * closes.
 */
@Composable
fun CalibrationWizardEntry(scope: TabEntryScope, route: CalibrationWizardRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = rememberPresenter { CalibrationWizardStartPresenter(scope.deviceId, route.axis, deps) }
    val state by presenter.uiState.collectAsState()
    LaunchedEffect(state) {
        when (val current = state) {
            null -> Unit
            is CalibrationWizardStartState.Open -> scope.navigator.replaceTop(current.route)
            is CalibrationWizardStartState.Refused -> {
                scope.showMessage(current.message)
                scope.navigator.pop(route)
            }
        }
    }
    ScreenScaffold(title = stringResource(Res.string.route_calibration), onBack = { scope.navigator.pop(route) }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = accentColor())
            Text(
                stringResource(Res.string.integration_calibration_opening),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(CALIBRATION_WIZARD_START_TAG),
            )
        }
    }
}
