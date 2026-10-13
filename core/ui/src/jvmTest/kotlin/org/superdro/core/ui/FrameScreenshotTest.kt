package org.superdro.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_hint_zero
import org.superdro.core.resources.route_graphical_view
import org.superdro.core.resources.route_sub_datums
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.navigation.SidePanelPlaceholder
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.ReadoutSamples
import org.superdro.core.ui.readout.ReadoutScreen
import org.superdro.core.ui.tabs.DeviceTabUiState
import org.superdro.core.ui.tabs.DeviceTabsEvents
import org.superdro.core.ui.tabs.DeviceTabsUiState
import org.superdro.core.ui.tabs.TabStatusRules
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the full frame with the readout of the selected tab to PNG files
 * (`core/ui/build/screenshots/app-*.png`) in light and dark mode at the widths 600 dp, 840 dp,
 * and 1200 dp, in landscape (height = width × 10 / 16) and portrait (height = width × 16 / 10).
 * The states are built directly, not from the fake adapter.
 */
class FrameScreenshotTest {

    private object NoEvents : DeviceTabsEvents {
        override fun onSelect(id: DeviceId) = Unit
        override fun onOpenMenu(id: DeviceId) = Unit
        override fun onCloseMenu() = Unit
        override fun onPause(id: DeviceId) = Unit
        override fun onOpenOverflow() = Unit
        override fun onCloseOverflow() = Unit
        override fun onDisconnectAll() = Unit
        override fun onMessageShown(id: Long) = Unit
    }

    private val mill = DeviceId("mill")
    private val lathe = DeviceId("lathe")

    private fun tab(id: DeviceId, name: String, transport: TransportKind, state: SessionState) =
        DeviceTabUiState(id, name, transport, state, TabStatusRules.of(state), inGrace = !state.isOnline)

    private val tabs = DeviceTabsUiState(
        tabs = listOf(
            tab(mill, "Bridgeport", TransportKind.BluetoothSpp, SessionState.Connected(0L)),
            tab(lathe, "Lathe", TransportKind.Tcp, SessionState.Reconnecting(2, 1_000L, LinkFailure.Lost)),
        ),
        selected = mill,
        rememberedCount = 2,
    )

    @Test
    fun theFrameWithTheReadoutAtEachWidthAndOrientation() {
        listOf(600, 840, 1200).forEach { width ->
            listOf("landscape" to width * 10 / 16, "portrait" to width * 16 / 10).forEach { (orientation, height) ->
                listOf(false, true).forEach { dark ->
                    val name = "app-mill-$width-$orientation-${if (dark) "dark" else "light"}"
                    val file = ScreenshotRenderer.render(name, widthDp = width, heightDp = height, frames = 30) {
                        Frame(WindowLayout.of(width.dp, height.dp), dark)
                    }
                    assertTrue(file.length() > 0, name)
                }
            }
        }
    }

    @Composable
    private fun Frame(layout: WindowLayout, dark: Boolean) {
        val sample = ReadoutSamples.mill(hint = UiText.of(Res.string.readout_hint_zero))
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides layout) {
                FrameLayout(
                    state = tabs,
                    events = NoEvents,
                    onAddMachine = {},
                    onOpenSettings = {},
                    onOverflowAction = {},
                    onTabMenuAction = { _, _ -> },
                ) { modifier ->
                    ReadoutScreen(
                        state = sample.state,
                        axisValue = sample::flow,
                        events = NoReadoutEvents,
                        modifier = modifier,
                        layout = layout,
                        sidePanelContent = { SidePanelPlaceholder(Res.string.route_sub_datums) },
                        graphicalViewPane = { SidePanelPlaceholder(Res.string.route_graphical_view) },
                    )
                }
            }
        }
    }
}
