package org.superdro.core.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.resources.Res
import org.superdro.core.resources.route_about
import org.superdro.core.resources.route_hole_circle
import org.superdro.core.ui.presenter.Presenter
import org.superdro.core.ui.presenter.rememberPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** The one `NavDisplay` of the selected tab and the global stack. */
@OptIn(ExperimentalTestApi::class)
class NavigationHostTest {

    private class Probe(val name: String) : Presenter {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeReadout(override val deviceId: DeviceId) : TabReadoutPresenter {
        var closed = false
        override val frame: StateFlow<ReadoutFrame?> = MutableStateFlow(null)
        override val sessionState: StateFlow<SessionState?> = MutableStateFlow(null)
        override fun positionUnavailable(): UiText? = null
        override fun apply(result: OperationResult, label: UiText?) = Unit
        override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) = Unit
        override fun showMessage(text: UiText) = Unit
        override fun close() {
            closed = true
        }
    }

    private val mill = DeviceId("mill")
    private val lathe = DeviceId("lathe")

    private fun registry(probes: MutableList<Probe>, readouts: MutableMap<DeviceId, FakeReadout>) = appEntryRegistry().apply {
        tab<ReadoutRoute>(EntryKind.Screen, RouteArea.Frame) { _ ->
            val probe = rememberPresenter { Probe("root ${deviceId.value}").also(probes::add) }
            readout
            Text("Readout of ${probe.name}")
        }
        // The readout presenter of the tab is one object for all entries.
        readouts.size
        // The real hole circle entry needs the DI graph. The test uses the placeholder.
        tabPlaceholder(HoleCircleRoute::class, EntryKind.Screen, RouteArea.Functions, Res.string.route_hole_circle)
    }

    @Test
    fun theHostShowsTheStackOfTheSelectedTabAndKeepsTheOtherStack() = runComposeUiTest {
        val holder = NavigationHolder()
        val probes = mutableListOf<Probe>()
        val readouts = mutableMapOf<DeviceId, FakeReadout>()
        var selected by mutableStateOf<DeviceId?>(mill)
        val registry = registry(probes, readouts)
        setContent {
            AppTheme(systemInDarkTheme = false) {
                NavigationHost(
                    holder = holder,
                    registry = registry,
                    tabIds = listOf(mill, lathe),
                    selected = selected,
                    readoutFactory = { id -> FakeReadout(id).also { readouts[id] = it } },
                    showMessage = {},
                )
            }
        }
        onNodeWithText("Readout of root mill").assertIsDisplayed()
        selected = lathe
        waitForIdle()
        onNodeWithText("Readout of root lathe").assertIsDisplayed()
        selected = mill
        waitForIdle()
        onNodeWithText("Readout of root mill").assertIsDisplayed()
        assertEquals(listOf("root mill", "root lathe"), probes.map { it.name }, "A tab change keeps the presenter of each root")
        assertEquals(setOf(mill, lathe), readouts.keys)
    }

    @Test
    fun aSheetShowsAboveTheScreenAndItsPresenterClosesWithTheEntry() = runComposeUiTest {
        val holder = NavigationHolder()
        val probes = mutableListOf<Probe>()
        val readouts = mutableMapOf<DeviceId, FakeReadout>()
        val registry = registry(probes, readouts).apply {
            tab<TachometerPresetRoute>(EntryKind.Sheet, RouteArea.Frame) { _ ->
                rememberPresenter { Probe("sheet").also(probes::add) }
                Text("The sheet")
            }
            // The real about screen needs the DI graph. This test examines only the placeholder.
            globalPlaceholder(AboutRoute::class, EntryKind.Screen, RouteArea.Settings, Res.string.route_about)
        }
        var tabs by mutableStateOf(listOf(mill))
        setContent {
            AppTheme(systemInDarkTheme = true) {
                NavigationHost(holder, registry, tabs, mill, { id -> FakeReadout(id).also { readouts[id] = it } }, {})
            }
        }
        runOnIdle { holder.tab(mill).push(TachometerPresetRoute) }
        onNodeWithText("The sheet").assertIsDisplayed()
        onNodeWithText("Readout of root mill").assertIsDisplayed()
        val sheet = probes.single { it.name == "sheet" }
        runOnIdle { holder.back(mill) }
        waitForIdle()
        assertTrue(sheet.closed)
        // A placeholder of an area shows "This screen is under construction".
        runOnIdle { holder.tab(mill).push(HoleCircleRoute) }
        onNodeWithText("This screen is under construction.").assertIsDisplayed()
        runOnIdle { holder.push(AboutRoute) }
        waitForIdle()
        assertEquals(1, onAllNodesWithTag(PLACEHOLDER_TAG).fetchSemanticsNodes().size)
        // The tab goes away: its presenters and its readout close.
        tabs = emptyList()
        runOnIdle { holder.clear() }
        waitForIdle()
        assertTrue(probes.single { it.name == "root mill" }.closed)
        assertTrue(readouts.getValue(mill).closed)
    }
}
