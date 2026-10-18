package org.superdro.core.ui.readout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.readout.keypad.KEYPAD_RESULT_TAG
import org.superdro.core.ui.readout.keypad.KEYPAD_SET_TAG
import org.superdro.core.ui.readout.keypad.KeypadKey
import org.superdro.core.ui.readout.keypad.KeypadPresenter
import org.superdro.core.ui.readout.keypad.KeypadSheet
import org.superdro.core.ui.readout.keypad.keyTag
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the readout screen. */
@OptIn(ExperimentalTestApi::class)
class ReadoutScreenUiTest {

    private val landscape = WindowLayout.of(1280.dp, 800.dp)

    /** A back stack of a tab that records the routes. */
    private class RecordingNavigator : TabNavigator {
        val pushed = mutableListOf<TabRoute>()
        override val deviceId: DeviceId = DeviceId("test")
        override fun push(route: TabRoute) {
            pushed += route
        }
        override fun pop() = Unit
        override fun pop(route: TabRoute) = Unit
        override fun replaceTop(route: TabRoute) = Unit
        override fun popToRoot() = Unit
    }

    @Composable
    private fun Themed(layout: WindowLayout = landscape, largeButtons: Boolean = false, content: @Composable () -> Unit) {
        AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true, largeButtons = largeButtons) {
            CompositionLocalProvider(LocalWindowLayout provides layout) { content() }
        }
    }

    private fun ComposeUiTest.top(axis: AxisId): Float = onNodeWithTag(rowTag(axis)).assertExists().getBoundsInRoot().top.value

    @Test
    fun oneRowForEachEnabledAxisInTheMillOrder() = runComposeUiTest {
        val sample = ReadoutSamples.mill()
        setContent { Themed { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = landscape) } }
        val order = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular, AxisId.Tachometer)
        val tops = order.map { axis -> top(axis) }
        assertEquals(tops.sorted(), tops, "Mill: X, Y, Z, then the angular row and the tachometer row.")
        onNodeWithTag(rowTag(AxisId.Caliper)).assertDoesNotExist()
    }

    @Test
    fun aPortraitWindowOf840DpOrMoreUsesTheExpandedLayout() = runComposeUiTest {
        // The window class comes from the width only.
        val sample = ReadoutSamples.mill()
        setContent {
            Themed {
                ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = WindowLayout.of(900.dp, 1440.dp), graphicalViewPane = {})
            }
        }
        onNodeWithTag(SUPPORTING_PANE_TAG).assertExists()
    }

    @Test
    fun oneRowForEachEnabledAxisInTheLatheOrder() = runComposeUiTest {
        val sample = ReadoutSamples.lathe()
        setContent { Themed { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = landscape) } }
        val tops = listOf(AxisId.X, AxisId.Z, AxisId.Y, AxisId.Tachometer).map { axis -> top(axis) }
        assertEquals(tops.sorted(), tops, "Lathe: X, Z, then the auxiliary readout.")
        onNodeWithTag(rowTag(AxisId.Angular)).assertDoesNotExist()
        onNodeWithText("Auxiliary readout").assertExists()
    }

    @Test
    fun eachValueHasASemanticLabelWithItsUnit() = runComposeUiTest {
        val sample = ReadoutSamples.mill()
        setContent { Themed { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = landscape) } }
        // The X axis has a tool offset: the state follows the value.
        onNodeWithContentDescription("X axis, 125.430 millimeters, Offset").assertExists()
        onNodeWithContentDescription("Y axis, -12.005 millimeters").assertExists()
        onNodeWithContentDescription("α axis, 45.00 degrees").assertExists()
        onNodeWithContentDescription("RPM axis, 1250 revolutions per minute").assertExists()
        // The display state has an icon and a text.
        onNodeWithText("Offset", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theErrorStateShowsItsText() = runComposeUiTest {
        val sample = ReadoutSamples.lathe()
        setContent { Themed { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = landscape) } }
        onNodeWithContentDescription("Y axis, Error").assertExists()
    }

    @Test
    fun aTapOnTheValueOpensTheKeypadAndChangesNoValue() = runComposeUiTest {
        val live = LiveReadout(MachineProfile.create().copy(metricDefault = true))
        live.inputs(AxisId.X to 12_000.0)
        val navigator = RecordingNavigator()
        setContent {
            Themed {
                val state by live.presenter.uiState.collectAsState()
                ReadoutScreen(state, live.presenter::axisValue, PresenterReadoutEvents(live.presenter, navigator), layout = landscape)
            }
        }
        onNodeWithTag(valueTag(AxisId.X)).performClick()
        waitForIdle()
        assertEquals(listOf<TabRoute>(KeypadRoute(AxisId.X)), navigator.pushed)
        val x = live.workspace().axisStates.getValue(AxisId.X)
        assertEquals(0.0, x.absoluteOffset)
        assertEquals(0.0, x.incrementalOffset)
        onNodeWithContentDescription("Calibration required").assertExists()
        live.close()
    }

    @Test
    fun theKeypadShowsTheResultBeforeSetAndSetsTheValue() = runComposeUiTest {
        val live = LiveReadout(MachineProfile.create(), states = mapOf(AxisId.X to AxisState(metric = false)))
        live.inputs(AxisId.X to 31_750.0)
        val keypad = KeypadPresenter(AxisId.X, live.presenter)
        var closed = false
        setContent {
            Themed(layout = WindowLayout.of(800.dp, 1280.dp)) {
                val state by keypad.uiState.collectAsState()
                val value by live.presenter.axisValue(AxisId.X).collectAsState()
                KeypadSheet(state, "X", value, keypad::onTextChange, keypad::onKey, onSet = { if (keypad.onSet()) closed = true }, onDismiss = {})
            }
        }
        listOf(KeypadKey.RelativeAdd, KeypadKey.Digit0, KeypadKey.Dot, KeypadKey.Digit5).forEach { key -> onNodeWithTag(keyTag(key)).performClick() }
        waitForIdle()
        assertEquals("++0.5", keypad.uiState.value.text)
        onNodeWithTag(KEYPAD_RESULT_TAG).assertTextEquals("Result: 1.7500 in")
        onNodeWithTag(keyTag(KeypadKey.Times)).assertWidthIsAtLeast(72.dp).assertHeightIsAtLeast(72.dp)
        onNodeWithTag(KEYPAD_SET_TAG).performClick()
        waitForIdle()
        assertTrue(closed)
        assertEquals(44_450.0 - 31_750.0, live.workspace().axisStates.getValue(AxisId.X).absoluteOffset, 1e-9)
        live.close()
    }

    @Test
    fun theUndoSnackbarShowsFor10SecondsAndRestoresTheOffsets() = runComposeUiTest {
        val live = LiveReadout(MachineProfile.create().copy(metricDefault = true))
        live.inputs(AxisId.X to 10_000.0)
        setContent {
            Themed {
                val host = remember { SnackbarHostState() }
                val state by live.presenter.uiState.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    ReadoutScreen(state, live.presenter::axisValue, PresenterReadoutEvents(live.presenter, RecordingNavigator()), layout = landscape)
                    SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                }
                UndoSnackbar(state.undo, host, live.presenter)
            }
        }
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).performClick()
        waitForIdle()
        onNodeWithText("X: zero set").assertExists()
        onNodeWithText("Undo").assertExists()
        live.scheduler.advanceTimeBy(ReadoutPresenter.UNDO_MS - 1)
        live.scheduler.runCurrent()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("X: zero set").assertExists()
        live.scheduler.advanceTimeBy(1)
        live.scheduler.runCurrent()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithText("X: zero set").assertDoesNotExist()

        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).performClick()
        waitForIdle()
        assertEquals(-10_000.0, live.workspace().axisStates.getValue(AxisId.X).incrementalOffset)
        live.inputs(AxisId.X to 15_000.0)
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).performClick()
        waitForIdle()
        onNodeWithText("Undo").performClick()
        waitForIdle()
        assertEquals(-10_000.0, live.workspace().axisStates.getValue(AxisId.X).incrementalOffset, "The undo restores the offset before the action.")
        live.close()
    }

    @Test
    fun theHoldDisplayKeepsTheHeldValue() = runComposeUiTest {
        val holdX = MachineProfile.create().let { mill ->
            mill.copy(metricDefault = true, x = mill.x.copy(buttons = listOf(AxisButtonConfig(AxisButtonMode.Hold), AxisButtonConfig(AxisButtonMode.SetZero))))
        }
        val live = LiveReadout(holdX)
        live.inputs(AxisId.X to 25_000.0)
        setContent {
            Themed {
                val state by live.presenter.uiState.collectAsState()
                ReadoutScreen(state, live.presenter::axisValue, PresenterReadoutEvents(live.presenter, RecordingNavigator()), layout = landscape)
            }
        }
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.Hold)).performClick()
        waitForIdle()
        live.inputs(AxisId.X to 30_000.0)
        waitForIdle()
        onNodeWithContentDescription("X axis, 25.000 millimeters, Hold").assertExists()
        onNodeWithContentDescription("X: Set held value").assertExists()
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.Hold)).performClick()
        waitForIdle()
        onNodeWithContentDescription("X axis, 25.000 millimeters").assertExists()
        live.close()
    }

    @Test
    fun theButtonsHaveTheTouchTargetsOfTheShopFloor() = runComposeUiTest {
        val sample = ReadoutSamples.mill()
        // The portrait layout shows all the action buttons of a row. The narrow readout column of
        // the landscape layout shows only the collapsed buttons.
        val portrait = WindowLayout.of(800.dp, 1280.dp)
        setContent { Themed(portrait) { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = portrait) } }
        listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits).forEach { mode ->
            onNodeWithTag(actionTag(AxisId.X, mode)).assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(64.dp)
        }
        listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG, functionTag(FunctionButton.ZReference), ADD_POINT_TAG).forEach { tag ->
            onNodeWithTag(tag).assertWidthIsAtLeast(64.dp).assertHeightIsAtLeast(64.dp)
        }
    }

    @Test
    fun largeButtonsAdd25Percent() = runComposeUiTest {
        val sample = ReadoutSamples.mill()
        setContent { Themed(largeButtons = true) { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = landscape) } }
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).assertWidthIsAtLeast(80.dp).assertHeightIsAtLeast(80.dp)
        onNodeWithTag(ZERO_SET_TAG).assertHeightIsAtLeast(80.dp)
    }

    @Test
    fun theFunctionStripShowsTheMillButtonsOnAHorizontalMill() = runComposeUiTest {
        val sample = ReadoutSamples.mill(machineType = MachineType.HorizontalMill)
        setContent { Themed { ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = landscape) } }
        onNodeWithTag(functionTag(FunctionButton.ZReference)).assertExists()
        onNodeWithTag(functionTag(FunctionButton.EdgeFinder)).assertExists()
        onNodeWithTag(functionTag(FunctionButton.RadiusDiameter)).assertDoesNotExist()
        onNodeWithContentDescription("Radius comp.").assertExists()
    }

    @Test
    fun draggingTheSplitHandleInExpandedLayoutResizesReadoutAndViewerBothWaysAndReordersUi() = runComposeUiTest {
        val sample = ReadoutSamples.mill()
        var state by mutableStateOf(sample.state)
        val events = object : ReadoutEvents {
            override fun onReadoutPaneFractionChange(fraction: Float) {
                state = state.copy(readoutPaneFraction = fraction.coerceIn(READOUT_PANE_MIN_FRACTION, READOUT_PANE_MAX_FRACTION))
            }
        }
        setContent {
            Themed(landscape) {
                ReadoutScreen(state, sample::flow, events, layout = landscape, graphicalViewPane = {})
            }
        }

        val initialRowBounds = onNodeWithTag(rowTag(AxisId.X)).assertExists().getBoundsInRoot()
        val initialSupportingBounds = onNodeWithTag(SUPPORTING_PANE_TAG).assertExists().getBoundsInRoot()
        val handleBounds = onNodeWithTag(SPLIT_HANDLE_TAG).assertExists().getBoundsInRoot()
        assertTrue(
            handleBounds.right >= initialRowBounds.right - 16.dp && handleBounds.left <= initialSupportingBounds.left + 16.dp,
            "The split handle sits between the readout rows and the supporting pane.",
        )

        // Initially at 45% width (< 600 dp), the readout rows use collapsed buttons (only SetZero).
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).assertExists()
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.ToggleIncremental)).assertDoesNotExist()
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.ToggleUnits)).assertDoesNotExist()

        val stripButtons = listOf(
            ZERO_SET_TAG,
            MODE_TAG,
            UNITS_TAG,
            functionTag(FunctionButton.ZReference),
            functionTag(FunctionButton.RadiusCompensation),
            functionTag(FunctionButton.HeightCompensation),
            functionTag(FunctionButton.EdgeFinder),
        )
        fun firstRowColumnCount(): Int {
            val top0 = onNodeWithTag(ZERO_SET_TAG).getBoundsInRoot().top.value
            return stripButtons.count { tag ->
                abs(onNodeWithTag(tag).getBoundsInRoot().top.value - top0) < 4f
            }
        }
        val initialColumns = firstRowColumnCount()

        // Drag the split handle to the left: readout becomes narrower and supporting pane becomes wider.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-150f, 0f))
            up()
        }
        waitForIdle()
        val narrowRowBounds = onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot()
        val wideSupportingBounds = onNodeWithTag(SUPPORTING_PANE_TAG).getBoundsInRoot()
        assertTrue(narrowRowBounds.width < initialRowBounds.width, "Dragging left narrows the readout column.")
        assertTrue(wideSupportingBounds.width > initialSupportingBounds.width, "Dragging left widens the supporting pane.")

        // Drag the split handle to the right so readout width > 600 dp:
        // readout widens, supporting pane narrows, all 3 row action buttons appear, and function strip reorders into more columns.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(500f, 0f))
            up()
        }
        waitForIdle()
        val wideRowBounds = onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot()
        val narrowSupportingBounds = onNodeWithTag(SUPPORTING_PANE_TAG).getBoundsInRoot()
        assertTrue(wideRowBounds.width > initialRowBounds.width, "Dragging right widens the readout column.")
        assertTrue(narrowSupportingBounds.width < initialSupportingBounds.width, "Dragging right narrows the supporting pane.")
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).assertExists()
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.ToggleIncremental)).assertExists()
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.ToggleUnits)).assertExists()
        val expandedColumns = firstRowColumnCount()
        assertTrue(expandedColumns > initialColumns, "Widening the readout column reorders the function strip into more columns.")

        // Dragging far past the right and left screen edges clamps to the maximum and minimum pane limits.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(2000f, 0f))
            up()
        }
        waitForIdle()
        assertTrue(state.readoutPaneFraction <= READOUT_PANE_MAX_FRACTION + 1e-3f)
        val clampedMaxWidth = onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot().width
        assertTrue(onNodeWithTag(SUPPORTING_PANE_TAG).getBoundsInRoot().width >= MIN_SUPPORTING_PANE_WIDTH - 2.dp)
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(1000f, 0f))
            up()
        }
        waitForIdle()
        assertEquals(clampedMaxWidth, onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot().width)

        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-2000f, 0f))
            up()
        }
        waitForIdle()
        assertTrue(state.readoutPaneFraction >= READOUT_PANE_MIN_FRACTION - 1e-3f)
        val clampedMinWidth = onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot().width
        assertTrue(clampedMinWidth >= MIN_READOUT_PANE_WIDTH - 2.dp)
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-1000f, 0f))
            up()
        }
        waitForIdle()
        assertEquals(clampedMinWidth, onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot().width)
    }

    @Test
    fun draggingTheSplitHandleInPortraitLayoutResizesReadoutAndViewerVertically() = runComposeUiTest {
        val portrait = WindowLayout.of(800.dp, 1280.dp)
        val sample = ReadoutSamples.mill()
        var state by mutableStateOf(sample.state)
        val events = object : ReadoutEvents {
            override fun onGraphicalPaneWeightChange(weight: Float) {
                state = state.copy(graphicalPaneWeight = weight.coerceIn(GRAPHICAL_PANE_MIN_WEIGHT, GRAPHICAL_PANE_MAX_WEIGHT))
            }
        }
        setContent {
            Themed(portrait) {
                ReadoutScreen(state, sample::flow, events, layout = portrait, graphicalViewPane = {})
            }
        }

        val initialRowBounds = onNodeWithTag(rowTag(AxisId.X)).assertExists().getBoundsInRoot()
        val initialGraphicalBounds = onNodeWithTag(GRAPHICAL_PANE_TAG).assertExists().getBoundsInRoot()
        onNodeWithTag(SPLIT_HANDLE_TAG).assertExists()

        // Dragging the split handle downward increases readout height and decreases graphical viewer height.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 150f))
            up()
        }
        waitForIdle()
        val tallerRowBounds = onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot()
        val shorterGraphicalBounds = onNodeWithTag(GRAPHICAL_PANE_TAG).getBoundsInRoot()
        assertTrue(tallerRowBounds.height > initialRowBounds.height, "Dragging down increases readout row height.")
        assertTrue(shorterGraphicalBounds.height < initialGraphicalBounds.height, "Dragging down decreases graphical pane height.")

        // Dragging the split handle upward decreases readout height and increases graphical viewer height.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -250f))
            up()
        }
        waitForIdle()
        val shorterRowBounds = onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot()
        val tallerGraphicalBounds = onNodeWithTag(GRAPHICAL_PANE_TAG).getBoundsInRoot()
        assertTrue(shorterRowBounds.height < tallerRowBounds.height, "Dragging up decreases readout row height.")
        assertTrue(tallerGraphicalBounds.height > shorterGraphicalBounds.height, "Dragging up increases graphical pane height.")

        // Dragging far past the bottom and top limits clamps within the minimum and maximum height bounds.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 2000f))
            up()
        }
        waitForIdle()
        assertTrue(state.graphicalPaneWeight >= GRAPHICAL_PANE_MIN_WEIGHT - 1e-3f)
        val clampedMinGraphicalHeight = onNodeWithTag(GRAPHICAL_PANE_TAG).getBoundsInRoot().height
        assertTrue(clampedMinGraphicalHeight >= MIN_GRAPHICAL_PANE_HEIGHT - 2.dp)

        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -2000f))
            up()
        }
        waitForIdle()
        assertTrue(state.graphicalPaneWeight <= GRAPHICAL_PANE_MAX_WEIGHT + 1e-3f)
        val clampedMaxGraphicalHeight = onNodeWithTag(GRAPHICAL_PANE_TAG).getBoundsInRoot().height
        assertTrue(clampedMaxGraphicalHeight > clampedMinGraphicalHeight)
    }

    @Test
    fun draggingTheSplitHandlePersistsTheResizeValuesGloballyAndInTheMachineProfile() = runComposeUiTest {
        val live = LiveReadout(MachineProfile.create().copy(metricDefault = true))
        val navigator = RecordingNavigator()
        var currentLayout by mutableStateOf(landscape)
        setContent {
            Themed(currentLayout) {
                val state by live.presenter.uiState.collectAsState()
                ReadoutScreen(
                    state = state,
                    axisValue = live.presenter::axisValue,
                    events = PresenterReadoutEvents(live.presenter, navigator),
                    layout = currentLayout,
                    graphicalViewPane = {},
                )
            }
        }

        // Multi-step horizontal drag in expanded layout persists readoutPaneFraction both globally and to the profile.
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(18f, 0f)) }
            up()
        }
        waitForIdle()
        live.scheduler.advanceUntilIdle()
        waitForIdle()
        val afterExpandedProfile = kotlinx.coroutines.runBlocking { live.machines.loadProfile(live.profile.id) }!!
        val afterExpandedDrag = afterExpandedProfile.display.readoutPaneFraction
        assertTrue(afterExpandedDrag > READOUT_PANE_FRACTION + 0.08f, "Expected persisted readoutPaneFraction > ${READOUT_PANE_FRACTION + 0.08f}, got $afterExpandedDrag")
        assertEquals(afterExpandedDrag, live.appSettings.settings.value.readoutPaneFraction, 1e-4f)

        // Switch to portrait and perform a multi-step vertical drag to persist graphicalPaneWeight.
        currentLayout = WindowLayout.of(800.dp, 1280.dp)
        waitForIdle()
        onNodeWithTag(SPLIT_HANDLE_TAG).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 16f)) }
            up()
        }
        waitForIdle()
        live.scheduler.advanceUntilIdle()
        waitForIdle()
        val afterPortraitProfile = kotlinx.coroutines.runBlocking { live.machines.loadProfile(live.profile.id) }!!
        val afterPortraitDrag = afterPortraitProfile.display.graphicalPaneWeight
        assertTrue(afterPortraitDrag < GRAPHICAL_PANE_WEIGHT - 0.05f, "Expected persisted graphicalPaneWeight < ${GRAPHICAL_PANE_WEIGHT - 0.05f}, got $afterPortraitDrag")
        assertEquals(afterPortraitDrag, live.appSettings.settings.value.graphicalPaneWeight, 1e-4f)
        live.close()
    }

    @Test
    fun touchingZeroOrIncShowsADismissableTooltipAnchoredToTheButtonWithoutShiftingReadoutRows() = runComposeUiTest {
        val portrait = WindowLayout.of(800.dp, 1280.dp)
        val live = LiveReadout(MachineProfile.create().copy(metricDefault = true))
        live.inputs(AxisId.X to 10_000.0)
        val navigator = RecordingNavigator()
        setContent {
            Themed(portrait) {
                val state by live.presenter.uiState.collectAsState()
                ReadoutScreen(
                    state = state,
                    axisValue = live.presenter::axisValue,
                    events = PresenterReadoutEvents(live.presenter, navigator),
                    layout = portrait,
                )
            }
        }

        val initialRowBounds = onNodeWithTag(rowTag(AxisId.X)).assertExists().getBoundsInRoot()
        val zeroButtonTag = actionTag(AxisId.X, AxisButtonMode.SetZero)
        val zeroButtonBounds = onNodeWithTag(zeroButtonTag).assertExists().getBoundsInRoot()

        // Tap 0: shows a compact tooltip anchored below the 0 button without shifting the readout row.
        onNodeWithTag(zeroButtonTag).performClick()
        waitForIdle()
        onNodeWithText("Press and hold to set the absolute zero.").assertExists()
        val zeroTooltipBounds = onNodeWithTag(MESSAGE_LINE_TAG).assertExists().getBoundsInRoot()
        assertEquals(initialRowBounds, onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot(), "Showing the tooltip must not shift or resize readout rows.")
        assertTrue(zeroTooltipBounds.width < 450.dp, "Tooltip is compact, not a full-width banner.")
        assertTrue(zeroTooltipBounds.top >= zeroButtonBounds.bottom, "Tooltip sits below the upper-screen 0 button.")
        val zeroCenterX = (zeroButtonBounds.left + zeroButtonBounds.right) / 2
        assertTrue(zeroCenterX in zeroTooltipBounds.left..zeroTooltipBounds.right, "Tooltip horizontally spans the pressed 0 button.")

        // Dismiss via the close button.
        onNodeWithTag(MESSAGE_DISMISS_TAG).performClick()
        waitForIdle()
        onNodeWithTag(MESSAGE_LINE_TAG).assertDoesNotExist()

        // Tap INC/ABS: shows a compact tooltip anchored below the INC/ABS button, dismissable by tapping the tooltip.
        val incButtonTag = actionTag(AxisId.X, AxisButtonMode.ToggleIncremental)
        val incButtonBounds = onNodeWithTag(incButtonTag).assertExists().getBoundsInRoot()
        onNodeWithTag(incButtonTag).performClick()
        waitForIdle()
        onNodeWithText("Press and hold to clear the offsets.").assertExists()
        val incTooltipBounds = onNodeWithTag(MESSAGE_LINE_TAG).assertExists().getBoundsInRoot()
        assertEquals(initialRowBounds, onNodeWithTag(rowTag(AxisId.X)).getBoundsInRoot())
        assertTrue(incTooltipBounds.top >= incButtonBounds.bottom)
        val incCenterX = (incButtonBounds.left + incButtonBounds.right) / 2
        assertTrue(incCenterX in incTooltipBounds.left..incTooltipBounds.right)

        onNodeWithTag(MESSAGE_LINE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(MESSAGE_LINE_TAG).assertDoesNotExist()
        live.close()
    }

    @Test
    fun theSetupReminderIndicatorOpensTheDialogWithSettingsAndCalibrate() = runComposeUiTest {
        val live = LiveReadout(MachineProfile.create().copy(metricDefault = true))
        val navigator = RecordingNavigator()
        setContent {
            Themed(landscape) {
                val state by live.presenter.uiState.collectAsState()
                ReadoutScreen(
                    state = state,
                    axisValue = live.presenter::axisValue,
                    events = PresenterReadoutEvents(live.presenter, navigator),
                    layout = landscape,
                )
            }
        }

        val statusBounds = onNodeWithTag(STATUS_STRIP_TAG).assertExists().getBoundsInRoot()
        val indicatorBounds = onNodeWithTag(SETUP_REMINDER_TAG).assertExists().getBoundsInRoot()
        assertTrue(
            indicatorBounds.top >= statusBounds.top && indicatorBounds.bottom <= statusBounds.bottom,
            "The setup reminder indicator sits inside the status strip without adding a banner row.",
        )
        onNodeWithContentDescription("Calibration required").assertExists()

        // Tap the yellow warning indicator: opens the Calibration required dialog.
        onNodeWithTag(SETUP_REMINDER_TAG).performClick()
        waitForIdle()
        onNodeWithText("Calibration required").assertExists()
        onNodeWithText("An enabled axis has an input that is not calibrated.").assertExists()
        onNodeWithText("Settings").performClick()
        waitForIdle()
        onNodeWithText("An enabled axis has an input that is not calibrated.").assertDoesNotExist()
        assertEquals(
            listOf<TabRoute>(org.superdro.core.ui.navigation.MachineSettingsRoute),
            navigator.pushed,
        )

        // Reopen the dialog and tap Calibrate.
        onNodeWithTag(SETUP_REMINDER_TAG).performClick()
        waitForIdle()
        onNodeWithText("Calibrate").performClick()
        waitForIdle()
        onNodeWithText("An enabled axis has an input that is not calibrated.").assertDoesNotExist()
        assertEquals(
            listOf<TabRoute>(
                org.superdro.core.ui.navigation.MachineSettingsRoute,
                org.superdro.core.ui.navigation.CalibrationWizardRoute(AxisId.X),
            ),
            navigator.pushed,
        )

        // Reopen the dialog and turn off showSetupReminder: dialog and indicator both disappear.
        onNodeWithTag(SETUP_REMINDER_TAG).performClick()
        waitForIdle()
        onNodeWithText("An enabled axis has an input that is not calibrated.").assertExists()
        kotlinx.coroutines.runBlocking {
            live.appSettings.update { settings -> settings.copy(showSetupReminder = false) }
        }
        live.scheduler.advanceUntilIdle()
        waitForIdle()
        onNodeWithTag(SETUP_REMINDER_TAG).assertDoesNotExist()
        onNodeWithText("An enabled axis has an input that is not calibrated.").assertDoesNotExist()
        live.close()
    }

    @Test
    fun tallPortraitRowsStackActionButtonsBelowTheValueBox() = runComposeUiTest {
        val portrait = WindowLayout.of(800.dp, 1200.dp)
        val sample = ReadoutSamples.mill()
        var state by mutableStateOf(sample.state.copy(graphicalPaneWeight = 0.62f))
        setContent {
            Themed(portrait) {
                Box(Modifier.requiredSize(800.dp, 1200.dp)) {
                    ReadoutScreen(state, sample::flow, NoReadoutEvents, layout = portrait, graphicalViewPane = {})
                }
            }
        }
        waitForIdle()
        val shortValueBounds = onNodeWithTag(valueTag(AxisId.X)).assertExists().getBoundsInRoot()
        val shortZeroBounds = onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).assertExists().getBoundsInRoot()
        assertTrue(shortZeroBounds.left >= shortValueBounds.right, "Short rows place action buttons beside the value box.")

        state = state.copy(graphicalPaneWeight = 0.15f)
        waitForIdle()
        val tallValueBounds = onNodeWithTag(valueTag(AxisId.X)).getBoundsInRoot()
        val tallZeroBounds = onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).getBoundsInRoot()
        assertTrue(tallValueBounds.width > shortValueBounds.width * 1.4f, "Tall rows expand the value box across the full width.")
        assertTrue(tallZeroBounds.top >= tallValueBounds.bottom - 1.dp, "Tall rows place action buttons in the bottom band below the value box.")
    }
}

/** The key feedback of the readout buttons and the mute switch. */
@OptIn(ExperimentalTestApi::class)
class ReadoutFeedbackUiTest {

    @Test
    fun aTapPlaysTheShortSoundAndALongPressPlaysTheLongSound() = runComposeUiTest {
        val sounds = RecordingSounds()
        val settings = kotlinx.coroutines.flow.MutableStateFlow(org.superdro.core.model.AppSettings())
        val controller = org.superdro.core.ui.sound.SoundController(sounds, settings, kotlinx.coroutines.flow.MutableStateFlow(emptySet()), org.superdro.core.util.Clock { 0L })
        val sample = ReadoutSamples.mill()
        setContent {
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
                CompositionLocalProvider(org.superdro.core.ui.sound.LocalSoundController provides controller) {
                    CompositionLocalProvider(LocalReadoutFeedback provides org.superdro.core.ui.sound.rememberKeyFeedback(sample.state.deviceId)) {
                        ReadoutScreen(sample.state, sample::flow, NoReadoutEvents, layout = WindowLayout.of(1280.dp, 800.dp))
                    }
                }
            }
        }
        onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).performClick()
        waitForIdle()
        assertEquals(listOf(org.superdro.core.ui.sound.UiSound.Short), sounds.played)
        onNodeWithTag(ZERO_SET_TAG).performTouchInput { longClick() }
        waitForIdle()
        assertEquals(org.superdro.core.ui.sound.UiSound.Long, sounds.played.last())
        settings.value = org.superdro.core.model.AppSettings(muteAllSounds = true)
        val before = sounds.played.size
        onNodeWithTag(UNITS_TAG).performClick()
        waitForIdle()
        assertEquals(before, sounds.played.size, "The app mute switch mutes the key sounds.")
    }
}
