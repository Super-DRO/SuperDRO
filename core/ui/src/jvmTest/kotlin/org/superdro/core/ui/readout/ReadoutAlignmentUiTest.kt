package org.superdro.core.ui.readout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisButtonConfig
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.theme.AppTheme

/** The value boxes of all the rows align, and a tap on a button does not move a value. */
@OptIn(ExperimentalTestApi::class)
class ReadoutAlignmentUiTest {

    private class NoNavigator : TabNavigator {
        override val deviceId: DeviceId = DeviceId("test")
        override fun push(route: TabRoute) = Unit
        override fun pop() = Unit
        override fun pop(route: TabRoute) = Unit
        override fun replaceTop(route: TabRoute) = Unit
        override fun popToRoot() = Unit
    }

    /** A mill with three different button sets: X with Hold, Y with no button, Z with the units button. */
    private fun profile(): MachineProfile = MachineProfile.create().let { mill ->
        mill.copy(
            metricDefault = true,
            x = mill.x.copy(buttons = listOf(AxisButtonMode.SetZero, AxisButtonMode.Hold, AxisButtonMode.ToggleIncremental).map(::AxisButtonConfig)),
            y = mill.y.copy(buttons = emptyList()),
            z = mill.z.copy(buttons = listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits).map(::AxisButtonConfig)),
        )
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).assertExists().getBoundsInRoot()

    private fun assertSameColumn(expected: DpRect, actual: DpRect, message: String) {
        assertTrue(abs((expected.left - actual.left).value) <= 1f, "$message: left ${expected.left} != ${actual.left}")
        assertTrue(abs((expected.right - actual.right).value) <= 1f, "$message: right ${expected.right} != ${actual.right}")
    }

    /**
     * Shows the readout in [layout]. A [graphicalPaneWeight] of 0.62 gives short rows with the
     * buttons next to the values. A weight of 0.15 gives tall rows with the buttons below the values.
     */
    private fun alignmentIn(width: Dp, height: Dp, graphicalPaneWeight: Float) = runComposeUiTest {
        val layout = WindowLayout.of(width, height)
        val live = LiveReadout(profile())
        live.inputs(AxisId.X to 25_000.0, AxisId.Y to 1_000.0, AxisId.Z to -3_000.0)
        setContent {
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
                CompositionLocalProvider(LocalWindowLayout provides layout) {
                    val state by live.presenter.uiState.collectAsState()
                    Box(Modifier.requiredSize(width, height)) {
                        ReadoutScreen(
                            state.copy(graphicalPaneWeight = graphicalPaneWeight),
                            live.presenter::axisValue,
                            PresenterReadoutEvents(live.presenter, NoNavigator()),
                            layout = layout,
                            graphicalViewPane = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        val x = bounds(valueTag(AxisId.X))
        assertSameColumn(x, bounds(valueTag(AxisId.Y)), "Y (no button) has the value box of X")
        assertSameColumn(x, bounds(valueTag(AxisId.Z)), "Z (other buttons) has the value box of X")

        val incTag = actionTag(AxisId.X, AxisButtonMode.ToggleIncremental)
        val unitsTag = actionTag(AxisId.Z, AxisButtonMode.ToggleUnits)
        val incBefore = bounds(incTag)
        val unitsBefore = bounds(unitsTag)
        onNodeWithTag(incTag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithTag(unitsTag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertSameColumn(incBefore, bounds(incTag), "The ABS and INC button keeps its width")
        assertSameColumn(unitsBefore, bounds(unitsTag), "The units button keeps its width")
        assertSameColumn(x, bounds(valueTag(AxisId.X)), "A tap does not move the value box of X")
        assertSameColumn(x, bounds(valueTag(AxisId.Z)), "A tap does not move the value box of Z")
        live.close()
    }

    @Test
    fun theValueBoxesAlignInLandscape() = alignmentIn(1280.dp, 800.dp, graphicalPaneWeight = 0.62f)

    @Test
    fun theValueBoxesAlignInShortPortraitRows() = alignmentIn(800.dp, 1200.dp, graphicalPaneWeight = 0.62f)

    @Test
    fun theValueBoxesAlignInTallPortraitRows() = alignmentIn(800.dp, 1200.dp, graphicalPaneWeight = 0.15f)
}
