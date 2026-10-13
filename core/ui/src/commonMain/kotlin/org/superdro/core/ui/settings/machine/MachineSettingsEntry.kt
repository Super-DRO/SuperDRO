package org.superdro.core.ui.settings.machine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.EncoderSettingsRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.ProbeInputRoute
import org.superdro.core.ui.navigation.ProbeLibraryRoute
import org.superdro.core.ui.navigation.ProfileFileRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.UiText

/**
 * The arguments of the factory of [MachineSettingsPresenter] in the DI graph: the profile of the
 * tab and the snackbar of the frame.
 */
class MachineSettingsArgs(val profileId: String, val showMessage: (UiText) -> Unit)

/**
 * The entry of [MachineSettingsRoute]: the machine settings of the profile of the tab (a screen of
 * the tab back stack). The entry owns the presenter, so the settings keep
 * their group and their texts while the tab changes.
 */
@Composable
fun MachineSettingsEntry(scope: TabEntryScope, route: MachineSettingsRoute) {
    val di = LocalDI.current
    val presenter = rememberPresenter {
        di.direct.instance<MachineSettingsArgs, MachineSettingsPresenter>(arg = MachineSettingsArgs(scope.deviceId.value, scope::showMessage))
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
