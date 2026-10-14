package org.superdro.core.ui.settings.machine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.OfflineEncoderSettingsRoute
import org.superdro.core.ui.navigation.OfflineMachineSettingsRoute
import org.superdro.core.ui.navigation.OfflineProbeInputRoute
import org.superdro.core.ui.navigation.ProbeInputRoute
import org.superdro.core.ui.navigation.ProbeLibraryRoute
import org.superdro.core.ui.navigation.ProfileFileRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.UiText

/**
 * The arguments of the factory of [MachineSettingsPresenter] in the DI graph: the profile, the
 * snackbar of the frame, and [tab] (true when the screen is in the back stack of the tab of the
 * profile).
 */
class MachineSettingsArgs(val profileId: String, val tab: Boolean = true, val showMessage: (UiText) -> Unit)

/**
 * The entry of [MachineSettingsRoute]: the machine settings of the profile of the tab (a screen of
 * the tab back stack). The entry owns the presenter, so the settings keep
 * their group and their texts while the tab changes.
 */
@Composable
fun MachineSettingsEntry(scope: TabEntryScope, route: MachineSettingsRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter {
        di.direct.instance<MachineSettingsArgs, MachineSettingsPresenter>(arg = MachineSettingsArgs(scope.deviceId.value, showMessage = scope::showMessage))
    }
    val state by presenter.uiState.collectAsState()
    val navigation = remember(scope) {
        object : MachineSettingsNavigation {
            override fun openEncoder(input: Char) = scope.navigator.push(EncoderSettingsRoute(input))
            override fun openProbeLibrary() = scope.global.push(ProbeLibraryRoute)
            override fun openProbeInput() = scope.navigator.push(ProbeInputRoute)
            override fun openProfileFile() = scope.navigator.push(ProfileFileRoute)
        }
    }
    MachineSettingsScreen(state = state, events = presenter, onBack = { scope.navigator.pop(route) }, navigation = navigation)
}

/**
 * The entry of [OfflineMachineSettingsRoute]: the machine settings of a stored profile with no tab
 * (a screen of the global back stack). Each change goes into the store. The encoder settings and
 * the probe input open the offline screens of the stored adapter profile of the machine. The
 * profile file needs a tab, so it does not open.
 */
@Composable
fun OfflineMachineSettingsEntry(scope: GlobalEntryScope, route: OfflineMachineSettingsRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter {
        di.direct.instance<MachineSettingsArgs, MachineSettingsPresenter>(
            arg = MachineSettingsArgs(route.profileId, tab = false, showMessage = scope::showMessage),
        )
    }
    val state by presenter.uiState.collectAsState()
    val navigation = remember(scope, presenter) {
        object : MachineSettingsNavigation {
            override fun openEncoder(input: Char) {
                val adapterId = presenter.uiState.value.profile?.adapterProfileId ?: return
                scope.navigator.push(OfflineEncoderSettingsRoute(adapterId, input))
            }

            override fun openProbeLibrary() = scope.navigator.push(ProbeLibraryRoute)

            override fun openProbeInput() {
                val adapterId = presenter.uiState.value.profile?.adapterProfileId ?: return
                scope.navigator.push(OfflineProbeInputRoute(adapterId))
            }
        }
    }
    MachineSettingsScreen(state = state, events = presenter, onBack = { scope.navigator.pop(route) }, navigation = navigation)
}
