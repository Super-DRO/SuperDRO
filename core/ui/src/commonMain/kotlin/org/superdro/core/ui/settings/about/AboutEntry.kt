package org.superdro.core.ui.settings.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.AboutRoute
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.navigation.GlobalEntryScope
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entry of [AboutRoute] and of [org.superdro.core.ui.navigation.HelpRoute]: the about screen
 * with its presenter from the DI graph
 * (`SettingsAboutModule` of `core:di`). With a [helpTopic], the screen opens at the help text of
 * that topic.
 */
@Composable
fun AboutEntry(scope: GlobalEntryScope, route: GlobalRoute, helpTopic: HelpTopic? = null) {
    val di = LocalDI.current
    val presenter = rememberPresenter {
        di.direct.instance<AboutPresenter>().also { about ->
            if (helpTopic != null) {
                about.selectGroup(AboutGroup.Help)
                about.openHelp(helpTopic)
            }
        }
    }
    val state by presenter.uiState.collectAsState()
    val events = remember(presenter) { presenter.events() }
    AboutScreen(state = state, events = events, onBack = { scope.navigator.pop(route) })
}
