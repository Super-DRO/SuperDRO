package org.superdro.validation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.TransportKind
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.ui.FrameLayout
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.currentWindowLayout
import org.superdro.core.ui.readout.AxisValueUiState
import org.superdro.core.ui.readout.FunctionStripRules
import org.superdro.core.ui.readout.LinkStatus
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.ReadoutEvents
import org.superdro.core.ui.readout.ReadoutRowUiState
import org.superdro.core.ui.readout.ReadoutScreen
import org.superdro.core.ui.readout.ReadoutUiState
import org.superdro.core.ui.readout.RowKind
import org.superdro.core.ui.readout.SetupReminderUiState
import org.superdro.core.ui.readout.SidePanelUiState
import org.superdro.core.ui.readout.StatusStripUiState
import org.superdro.core.ui.readout.ValueState
import org.superdro.core.ui.tabs.DeviceTabUiState
import org.superdro.core.ui.tabs.DeviceTabsEvents
import org.superdro.core.ui.tabs.DeviceTabsUiState
import org.superdro.core.ui.tabs.TabStatusRules
import org.superdro.core.ui.theme.AppTheme

/** The test tag of the side panel content of the samples. */
const val SAMPLE_SIDE_PANEL_TAG: String = "sample-side-panel"

/** The test tag of the graphical view pane content of the samples. */
const val SAMPLE_GRAPHICAL_TAG: String = "sample-graphical-view"

/**
 * The UI states of the layout figures of the main screen. The values are the values of the
 * landscape figure: X +0125.430, Y -0012.005, Z +0003.100, α +0045.00, and 1250 RPM. The screenshot
 * tests and the previews build the `UiState` directly.
 */
object MainScreenSamples {

    private val buttons = listOf(AxisButtonMode.SetZero, AxisButtonMode.SetCenter, AxisButtonMode.ToggleIncremental)

    /** The rows of a mill with X, Y, Z, the angular axis, and the tachometer. */
    val millRows: List<ReadoutRowUiState> = listOf(
        ReadoutRowUiState(AxisId.X, "X", RowKind.Linear, buttons = buttons, collapsedButtons = buttons.take(1), slider = true),
        ReadoutRowUiState(AxisId.Y, "Y", RowKind.Linear, buttons = buttons, collapsedButtons = buttons.take(1), slider = true),
        ReadoutRowUiState(AxisId.Z, "Z", RowKind.Linear, buttons = buttons, collapsedButtons = buttons.take(1), slider = true),
        ReadoutRowUiState(AxisId.Angular, "α", RowKind.Angular, buttons = buttons, collapsedButtons = buttons.take(1)),
        ReadoutRowUiState(AxisId.Tachometer, "RPM", RowKind.Tachometer),
    )

    private val values: Map<AxisId, MutableStateFlow<AxisValueUiState>> = mapOf(
        AxisId.X to AxisValueUiState(AxisId.X, digits = "125.430", unit = DisplayUnit.Millimeter, state = ValueState.Normal, positionUm = 125_430.0),
        AxisId.Y to AxisValueUiState(AxisId.Y, digits = "12.005", negative = true, unit = DisplayUnit.Millimeter, state = ValueState.Normal, positionUm = -12_005.0),
        AxisId.Z to AxisValueUiState(AxisId.Z, digits = "3.100", unit = DisplayUnit.Millimeter, state = ValueState.Normal, positionUm = 3_100.0),
        AxisId.Angular to AxisValueUiState(AxisId.Angular, digits = "45.00", unit = DisplayUnit.Degrees, state = ValueState.Normal),
        AxisId.Tachometer to AxisValueUiState(AxisId.Tachometer, digits = "1250", unit = DisplayUnit.Rpm, state = ValueState.Normal),
    ).mapValues { (_, value) -> MutableStateFlow(value) }

    /** The RPM text of the status strip. */
    val rpm: StateFlow<String?> = MutableStateFlow("1250")

    /** Gives the value flow of [axis]. */
    fun value(axis: AxisId): StateFlow<AxisValueUiState> = values[axis] ?: MutableStateFlow(AxisValueUiState(axis))

    /** Sets the value of [axis] (a test of the value animation or of the semantic labels). */
    fun set(axis: AxisId, value: AxisValueUiState) {
        (values[axis] ?: error("No sample value of $axis")).value = value
    }

    /** The profile of the sample mill: all `show_*` switches on (the defaults). */
    val millProfile: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill")

    /** The readout state of the landscape figure. */
    fun mill(
        sidePanel: SidePanelMode = SidePanelMode.Off,
        placement: GraphicalViewPlacement = GraphicalViewPlacement.BelowReadout,
        reminder: Boolean = false,
        rows: List<ReadoutRowUiState> = millRows,
        functionSheetOpen: Boolean = false,
        measurement: Boolean = false,
    ): ReadoutUiState = ReadoutUiState(
        deviceId = DeviceId("mill"),
        loaded = true,
        machineType = MachineType.VerticalMill,
        status = StatusStripUiState(toolName = "3", subDatumName = "S2", link = LinkStatus.Connected, measurementMode = measurement),
        rows = rows,
        functions = FunctionStripRules.buttons(millProfile, millProfile.activeWorkspace),
        sidePanel = SidePanelUiState(mode = sidePanel, toolsEnabled = true, addVisible = sidePanel != SidePanelMode.Tools),
        graphicalViewPlacement = placement,
        setupReminder = if (reminder) SetupReminderUiState(AxisId.X) else null,
        keepScreenOn = true,
        functionSheetOpen = functionSheetOpen,
    )

    /** The tabs of the top level figure. */
    val tabs: DeviceTabsUiState = DeviceTabsUiState(
        tabs = listOf(
            DeviceTabUiState(DeviceId("mill"), "Mill", TransportKind.BluetoothSpp, SessionState.Connected(0L), TabStatusRules.of(SessionState.Connected(0L))),
            DeviceTabUiState(
                DeviceId("lathe"), "Lathe", TransportKind.UsbSerial,
                SessionState.Reconnecting(2, 0L, org.superdro.core.connection.LinkFailure.Lost),
                TabStatusRules.of(SessionState.Reconnecting(2, 0L, org.superdro.core.connection.LinkFailure.Lost)),
                inGrace = true,
            ),
            DeviceTabUiState(DeviceId("demo"), "Demo", TransportKind.Fake, SessionState.Connected(0L), TabStatusRules.of(SessionState.Connected(0L))),
        ),
        selected = DeviceId("mill"),
        rememberedCount = 3,
    )

    /** The events of a frame with no presenter. */
    val noTabEvents: DeviceTabsEvents = object : DeviceTabsEvents {
        override fun onSelect(id: DeviceId) = Unit
        override fun onOpenMenu(id: DeviceId) = Unit
        override fun onCloseMenu() = Unit
        override fun onPause(id: DeviceId) = Unit
        override fun onOpenOverflow() = Unit
        override fun onCloseOverflow() = Unit
        override fun onDisconnectAll() = Unit
        override fun onMessageShown(id: Long) = Unit
    }
}

/**
 * The main screen of one tab in the frame, as `App()` composes it: the theme, the window layout of
 * the window size class, the tab row, and the readout screen. The side panel and the graphical
 * view pane get marked boxes.
 */
@Composable
fun SampleMainScreen(
    state: ReadoutUiState,
    dark: Boolean,
    events: ReadoutEvents = NoReadoutEvents,
    fontScale: Float? = null,
    largeButtons: Boolean = false,
) {
    AppTheme(systemInDarkTheme = dark, largeButtons = largeButtons) {
        CompositionLocalProvider(LocalWindowLayout provides currentWindowLayout()) {
            val content = @Composable {
                FrameLayout(MainScreenSamples.tabs, MainScreenSamples.noTabEvents, {}, {}, {}, { _, _ -> }) { modifier ->
                    ReadoutScreen(
                        state = state,
                        axisValue = MainScreenSamples::value,
                        rpmText = MainScreenSamples.rpm,
                        events = events,
                        modifier = modifier,
                        sidePanelContent = { mode ->
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).testTag(SAMPLE_SIDE_PANEL_TAG),
                                contentAlignment = Alignment.Center,
                            ) { Text("Side panel: ${mode.name}") }
                        },
                        graphicalViewPane = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).testTag(SAMPLE_GRAPHICAL_TAG),
                                contentAlignment = Alignment.Center,
                            ) { Text("Graphical view") }
                        },
                    )
                }
            }
            if (fontScale == null) {
                content()
            } else {
                val density = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides Density(density.density, fontScale)) { content() }
            }
        }
    }
}

/**
 * Runs a Compose UI test in a window of [widthDp] × [heightDp] with 1 px for each dp, so that the
 * bounds of the nodes are in dp.
 */
@OptIn(ExperimentalTestApi::class)
fun runWindowTest(widthDp: Int, heightDp: Int, block: suspend SkikoComposeUiTest.() -> Unit) =
    runSkikoComposeUiTest(size = Size(widthDp.toFloat(), heightDp.toFloat()), density = Density(1f)) { block() }

/** The bounds of the node in the root, with no clip by the parents. */
fun SemanticsNodeInteraction.rect(): Rect {
    val node = fetchSemanticsNode()
    val position = node.positionInRoot
    return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
}
