package org.superdro.core.ui.readout

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.TransportKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.readout_hint_zero
import org.superdro.core.resources.readout_undo
import org.superdro.core.resources.readout_undo_zero
import org.superdro.core.resources.route_graphical_view
import org.superdro.core.resources.route_sub_datums
import org.superdro.core.ui.FrameLayout
import org.superdro.core.ui.LocalAppSnackbarHostState
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WidthClass
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.graphics.view.GraphicalView
import org.superdro.core.ui.graphics.view.RecordingViewEvents
import org.superdro.core.ui.graphics.view.ViewStates
import org.superdro.core.ui.navigation.SidePanelPlaceholder
import org.superdro.core.ui.tabs.DeviceTabUiState
import org.superdro.core.ui.tabs.DeviceTabsEvents
import org.superdro.core.ui.tabs.DeviceTabsUiState
import org.superdro.core.ui.tabs.TabStatusRules
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the readout screen to PNG files (`core/ui/build/screenshots/readout-*.png`) in light and
 * dark mode at the widths 600 dp, 800 dp, 840 dp, and 1200 dp, in landscape (height = width × 10 / 16)
 * and portrait (height = width × 16 / 10), for a mill and a lathe. The
 * states come from [ReadoutSamples], not from the fake adapter.
 */
class ReadoutScreenshotTest {

    private val widths = listOf(600, 800, 840, 1200)

    private object NoTabEvents : DeviceTabsEvents {
        override fun onSelect(id: DeviceId) = Unit
        override fun onOpenMenu(id: DeviceId) = Unit
        override fun onCloseMenu() = Unit
        override fun onPause(id: DeviceId) = Unit
        override fun onOpenOverflow() = Unit
        override fun onCloseOverflow() = Unit
        override fun onDisconnectAll() = Unit
        override fun onMessageShown(id: Long) = Unit
    }

    private val millTab = DeviceId("mill")
    private val tabs = DeviceTabsUiState(
        tabs = listOf(
            DeviceTabUiState(
                id = millTab,
                name = "Bridgeport",
                transport = TransportKind.BluetoothSpp,
                state = SessionState.Connected(0L),
                status = TabStatusRules.of(SessionState.Connected(0L)),
            ),
        ),
        selected = millTab,
        rememberedCount = 1,
    )

    @Test
    fun theMillAtEachWidthAndOrientation() {
        renderAll("mill") { ReadoutSamples.mill(hint = UiText.of(Res.string.readout_hint_zero)) }
    }

    @Test
    fun theLatheAtEachWidthAndOrientation() {
        renderAll("lathe") { ReadoutSamples.lathe() }
    }

    @Test
    fun theMillWithTheSidePanelOpen() {
        val sample = ReadoutSamples.mill()
        val open = ReadoutSamples.Sample(sample.state.copy(sidePanel = SidePanelUiState(org.superdro.core.model.SidePanelMode.SubDatums, toolsEnabled = true)), sample.values)
        val file = render("readout-mill-1200-landscape-panel-dark", open, 1200, 750, dark = true)
        assertTrue(file.length() > 0)
    }

    @Test
    fun theSetupReminderDialogOpen() {
        val sample = ReadoutSamples.mill(setupReminder = true)
        val layout = WindowLayout.of(1200.dp, 750.dp)
        val file = ScreenshotRenderer.render("readout-mill-1200-landscape-setup-dialog-dark", widthDp = 1200, heightDp = 750, frames = 30) {
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = false) {
                CompositionLocalProvider(LocalWindowLayout provides layout) {
                    ReadoutScreen(
                        state = sample.state,
                        axisValue = sample::flow,
                        events = NoReadoutEvents,
                        layout = layout,
                        sidePanelContent = { SidePanelPlaceholder(Res.string.route_sub_datums) },
                        graphicalViewPane = { SidePanelPlaceholder(Res.string.route_graphical_view) },
                        rpmText = sample.rpm,
                    )
                    SetupReminderDialog(onSettings = {}, onCalibrate = {}, onDismiss = {})
                }
            }
        }
        assertTrue(file.length() > 0)
    }

    @Test
    fun theFrameWithTheUndoSnackbarAndTheGraphicalView() {
        val windows = listOf(
            Triple("readout-undo-snackbar-portrait-800x1200", 800 to 1200, WindowLayout.of(800.dp, 1200.dp)),
            Triple("readout-undo-snackbar-compact-portrait-600x960", 600 to 960, WindowLayout(WidthClass.Compact, landscape = false)),
            Triple("readout-undo-snackbar-short-landscape-800x500", 800 to 500, WindowLayout.of(800.dp, 500.dp)),
        )
        windows.forEach { (name, size, layout) ->
            val (width, height) = size
            val file = ScreenshotRenderer.render(name, widthDp = width, heightDp = height, density = 2f, frames = 30) {
                UndoSnackbarFrame(sample = ReadoutSamples.mill(), width = width, height = height, layout = layout)
            }
            assertTrue(file.length() > 0, name)
        }
    }

    @Composable
    private fun UndoSnackbarFrame(sample: ReadoutSamples.Sample, width: Int, height: Int, layout: WindowLayout) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = false) {
            val host = remember { SnackbarHostState() }
            val message = stringResource(Res.string.readout_undo_zero, "X")
            val action = stringResource(Res.string.readout_undo)
            LaunchedEffect(message, action) {
                host.showSnackbar(
                    message = message,
                    actionLabel = action,
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            CompositionLocalProvider(
                LocalWindowLayout provides layout,
                LocalAppSnackbarHostState provides host,
            ) {
                FrameLayout(
                    state = tabs,
                    events = NoTabEvents,
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
                        graphicalViewPane = {
                            val (view, controls) = ViewStates.millView(width.toDouble(), (height * 0.5).coerceAtLeast(240.0), followMode = false)
                            GraphicalView(
                                view = view,
                                controls = controls,
                                metrics = ViewStates.metrics,
                                events = RecordingViewEvents(),
                                showFullScreen = true,
                            )
                        },
                        rpmText = sample.rpm,
                    )
                }
            }
        }
    }

    private fun renderAll(machine: String, sample: () -> ReadoutSamples.Sample) {
        widths.forEach { width ->
            listOf("landscape" to width * 10 / 16, "portrait" to width * 16 / 10).forEach { (orientation, height) ->
                listOf(false, true).forEach { dark ->
                    val name = "readout-$machine-$width-$orientation-${if (dark) "dark" else "light"}"
                    val file = render(name, sample(), width, height, dark)
                    assertTrue(file.length() > 0, name)
                }
            }
        }
    }

    private fun render(name: String, sample: ReadoutSamples.Sample, width: Int, height: Int, dark: Boolean) =
        ScreenshotRenderer.render(name, widthDp = width, heightDp = height, frames = 30) {
            Screen(sample, WindowLayout.of(width.dp, height.dp), dark)
        }

    @Composable
    private fun Screen(sample: ReadoutSamples.Sample, layout: WindowLayout, dark: Boolean) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides layout) {
                ReadoutScreen(
                    state = sample.state,
                    axisValue = sample::flow,
                    events = NoReadoutEvents,
                    layout = layout,
                    sidePanelContent = { SidePanelPlaceholder(Res.string.route_sub_datums) },
                    graphicalViewPane = { SidePanelPlaceholder(Res.string.route_graphical_view) },
                    rpmText = sample.rpm,
                )
            }
        }
    }
}
