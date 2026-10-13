package org.superdro.core.ui.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.ADD_MACHINE_TAG
import org.superdro.core.ui.AppFrame
import org.superdro.core.ui.BACKGROUND_WARNING_TAG
import org.superdro.core.ui.FRAME_CONTENT_TAG
import org.superdro.core.ui.FrameLayout
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.AppSettingsRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.navigation.RememberedMachinesRoute
import org.superdro.core.ui.navigation.RenameMachineRoute
import org.superdro.core.ui.tabTag
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the device tabs. */
@OptIn(ExperimentalTestApi::class)
class TabsFrameUiTest {

    private class RecordingEvents : DeviceTabsEvents {
        val calls = mutableListOf<String>()
        override fun onSelect(id: DeviceId) { calls += "select $id" }
        override fun onOpenMenu(id: DeviceId) { calls += "menu $id" }
        override fun onCloseMenu() { calls += "closeMenu" }
        override fun onPause(id: DeviceId) { calls += "pause $id" }
        override fun onOpenOverflow() { calls += "overflow" }
        override fun onCloseOverflow() { calls += "closeOverflow" }
        override fun onDisconnectAll() { calls += "disconnectAll" }
        override fun onMessageShown(id: Long) { calls += "shown $id" }
    }

    private val mill = DeviceId("mill")
    private val lathe = DeviceId("lathe")

    private fun tab(id: DeviceId, name: String, state: SessionState, transport: TransportKind = TransportKind.BluetoothSpp) =
        DeviceTabUiState(id, name, transport, state, TabStatusRules.of(state), inGrace = !state.isOnline)

    private fun twoTabs(menuFor: DeviceId? = null) = DeviceTabsUiState(
        tabs = listOf(tab(mill, "Mill", SessionState.Connected(0L)), tab(lathe, "Lathe", SessionState.AwaitingData, TransportKind.UsbSerial)),
        selected = mill,
        rememberedCount = 2,
        menuFor = menuFor,
    )

    private fun ComposeUiTest.showFrame(state: DeviceTabsUiState, events: DeviceTabsEvents, navigation: NavigationHolder = NavigationHolder()) {
        setContent {
            AppTheme(systemInDarkTheme = false) {
                AppFrame(state = state, events = events, navigation = navigation) { modifier -> Box(modifier) }
            }
        }
    }

    @Test
    fun oneTapSelectsATab() = runComposeUiTest {
        val events = RecordingEvents()
        showFrame(twoTabs(), events)
        onNodeWithTag(tabTag(lathe)).performClick()
        assertEquals(listOf("select lathe"), events.calls)
    }

    @Test
    fun theFrameShowsTheBackgroundWarningWithAnIconAndAText() = runComposeUiTest {
        showFrame(twoTabs().copy(backgroundWarning = true), RecordingEvents())
        onNodeWithTag(BACKGROUND_WARNING_TAG).assertIsDisplayed()
        onNodeWithText("The links can stop while SuperDRO is in the background.").assertIsDisplayed()
    }

    @Test
    fun aSwipeOfTheContentDoesNotChangeTheTab() = runComposeUiTest {
        val events = RecordingEvents()
        showFrame(twoTabs(), events)
        onNodeWithTag(FRAME_CONTENT_TAG).performTouchInput { swipeLeft() }
        onNodeWithTag(FRAME_CONTENT_TAG).performTouchInput { swipeRight() }
        assertEquals(emptyList(), events.calls)
    }

    @Test
    fun aLongPressOpensTheTabMenu() = runComposeUiTest {
        val events = RecordingEvents()
        showFrame(twoTabs(), events)
        onNodeWithTag(tabTag(lathe)).performTouchInput { longClick() }
        assertEquals(listOf("menu lathe"), events.calls)
    }

    @Test
    fun theTabMenuHasDisconnectRenameMachineSettingsAndAdapterDetails() = runComposeUiTest {
        val events = RecordingEvents()
        val navigation = NavigationHolder()
        showFrame(twoTabs(menuFor = lathe), events, navigation)
        onNodeWithText("Disconnect").assertIsDisplayed()
        onNodeWithText("Machine settings").assertIsDisplayed()
        // The session of the lathe is not Connected, so "Adapter details" is not enabled.
        onNodeWithText("Adapter details").assertIsNotEnabled()
        onNodeWithText("Rename").performClick()
        assertEquals(RenameMachineRoute("lathe"), navigation.globalEntries.last().route)
        onNodeWithText("Machine settings").performClick()
        assertEquals(listOf(ReadoutRoute, MachineSettingsRoute), navigation.tab(lathe).routes)
        onNodeWithText("Disconnect").performClick()
        assertEquals(listOf("closeMenu", "closeMenu", "select lathe", "closeMenu", "pause lathe"), events.calls)
    }

    @Test
    fun eachTabShowsTheStatusTextOfItsState() = runComposeUiTest {
        val states = listOf(
            SessionState.Connected(0L) to "Connected",
            SessionState.Connecting(1) to "Connecting",
            SessionState.AwaitingData to "Waiting for data",
            SessionState.Reconnecting(2, 0L, LinkFailure.Lost) to "Reconnecting (2)",
            SessionState.NoData to "No data",
            SessionState.WaitingForDevice(LinkFailure.RadioOff) to "Bluetooth off",
            SessionState.WaitingForDevice(LinkFailure.DeviceGone) to "Unplugged",
            SessionState.Failed(LinkFailure.Refused) to "Connect failed",
        )
        val state = DeviceTabsUiState(
            tabs = states.mapIndexed { index, (session, _) -> tab(DeviceId("m$index"), "M$index", session) },
            selected = DeviceId("m0"),
        )
        showFrame(state, RecordingEvents())
        states.forEach { (_, text) -> assertEquals(1, onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size, text) }
    }

    @Test
    fun theGlobalActionsOpenTheirRoutesAndHaveLargeTouchTargets() = runComposeUiTest {
        val events = RecordingEvents()
        val navigation = NavigationHolder()
        showFrame(twoTabs(), events, navigation)
        onNodeWithTag(ADD_MACHINE_TAG).assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
        onNodeWithTag(tabTag(mill)).assertHeightIsAtLeast(64.dp)
        onNodeWithContentDescription("Settings").assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(64.dp)
        onNodeWithContentDescription("More options").assertHeightIsAtLeast(64.dp)
        onNodeWithTag(ADD_MACHINE_TAG).performClick()
        onNodeWithContentDescription("Settings").performClick()
        onNodeWithContentDescription("More options").performClick()
        assertEquals(listOf(AddDeviceRoute, AppSettingsRoute), navigation.globalEntries.map { it.route })
        assertEquals(listOf("overflow"), events.calls)
    }

    @Test
    fun theOverflowMenuOpensTheRememberedMachinesAndDisconnectsAll() = runComposeUiTest {
        val events = RecordingEvents()
        val navigation = NavigationHolder()
        showFrame(twoTabs().copy(overflowOpen = true), events, navigation)
        listOf("About", "Logs", "Machine profiles", "Adapters", "Probe library", "Data output").forEach { item -> onNodeWithText(item).assertIsDisplayed() }
        onNodeWithText("Remembered machines").performClick()
        onNodeWithText("Disconnect all").performClick()
        assertEquals(listOf(RememberedMachinesRoute), navigation.globalEntries.map { it.route })
        assertEquals(listOf("closeOverflow", "closeOverflow", "disconnectAll"), events.calls)
    }

    @Test
    fun aTabShowsWhenItsMachineComesOnlineAndHidesAfterTheGraceTime() = runComposeUiTest {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val presenter = DeviceTabsPresenter(connections, store.tabs, store.machines, scope.testPresenterContext())
        var id = DeviceId("")
        scope.launchAndRun {
            val profile = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
            store.tabs.remember(profile.id)
            id = DeviceId(profile.id)
        }
        val session = FakeDeviceSession(id, "Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), SessionState.Connecting(1))
        connections.add(session)
        setContent {
            val state by presenter.uiState.collectAsState()
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(state, presenter, onAddMachine = {}, onOpenSettings = {}, onOverflowAction = {}, onTabMenuAction = { _, _ -> }) { modifier ->
                    Box(modifier)
                }
            }
        }
        scope.testScheduler.runCurrent()
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(tabTag(id)).fetchSemanticsNodes().size)
        session.state.value = SessionState.Connected(0L)
        scope.testScheduler.runCurrent()
        waitForIdle()
        onNodeWithTag(tabTag(id)).assertIsDisplayed()
        session.state.value = SessionState.Reconnecting(2, 0L, LinkFailure.Lost)
        scope.testScheduler.runCurrent()
        waitForIdle()
        onNodeWithText("Reconnecting (2)", useUnmergedTree = true).assertIsDisplayed()
        scope.testScheduler.advanceTimeBy(9_999L)
        scope.testScheduler.runCurrent()
        waitForIdle()
        onNodeWithTag(tabTag(id)).assertIsDisplayed()
        scope.testScheduler.advanceTimeBy(1L)
        scope.testScheduler.runCurrent()
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(tabTag(id)).fetchSemanticsNodes().size)
        presenter.close()
        store.close()
    }
}

/** Runs [block] in this test scope, and runs the scheduler until no task waits. */
private fun TestScope.launchAndRun(block: suspend () -> Unit) {
    launch { block() }
    testScheduler.advanceUntilIdle()
}
