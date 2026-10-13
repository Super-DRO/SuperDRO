package org.superdro.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.ThemeMode
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.currentWindowLayout
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.NavigationHost
import org.superdro.core.ui.navigation.RememberedMachinesRoute
import org.superdro.core.ui.navigation.TabReadoutPresenter
import org.superdro.core.ui.sound.LocalSoundController
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.tabs.DeviceTabsPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AppTheme

/**
 * The root composable of SuperDRO. It reads the app-scope objects of the
 * DI graph ([ProvideDI]): the tabs presenter, the navigation holder with the back stacks, the
 * entries, the app settings, and the sound controller. Then it shows the frame with the tabs and
 * the content of the selected tab.
 *
 * - The theme follows the app settings: the theme mode and "Large buttons".
 * - The window size class selects the layout.
 * - The sound controller knows when the app is visible, for the link tones.
 * - The one snackbar host shows the messages of the entries in sequence.
 */
@Composable
fun App(modifier: Modifier = Modifier) {
    val di = LocalDI.current
    val settings by rememberInstance<AppSettingsStore>().settings.collectAsState()
    val tabs = rememberInstance<DeviceTabsPresenter>()
    val holder = rememberInstance<NavigationHolder>()
    val registry = rememberInstance<EntryRegistry>()
    val sounds = rememberInstance<SoundController>()
    val readoutFactory: (DeviceId) -> TabReadoutPresenter = remember(di) {
        { id -> di.direct.instance<DeviceId, TabReadoutPresenter>(arg = id) }
    }
    LifecycleStartEffect(sounds) {
        sounds.onForeground()
        onStopOrDispose { sounds.onBackground() }
    }
    val snackbar = remember { SnackbarHostState() }
    val messages = remember { mutableStateListOf<AppMessage>() }
    val counter = remember { longArrayOf(0L) }

    AppTheme(mode = settings.themeMode, largeButtons = settings.largeButtons) {
        CompositionLocalProvider(
            LocalWindowLayout provides currentWindowLayout(),
            LocalSoundController provides sounds,
            LocalAppSnackbarHostState provides snackbar,
        ) {
            val state by tabs.uiState.collectAsState()
            MessageEffect(messages, snackbar)
            AppFrame(state = state, events = tabs, navigation = holder, modifier = modifier) { contentModifier ->
                NavigationHost(
                    holder = holder,
                    registry = registry,
                    tabIds = state.tabs.map { tab -> tab.id },
                    selected = state.selected,
                    readoutFactory = readoutFactory,
                    showMessage = { text -> messages += AppMessage(++counter[0], text) },
                    modifier = contentModifier,
                    emptyContent = {
                        EmptyState(
                            rememberedMachineCount = state.rememberedCount,
                            onAddMachine = { holder.push(AddDeviceRoute) },
                            onOpenRememberedMachines = { holder.push(RememberedMachinesRoute) },
                        )
                    },
                )
            }
        }
    }
}

/**
 * The frame with no tab and no DI graph, for the previews and the screenshot tests: the tab row,
 * the "+" button, the gear, the overflow menu, and the empty state.
 *
 * @param systemInDarkTheme The dark mode of the system. A test gives a fixed value.
 */
@Composable
fun AppPreview(
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.System,
    systemInDarkTheme: Boolean = isSystemInDarkTheme(),
    state: AppFrameState = AppFrameState(),
) {
    AppTheme(mode = themeMode, systemInDarkTheme = systemInDarkTheme) {
        AppFrame(state = state, modifier = modifier)
    }
}

/** One message of an entry for the snackbar. [id] keeps two equal texts apart. */
private data class AppMessage(val id: Long, val text: UiText)

/** Shows the first message in the snackbar, then removes it. */
@Composable
private fun MessageEffect(messages: SnapshotStateList<AppMessage>, host: SnackbarHostState) {
    val first = messages.firstOrNull() ?: return
    val text = first.text.resolve()
    LaunchedEffect(first.id) {
        try {
            host.showSnackbar(text)
        } finally {
            messages.remove(first)
        }
    }
}
