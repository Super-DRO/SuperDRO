package org.superdro.core.ui.settings.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.model.MuteScope
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.components.settingsGroupTag
import org.superdro.core.ui.tabs.RecordingRequests
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/** The main flows of the app settings screen. */
@OptIn(ExperimentalTestApi::class)
class AppSettingsUiTest {

    private class Graph(val scope: TestScope) {
        val store = UiStoreFixture(scope)
        val battery = FakeBatteryOptimization(exempt = false)
        val requests = RecordingRequests()
        val presenter = AppSettingsPresenter(store.appSettings, battery, requests, scope.testPresenterContext())
        val opened = mutableListOf<String>()

        fun close() {
            presenter.close()
            store.close()
        }
    }

    private fun ComposeUiTest.show(graph: Graph, window: SettingsWindow = SettingsWindow.Landscape) {
        graph.scope.testScheduler.advanceUntilIdle()
        setContent {
            SettingsThemed(dark = false, window = window) {
                val state by graph.presenter.uiState.collectAsState()
                AppSettingsScreen(
                    state = state,
                    presenter = graph.presenter,
                    links = AppSettingsLinks(onRememberedMachines = { graph.opened += "remembered" }, onKeyMapping = { graph.opened += "keys" }),
                    onBack = { graph.opened += "back" },
                )
            }
        }
    }

    private fun ComposeUiTest.settle(graph: Graph) {
        repeat(3) {
            waitForIdle()
            graph.scope.testScheduler.advanceUntilIdle()
        }
        waitForIdle()
    }

    @Test
    fun aTapOnASwitchRowWritesTheSettingAtOnce() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        onNodeWithTag(AppSettingsTags.LARGE_BUTTONS).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        assertTrue(graph.store.appSettings.settings.value.largeButtons)
        onNodeWithTag(AppSettingsTags.FULL_SCREEN).performClick()
        settle(graph)
        assertEquals(false, graph.store.appSettings.settings.value.fullScreen)
        onNodeWithText("Each machine").performScrollTo().performClick()
        settle(graph)
        assertEquals(org.superdro.core.model.PaneSizeScope.EachMachine, graph.store.appSettings.settings.value.paneSizeScope)
        onNodeWithText("All machines").performScrollTo().performClick()
        settle(graph)
        assertEquals(org.superdro.core.model.PaneSizeScope.App, graph.store.appSettings.settings.value.paneSizeScope)
        graph.close()
    }

    @Test
    fun theConnectionGroupShowsTheWarningTheLinkTypesAndTheBatteryState() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        onNodeWithTag(settingsGroupTag(AppSettingsGroup.Connection)).performClick()
        settle(graph)
        onNodeWithText("More battery use and more heat").assertIsDisplayed()
        onNodeWithTag(AppSettingsTags.linkType(TransportKind.BluetoothSpp)).performScrollTo().performClick()
        settle(graph)
        assertEquals(setOf(TransportKind.BluetoothSpp), graph.store.appSettings.settings.value.hiddenLinkTypes)
        onNodeWithText("Battery optimization is on for SuperDRO. Android can stop the links with the screen off.").performScrollTo().assertIsDisplayed()
        onNodeWithTag(AppSettingsTags.BATTERY_OPEN).performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.OpenBatteryOptimizationSettings), graph.requests.sent)
        graph.close()
    }

    @Test
    fun theMuteScopeEachMachineDisablesTheAppSwitch() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph, SettingsWindow.Portrait)
        onNodeWithTag(settingsGroupTag(AppSettingsGroup.Sounds)).performClick()
        settle(graph)
        onNodeWithText("One for each machine").performClick()
        settle(graph)
        assertEquals(MuteScope.EachMachine, graph.store.appSettings.settings.value.muteScope)
        onNodeWithTag(AppSettingsTags.MUTE).assertIsNotEnabled()
        onNodeWithText("Each machine has its own mute switch in its machine settings.").assertIsDisplayed()
        graph.close()
    }

    @Test
    fun aDxfFieldShowsItsMessageForATextThatIsNotValid() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        onNodeWithTag(settingsGroupTag(AppSettingsGroup.DxfImport)).performClick()
        settle(graph)
        onNodeWithTag(AppSettingsTags.dxf(DxfLimit.Entities)).performTextClearance()
        settle(graph)
        onNodeWithTag(AppSettingsTags.dxf(DxfLimit.Entities)).performTextInput("0")
        settle(graph)
        onNodeWithText("Type a whole number more than 0.").assertIsDisplayed()
        assertEquals(10_000, graph.store.appSettings.settings.value.dxfImportLimits.maxEntities)
        onNodeWithTag(AppSettingsTags.dxf(DxfLimit.Entities)).performTextClearance()
        settle(graph)
        onNodeWithTag(AppSettingsTags.dxf(DxfLimit.Entities)).performTextInput("750")
        settle(graph)
        assertEquals(750, graph.store.appSettings.settings.value.dxfImportLimits.maxEntities)
        graph.close()
    }

    @Test
    fun theMachinesGroupOpensTheRememberedMachinesPane() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        onNodeWithTag(settingsGroupTag(AppSettingsGroup.Machines)).performClick()
        settle(graph)
        onNodeWithTag("app-link-remembered").assertHeightIsAtLeast(64.dp).performClick()
        onNodeWithTag("app-link-keys").performClick()
        assertEquals(listOf("remembered", "keys"), graph.opened)
        graph.close()
    }
}
