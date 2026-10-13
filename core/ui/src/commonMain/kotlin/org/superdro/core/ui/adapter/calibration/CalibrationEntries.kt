package org.superdro.core.ui.adapter.calibration

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.resources.Res
import org.superdro.core.resources.calibration_saved
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.InputCalibrationRoute
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.text.UiText

/**
 * The entry of the calibration wizard of an adapter input.
 * [org.superdro.core.ui.adapter.adapterEntries] calls it.
 *
 * The "Calibrate" button of the encoder settings pushes [InputCalibrationRoute]. The "Calibrate"
 * action of the setup reminder banner pushes [CalibrationWizardRoute], which selects the input of
 * its axis and then opens [InputCalibrationRoute] in its place. After "Apply",
 * "Discard", or "Cancel" the entry closes, and the encoder settings show again. The back
 * gesture closes the entry, and the presenter stops a sample when it closes.
 */
internal fun EntryRegistry.calibrationEntries() {
    // The "Calibrate" action of the setup reminder banner: the input of the axis.
    tab<CalibrationWizardRoute>(EntryKind.Screen, RouteArea.Adapter) { route -> CalibrationWizardEntry(this, route) }
    tab<InputCalibrationRoute>(EntryKind.Screen, RouteArea.Adapter) { route ->
        val deps = rememberInstance<AdapterUiDeps>()
        val presenter = rememberPresenter { InputCalibrationPresenter(deviceId, route.input, deps) }
        val state by presenter.uiState.collectAsState()
        val events = remember(presenter) { presenter.events() }
        LaunchedEffect(state.done) {
            if (state.done) {
                if (state.applied) showMessage(UiText.of(Res.string.calibration_saved, state.inputName))
                navigator.pop(route)
            }
        }
        InputCalibrationScreen(state = state, events = events)
    }
}
