package org.superdro.validation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.ADD_MACHINE_TAG
import org.superdro.core.ui.FRAME_CONTENT_TAG
import org.superdro.core.ui.FrameLayout
import org.superdro.core.ui.OverflowAction
import org.superdro.core.ui.EmptyState
import org.superdro.core.ui.TabMenuAction
import org.superdro.core.ui.statusColors
import org.superdro.core.ui.tabTag
import org.superdro.core.ui.tabs.DeviceTabUiState
import org.superdro.core.ui.tabs.DeviceTabsEvents
import org.superdro.core.ui.tabs.DeviceTabsPresenter
import org.superdro.core.ui.tabs.DeviceTabsUiState
import org.superdro.core.ui.tabs.TabStatusIcon
import org.superdro.core.ui.tabs.TabStatusRules
import org.superdro.core.ui.tabs.remembered.MachineLinkState
import org.superdro.core.ui.tabs.remembered.RememberedMachineRow
import org.superdro.core.ui.tabs.remembered.RememberedMachinesEvents
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPane
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPresenter
import org.superdro.core.ui.tabs.remembered.RememberedMachinesUiState
import org.superdro.core.ui.theme.AppTheme
import org.superdro.core.ui.theme.Theme

/**
 * The tabs (add, select, long-press menu,
 * status text for each state, no tab change on a swipe), a tab that shows when its machine comes
 * online and hides after the grace time, and the remembered machines pane with each state.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class DeviceTabsValidationTest {

    // ---------------------------------------------------------------------------------------
    // The presenter (virtual time)

    private suspend fun PresenterRig.machine(id: String, name: String, type: MachineType = MachineType.VerticalMill) {
        machines.createProfile(MachineProfile.create(type, name).copy(id = id, adapter = AdapterAddress.Fake("still")))
        tabStore.remember(id)
    }

    private fun TestScope.rig(): PresenterRig = PresenterRig(this, StandardTestDispatcher(testScheduler))

    @Test
    fun a_tab_shows_only_while_the_machine_is_online() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            val session = FakeSession("mill", "Mill")
            rig.connections.add(session)
            val tabs = rig.tabs()
            runCurrent()
            // Offline states before the first link: no tab (online is AwaitingData, Connected, NoData).
            listOf(SessionState.Disconnected, SessionState.Connecting(1)).forEach { state ->
                session.state.value = state
                runCurrent()
                assertEquals(emptyList(), tabs.uiState.value.tabs, "no tab in $state")
            }
            listOf(SessionState.AwaitingData, SessionState.Connected(0L), SessionState.NoData).forEach { state ->
                session.state.value = state
                runCurrent()
                val tab = tabs.uiState.value.tabs.singleOrNull()
                assertNotNull(tab, "a tab in $state")
                assertEquals("Mill", tab.name)
                assertEquals(DeviceId("mill"), tabs.uiState.value.selected)
            }
        }
    }

    @Test
    fun offline_tab_stays_for_the_grace_time_of_10_s_with_Reconnecting_then_hides() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            val session = FakeSession("mill", "Mill")
            rig.connections.add(session)
            val tabs = rig.tabs()
            session.state.value = SessionState.Connected(0L)
            runCurrent()
            assertEquals(1, tabs.uiState.value.tabs.size)
            session.state.value = SessionState.Reconnecting(attempt = 2, retryAtMs = 1_000_000L, cause = LinkFailure.Lost)
            runCurrent()
            advanceTimeBy(9_999L)
            runCurrent()
            val tab = assertNotNull(tabs.uiState.value.tabs.singleOrNull(), "the tab stays during the grace time")
            assertTrue(tab.inGrace)
            assertEquals(TabStatusIcon.Reconnecting, tab.status.icon)
            assertEquals(2, tab.status.attempt)
            advanceTimeBy(2L)
            runCurrent()
            assertEquals(emptyList(), tabs.uiState.value.tabs, "the tab hides after 10 s")
        }
    }

    @Test
    fun a_machine_that_comes_online_in_the_grace_time_keeps_its_tab() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            val session = FakeSession("mill", "Mill")
            rig.connections.add(session)
            val tabs = rig.tabs()
            session.state.value = SessionState.Connected(0L)
            runCurrent()
            session.state.value = SessionState.Reconnecting(1, 1_000L, LinkFailure.Lost)
            runCurrent()
            advanceTimeBy(5_000L)
            session.state.value = SessionState.Connected(5_000L)
            runCurrent()
            advanceTimeBy(20_000L)
            runCurrent()
            val tab = assertNotNull(tabs.uiState.value.tabs.singleOrNull())
            assertFalse(tab.inGrace)
        }
    }

    @Test
    fun one_tap_selects_a_tab_and_a_long_press_opens_its_menu() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            rig.machine("lathe", "Lathe", MachineType.Lathe)
            val mill = FakeSession("mill", "Mill").apply { state.value = SessionState.Connected(0L) }
            val lathe = FakeSession("lathe", "Lathe").apply { state.value = SessionState.Connected(0L) }
            rig.connections.add(mill)
            rig.connections.add(lathe)
            val tabs = rig.tabs()
            runCurrent()
            assertEquals(listOf("Mill", "Lathe"), tabs.uiState.value.tabs.map { it.name })
            tabs.onSelect(DeviceId("lathe"))
            runCurrent()
            assertEquals(DeviceId("lathe"), tabs.uiState.value.selected)
            tabs.onOpenMenu(DeviceId("mill"))
            runCurrent()
            assertEquals(DeviceId("mill"), tabs.uiState.value.menuFor)
            // The selection does not change with the menu.
            assertEquals(DeviceId("lathe"), tabs.uiState.value.selected)
            // A failure on one link does not change the other tab.
            lathe.state.value = SessionState.Failed(LinkFailure.Refused)
            runCurrent()
            assertTrue(tabs.uiState.value.tabs.first { it.id == DeviceId("mill") }.state is SessionState.Connected)
        }
    }

    @Test
    fun menu_Disconnect_pauses_the_machine() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            val session = FakeSession("mill", "Mill").apply { state.value = SessionState.Connected(0L) }
            rig.connections.add(session)
            val tabs = rig.tabs()
            runCurrent()
            tabs.onPause(DeviceId("mill"))
            runCurrent()
            assertEquals(SessionState.Disconnected, session.state.value)
            assertTrue(rig.tabStore.state.value.remembered.single { it.profileId == "mill" }.paused)
        }
    }

    @Test
    fun status_icon_of_each_state() {
        assertEquals(TabStatusIcon.Connected, TabStatusRules.of(SessionState.Connected(0L)).icon)
        assertEquals(TabStatusIcon.Progress, TabStatusRules.of(SessionState.Connecting(1)).icon)
        assertEquals(TabStatusIcon.Progress, TabStatusRules.of(SessionState.AwaitingData).icon)
        val reconnecting = TabStatusRules.of(SessionState.Reconnecting(3, 0L, LinkFailure.Lost))
        assertEquals(TabStatusIcon.Reconnecting, reconnecting.icon)
        assertEquals(3, reconnecting.attempt)
        assertEquals(TabStatusIcon.Warning, TabStatusRules.of(SessionState.NoData).icon)
        assertEquals(TabStatusIcon.Error, TabStatusRules.of(SessionState.Failed(LinkFailure.Refused)).icon)
        assertEquals(TabStatusIcon.Error, TabStatusRules.of(SessionState.WaitingForDevice(LinkFailure.RadioOff)).icon)
    }

    @Test
    fun link_status_colors() = runComposeUiTest {
        val seen = HashMap<String, Pair<String, String>>()
        setContent {
            listOf(false, true).forEach { dark ->
                AppTheme(systemInDarkTheme = dark) {
                    val scheme = MaterialTheme.colorScheme
                    val mode = if (dark) "dark" else "light"
                    @Composable
                    fun record(name: String, state: SessionState) {
                        val (container, content) = statusColors(TabStatusRules.of(state).roles)
                        seen["$mode $name"] = container.hex() to content.hex()
                    }
                    record("connected", SessionState.Connected(0L))
                    record("reconnect", SessionState.Reconnecting(1, 0L, LinkFailure.Lost))
                    record("failed", SessionState.Failed(LinkFailure.Refused))
                    record("waiting", SessionState.WaitingForDevice(LinkFailure.DeviceGone))
                    seen["$mode expected connected"] = scheme.primaryContainer.hex() to scheme.onPrimaryContainer.hex()
                    seen["$mode expected reconnect"] = scheme.tertiaryContainer.hex() to scheme.onTertiaryContainer.hex()
                    seen["$mode expected failure"] = scheme.errorContainer.hex() to scheme.onErrorContainer.hex()
                }
            }
        }
        waitForIdle()
        listOf("light", "dark").forEach { mode ->
            assertEquals(seen["$mode expected connected"], seen["$mode connected"], mode)
            assertEquals(seen["$mode expected reconnect"], seen["$mode reconnect"], mode)
            assertEquals(seen["$mode expected failure"], seen["$mode failed"], mode)
            assertEquals(seen["$mode expected failure"], seen["$mode waiting"], mode)
        }
        // The primary color is never a status color in light mode.
        assertTrue(seen.filterKeys { it.startsWith("light") }.values.none { (container, content) -> Theme.lightScheme.primary.hex() in listOf(container, content) })
    }

    // ---------------------------------------------------------------------------------------
    // The frame (stateless composables)

    private class RecordingEvents : DeviceTabsEvents {
        val calls = ArrayList<String>()
        override fun onSelect(id: DeviceId) { calls += "select ${id.value}" }
        override fun onOpenMenu(id: DeviceId) { calls += "menu ${id.value}" }
        override fun onCloseMenu() { calls += "closeMenu" }
        override fun onPause(id: DeviceId) { calls += "pause ${id.value}" }
        override fun onOpenOverflow() { calls += "overflow" }
        override fun onCloseOverflow() { calls += "closeOverflow" }
        override fun onDisconnectAll() { calls += "disconnectAll" }
        override fun onMessageShown(id: Long) = Unit
    }

    private fun tab(id: String, name: String, transport: TransportKind, state: SessionState) =
        DeviceTabUiState(DeviceId(id), name, transport, state, TabStatusRules.of(state), inGrace = !state.isOnline)

    private val threeTabs = DeviceTabsUiState(
        tabs = listOf(
            tab("mill", "Mill", TransportKind.BluetoothSpp, SessionState.Connected(0L)),
            tab("lathe", "Lathe", TransportKind.UsbSerial, SessionState.Reconnecting(2, 0L, LinkFailure.Lost)),
            tab("demo", "Demo", TransportKind.Fake, SessionState.Connected(0L)),
        ),
        selected = DeviceId("mill"),
        rememberedCount = 3,
    )

    @Test
    fun tab_label_has_the_name_the_link_and_the_status_text() = runComposeUiTest {
        val events = RecordingEvents()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(threeTabs, events, {}, {}, {}, { _, _ -> }) { modifier -> androidx.compose.foundation.layout.Box(modifier) }
            }
        }
        val mill = onNodeWithTag(tabTag(DeviceId("mill"))).fetchSemanticsNode()
        val description = mill.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue("Mill" in description && "Bluetooth" in description && "Connected" in description, description)
        assertEquals(true, mill.config.getOrNull(SemanticsProperties.Selected))
        val lathe = onNodeWithTag(tabTag(DeviceId("lathe"))).fetchSemanticsNode()
        val latheText = lathe.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue("Lathe" in latheText && "USB" in latheText && "Reconnecting" in latheText && "2" in latheText, latheText)
        onNodeWithText("Reconnecting (2)", useUnmergedTree = true).assertIsDisplayed()
        onAllNodesWithText("Connected", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(2, it.size) }
    }

    @Test
    fun status_text_of_each_state() = runComposeUiTest {
        val states = listOf(
            "s1" to SessionState.Connected(0L),
            "s2" to SessionState.Connecting(1),
            "s3" to SessionState.AwaitingData,
            "s4" to SessionState.Reconnecting(4, 0L, LinkFailure.Lost),
            "s5" to SessionState.NoData,
            "s6" to SessionState.WaitingForDevice(LinkFailure.RadioOff),
            "s7" to SessionState.WaitingForDevice(LinkFailure.DeviceGone),
            "s8" to SessionState.Failed(LinkFailure.RadioOff),
        )
        val state = DeviceTabsUiState(tabs = states.map { (id, s) -> tab(id, id, TransportKind.BluetoothSpp, s) }, selected = DeviceId("s1"))
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(state, RecordingEvents(), {}, {}, {}, { _, _ -> }) { modifier -> androidx.compose.foundation.layout.Box(modifier) }
            }
        }
        fun text(id: String) = onNodeWithTag(tabTag(DeviceId(id))).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue("Connected" in text("s1"), text("s1"))
        assertTrue("Reconnecting" in text("s4") && "4" in text("s4"), text("s4"))
        // The causes in quotes.
        assertTrue("Bluetooth off" in text("s6"), text("s6"))
        assertTrue("Unplugged" in text("s7"), text("s7"))
        assertTrue("Bluetooth off" in text("s8"), text("s8"))
        // Each state has a text.
        states.forEach { (id, _) -> assertTrue(text(id).split(',').size >= 3, "$id: ${text(id)}") }
    }

    @Test
    fun one_tap_selects_and_a_long_press_opens_the_menu() = runComposeUiTest {
        val events = RecordingEvents()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(threeTabs, events, {}, {}, {}, { _, _ -> }) { modifier -> androidx.compose.foundation.layout.Box(modifier) }
            }
        }
        onNodeWithTag(tabTag(DeviceId("lathe"))).performClick()
        waitForIdle()
        assertEquals(listOf("select lathe"), events.calls)
        events.calls.clear()
        onNodeWithTag(tabTag(DeviceId("demo"))).performTouchInput { longClick() }
        waitForIdle()
        assertEquals(listOf("menu demo"), events.calls)
    }

    @Test
    fun long_press_menu_has_Disconnect_Rename_Machine_settings_Adapter_details() = runComposeUiTest {
        val actions = ArrayList<Pair<String, TabMenuAction>>()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(threeTabs.copy(menuFor = DeviceId("mill")), RecordingEvents(), {}, {}, {}, { id, action -> actions += id.value to action }) { modifier ->
                    androidx.compose.foundation.layout.Box(modifier)
                }
            }
        }
        val items = listOf("Disconnect", "Rename", "Machine settings", "Adapter details")
        val tops = items.map { item -> onNodeWithText(item).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "the menu order $items")
        onNodeWithText("Disconnect").performClick()
        waitForIdle()
        assertEquals(listOf("mill" to TabMenuAction.Disconnect), actions)
    }

    @Test
    fun a_swipe_does_not_change_the_tab() = runComposeUiTest {
        val events = RecordingEvents()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(threeTabs, events, {}, {}, {}, { _, _ -> }) { modifier -> androidx.compose.foundation.layout.Box(modifier) }
            }
        }
        onNodeWithTag(FRAME_CONTENT_TAG).performTouchInput { swipeLeft() }
        onNodeWithTag(FRAME_CONTENT_TAG).performTouchInput { swipeRight() }
        waitForIdle()
        assertTrue(events.calls.none { it.startsWith("select") }, "${events.calls}")
        // The content has no pager: it cannot scroll horizontally.
        val content = onNodeWithTag(FRAME_CONTENT_TAG).fetchSemanticsNode()
        assertNull(content.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
    }

    @Test
    fun plus_opens_the_add_device_sheet_and_the_gear_the_settings() = runComposeUiTest {
        val calls = ArrayList<String>()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(threeTabs, RecordingEvents(), { calls += "add" }, { calls += "settings" }, {}, { _, _ -> }) { modifier ->
                    androidx.compose.foundation.layout.Box(modifier)
                }
            }
        }
        onNodeWithTag(ADD_MACHINE_TAG).performClick()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
        assertEquals(listOf("add", "settings"), calls)
    }

    @Test
    fun overflow_menu_items_in_order() = runComposeUiTest {
        val chosen = ArrayList<OverflowAction>()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                FrameLayout(threeTabs.copy(overflowOpen = true), RecordingEvents(), {}, {}, { chosen += it }, { _, _ -> }) { modifier ->
                    androidx.compose.foundation.layout.Box(modifier)
                }
            }
        }
        val items = listOf("About", "Logs", "Machine profiles", "Remembered machines", "Adapters", "Probe library", "Data output", "Disconnect all")
        val tops = items.map { item -> onNodeWithText(item).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops, "the overflow order $items")
        onNodeWithText("Disconnect all").performClick()
        waitForIdle()
        assertEquals(listOf(OverflowAction.DisconnectAll), chosen)
    }

    @Test
    fun empty_state_texts_and_actions() = runComposeUiTest {
        val calls = ArrayList<String>()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                EmptyState(rememberedMachineCount = 2, onAddMachine = { calls += "add" }, onOpenRememberedMachines = { calls += "pane" })
            }
        }
        onNodeWithText("No machine is online").assertIsDisplayed()
        // The number of remembered machines with a link to the pane.
        onNode(hasText("2", substring = true) and hasClickAction()).performClick()
        onNode(hasText("Add machine") and hasClickAction(), useUnmergedTree = false).performClick()
        waitForIdle()
        assertEquals(listOf("pane", "add"), calls)
        // A large button: more than the 64 dp of a touch target.
        val button = onNode(hasText("Add machine") and hasClickAction()).fetchSemanticsNode()
        assertTrue(button.size.height >= 64 * density.density, "height ${button.size.height}")
    }

    // ---------------------------------------------------------------------------------------
    // The remembered machines pane

    @Test
    fun remembered_machines_pane_gives_online_offline_and_paused() = runTest {
        rig().use { rig ->
            rig.machine("a", "Mill A")
            rig.machine("b", "Mill B")
            rig.machine("c", "Lathe C", MachineType.Lathe)
            rig.tabStore.setPaused("c", true)
            rig.connections.add(FakeSession("a", "Mill A").apply { state.value = SessionState.Connected(0L) })
            rig.connections.add(FakeSession("b", "Mill B").apply { state.value = SessionState.Reconnecting(1, 0L, LinkFailure.Lost) })
            val pane = RememberedMachinesPresenter(rig.connections, rig.tabStore, rig.machines, rig.context)
            runCurrent()
            val rows = pane.uiState.value.rows.associateBy { it.profileId }
            assertEquals(MachineLinkState.Online, rows.getValue("a").state)
            assertEquals(MachineLinkState.Offline, rows.getValue("b").state)
            assertEquals(MachineLinkState.Paused, rows.getValue("c").state)
            assertEquals("Mill A", rows.getValue("a").name)
            assertEquals(TransportKind.Fake, rows.getValue("a").transport)
            // "Remove" asks for a confirmation, then the profile stays.
            pane.askRemove("b")
            runCurrent()
            assertEquals("b", pane.uiState.value.confirmRemove?.profileId)
            pane.confirmRemove()
            runCurrent()
            assertTrue(rig.tabStore.state.value.remembered.none { it.profileId == "b" })
            assertNotNull(rig.machines.loadProfile("b"), "the machine profile stays")
            pane.close()
        }
    }

    @Test
    fun remembered_machines_pane_shows_the_state_texts_and_the_actions() = runComposeUiTest {
        val state = RememberedMachinesUiState(
            rows = listOf(
                RememberedMachineRow("a", "Mill A", TransportKind.BluetoothSpp, "00:11:22:33:44:55", MachineLinkState.Online),
                RememberedMachineRow("b", "Mill B", TransportKind.Tcp, "192.168.4.1:8080", MachineLinkState.Offline),
                RememberedMachineRow("c", "Lathe C", TransportKind.UsbSerial, "0403:6001", MachineLinkState.Paused),
            ),
        )
        val events = object : RememberedMachinesEvents {
            override fun onConnect(profileId: String) = Unit
            override fun onPause(profileId: String) = Unit
            override fun onEdit(profileId: String) = Unit
            override fun onAskRemove(profileId: String) = Unit
            override fun onCancelRemove() = Unit
            override fun onConfirmRemove() = Unit
        }
        setContent { AppTheme(systemInDarkTheme = false) { RememberedMachinesPane(state, events, onBack = {}) } }
        listOf("online", "offline", "paused").forEach { text ->
            assertTrue(onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), text)
        }
        listOf("Mill A", "00:11:22:33:44:55", "192.168.4.1:8080").forEach { text ->
            assertTrue(onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), text)
        }
        listOf("Connect", "Pause", "Edit", "Remove").forEach { action ->
            assertTrue(onAllNodesWithText(action, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), action)
        }
    }

    @Test
    fun grace_time_is_10_s_and_no_tab_gives_no_selection() {
        assertEquals(10_000L, DeviceTabsPresenter.GRACE_MS)
        assertEquals(-1, DeviceTabsUiState().selectedIndex)
        assertNull(DeviceTabsUiState().selectedTab)
    }

    @Test
    fun the_app_opens_on_the_last_selected_machine_and_a_tap_stores_the_selection() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            rig.machine("lathe", "Lathe", MachineType.Lathe)
            rig.tabStore.select("lathe")
            rig.connections.add(FakeSession("mill", "Mill").apply { state.value = SessionState.Connected(0L) })
            rig.connections.add(FakeSession("lathe", "Lathe").apply { state.value = SessionState.Connected(0L) })
            val tabs = rig.tabs()
            runCurrent()
            assertEquals(DeviceId("lathe"), tabs.uiState.value.selected, "the last selected machine")
            tabs.onSelect(DeviceId("mill"))
            runCurrent()
            assertEquals("mill", rig.tabStore.state.value.selectedProfileId, "TabStore keeps the last selected machine")
        }
    }

    @Test
    fun Disconnect_all_pauses_each_remembered_machine_and_stops_each_session() = runTest {
        rig().use { rig ->
            rig.machine("mill", "Mill")
            rig.machine("lathe", "Lathe", MachineType.Lathe)
            val mill = FakeSession("mill", "Mill").apply { state.value = SessionState.Connected(0L) }
            val lathe = FakeSession("lathe", "Lathe").apply { state.value = SessionState.NoData }
            rig.connections.add(mill)
            rig.connections.add(lathe)
            val tabs = rig.tabs()
            runCurrent()
            assertEquals(2, tabs.uiState.value.tabs.size)
            tabs.onDisconnectAll()
            runCurrent()
            assertEquals(SessionState.Disconnected, mill.state.value)
            assertEquals(SessionState.Disconnected, lathe.state.value)
            assertTrue(rig.tabStore.state.value.remembered.all { it.paused })
            assertEquals(emptyList(), tabs.uiState.value.tabs)
        }
    }

    @Test
    fun each_link_type_has_its_own_transport_icon() {
        val icons = listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp, TransportKind.Fake)
            .map { kind -> org.superdro.core.ui.transportIcon(kind) }
        assertEquals(4, icons.toSet().size, "Bluetooth, USB, Wi-Fi, and demo icons")
    }
}
