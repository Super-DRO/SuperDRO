package org.superdro.core.ui.tabs

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.ADD_MACHINE_TAG
import org.superdro.core.ui.AppFrame
import org.superdro.core.ui.EmptyState
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.NavigationHost
import org.superdro.core.ui.navigation.RememberedMachinesRoute
import org.superdro.core.ui.tabs.adddevice.ADD_DEVICE_CONNECT_TAG
import org.superdro.core.ui.tabs.adddevice.ADD_DEVICE_SHEET_TAG
import org.superdro.core.ui.tabs.adddevice.AddDevicePresenter
import org.superdro.core.ui.tabs.adddevice.linkOptionTag
import org.superdro.core.ui.tabs.adddevice.LinkOption
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPresenter
import org.superdro.core.ui.tabs.remembered.rememberedOpenTag
import org.superdro.core.ui.tabs.remembered.rememberedRowTag
import org.superdro.core.ui.tabTag
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.navigation.BatteryOptimizationRoute
import org.superdro.core.connection.ConnectionRequest
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * The global entries of the frame in the navigation host with a DI graph: the add-device sheet
 * opens from "+" and adds a machine, and the remembered machines pane shows each state.
 */
@OptIn(ExperimentalTestApi::class)
class TabsEntriesUiTest {

    private class Graph(val scope: TestScope) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val requests = RecordingRequests()
        val navigation = NavigationHolder()
        val bluetooth = FakeDiscovery(
            TransportKind.BluetoothSpp,
            listOf(DiscoveredAdapter(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05", "24:6F:28:A1:B2:C3")),
        )
        val tabs = DeviceTabsPresenter(connections, store.tabs, store.machines, scope.testPresenterContext())
        private val presenterContext = scope.testPresenterContext()
        val di = DI {
            bindSingleton<RequestSender> { requests }
            bindSingleton { store.appSettings }
            bindSingleton { tabs }
            bindProvider {
                AddDevicePresenter(
                    connections, store.tabs, store.machines, store.adapters, settingsFlow(), listOf(bluetooth), FakeCapabilities(),
                    NoDebugAdapter, requests, FirstConnectRequests(store.appSettings), presenterContext,
                )
            }
            bindProvider { RememberedMachinesPresenter(connections, store.tabs, store.machines, presenterContext) }
            bindFactory<String, EditMachinePresenter> { id -> EditMachinePresenter(id, connections, store.machines, presenterContext) }
        }
        val registry = EntryRegistry().apply { tabsEntries() }

        fun run() = scope.testScheduler.advanceUntilIdle()

        fun close() {
            tabs.close()
            store.close()
        }
    }

    private fun ComposeUiTest.show(graph: Graph) {
        setContent {
            val state by graph.tabs.uiState.collectAsState()
            ProvideDI(graph.di) {
                AppTheme(systemInDarkTheme = false) {
                    AppFrame(state = state, events = graph.tabs, navigation = graph.navigation) { modifier ->
                        NavigationHost(
                            holder = graph.navigation,
                            registry = graph.registry,
                            tabIds = emptyList(),
                            selected = null,
                            readoutFactory = { error("No tab in this test.") },
                            showMessage = {},
                            modifier = modifier,
                            emptyContent = {
                                EmptyState(0, onAddMachine = { graph.navigation.push(AddDeviceRoute) }, onOpenRememberedMachines = {})
                            },
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.settle(graph: Graph) {
        repeat(3) {
            waitForIdle()
            graph.run()
        }
        waitForIdle()
    }

    @Test
    fun theBatteryDialogHasTheTextAndKeepsTheAskedFlag() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        runOnIdle { graph.navigation.push(BatteryOptimizationRoute) }
        settle(graph)
        onNodeWithText("SuperDRO keeps the links to your machines open while the screen is off.").assertIsDisplayed()
        onNodeWithText("Not now").performClick()
        settle(graph)
        assertTrue(graph.store.appSettings.settings.value.batteryExemptionAsked)
        assertTrue(graph.requests.sent.isEmpty(), "Not now sends no request")
        graph.close()
    }

    @Test
    fun continueOfTheBatteryDialogOfTheFirstConnectSendsTheRequest() = runComposeUiTest {
        // The activity opens this dialog at the first connect.
        val graph = Graph(TestScope())
        show(graph)
        runOnIdle { graph.navigation.push(BatteryOptimizationRoute) }
        settle(graph)
        onNodeWithText("Continue").performClick()
        settle(graph)
        assertTrue(graph.store.appSettings.settings.value.batteryExemptionAsked)
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestIgnoreBatteryOptimizations), graph.requests.sent)
        graph.close()
    }

    @Test
    fun theAddDeviceSheetOpensFromPlusAndAddsAMachine() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        onNodeWithTag(ADD_MACHINE_TAG).performClick()
        settle(graph)
        onNodeWithTag(ADD_DEVICE_SHEET_TAG).assertIsDisplayed()
        onNodeWithText("Select a link").assertIsDisplayed()
        val option = LinkOption(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05", "24:6F:28:A1:B2:C3")
        onNodeWithTag(linkOptionTag(option)).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        onNodeWithText("Machine profile").assertIsDisplayed()
        onNodeWithTag("machine-name").performScrollTo().performTextInput("Bridgeport")
        settle(graph)
        onNodeWithTag(ADD_DEVICE_CONNECT_TAG).performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        assertEquals(0, onAllNodesWithTag(ADD_DEVICE_SHEET_TAG).fetchSemanticsNodes().size)
        val remembered = graph.store.tabs.state.value.remembered.single()
        assertEquals(listOf("connect ${remembered.profileId}"), graph.connections.calls)
        // The first connect opens no battery dialog of the frame: the activity has the one flow
        // of the first connect.
        assertEquals(0, onAllNodesWithText("Battery optimization").fetchSemanticsNodes().size)
        assertTrue(graph.requests.sent.isEmpty())
        assertTrue(graph.navigation.globalEntries.isEmpty())
        graph.close()
    }

    @Test
    fun theRememberedMachinesPaneShowsEachState() = runComposeUiTest {
        val graph = Graph(TestScope())
        val ids = mutableListOf<String>()
        graph.scope.launch {
            listOf("Mill" to AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "Lathe" to AdapterAddress.Tcp("192.168.4.1", 8080)).forEach { (name, address) ->
                val profile = graph.store.machines.createProfile(MachineProfile.create(name = name).copy(adapter = address)).valueOrNull()!!
                graph.store.tabs.remember(profile.id)
                ids += profile.id
            }
            graph.store.tabs.setPaused(ids[1], true)
        }
        graph.run()
        graph.connections.add(
            org.superdro.core.ui.testing.FakeDeviceSession(
                org.superdro.core.connection.DeviceId(ids[0]),
                "Mill",
                initialState = org.superdro.core.connection.SessionState.Connected(0L),
            ),
        )
        show(graph)
        graph.navigation.push(RememberedMachinesRoute)
        settle(graph)
        onNodeWithTag(rememberedRowTag(ids[0])).assertIsDisplayed()
        onNodeWithText("online", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("paused", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("192.168.4.1:8080", substring = true, useUnmergedTree = true).assertIsDisplayed()
        graph.connections.disconnect(org.superdro.core.connection.DeviceId(ids[0]))
        settle(graph)
        onNodeWithText("offline", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("Remove")[0].performClick()
        settle(graph)
        onNodeWithText("Remove Mill?").assertIsDisplayed()
        onNodeWithTag("confirm-remove").performClick()
        settle(graph)
        assertEquals(listOf(ids[1]), graph.store.tabs.state.value.remembered.map { it.profileId })
        assertEquals(0, onAllNodesWithTag(rememberedRowTag(ids[0])).fetchSemanticsNodes().size)
        // "Open" on the paused machine closes the pane and shows its tab in the frame.
        onNodeWithTag(rememberedOpenTag(ids[1])).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        assertTrue(graph.navigation.globalEntries.isEmpty())
        onNodeWithTag(tabTag(org.superdro.core.connection.DeviceId(ids[1]))).assertIsDisplayed()
        assertEquals(org.superdro.core.connection.DeviceId(ids[1]), graph.tabs.uiState.value.selected)
        graph.close()
    }
}
