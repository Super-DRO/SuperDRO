package org.superdro.core.ui.settings.logs

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.LogsRoute
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.NavigationHost
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.settingsEntries
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.text.UiText

/** The log screen in the navigation host with a DI graph. */
@OptIn(ExperimentalTestApi::class)
class LogsScreenUiTest {

    private class Graph(val scope: TestScope) {
        val store = UiStoreFixture(scope)
        val sink = SessionLogSink(store.wallClock)
        val streams = RecordedStreams(clock = store.wallClock)
        val connections = FakeConnections()
        val files = RecordingFilePicker()
        val navigation = NavigationHolder()
        val messages = mutableListOf<UiText>()
        private val context = scope.testPresenterContext()
        val di = DI {
            bindProvider { LogsPresenter(sink, streams, connections, store.machines, files, context, store.wallClock, TimeZone.UTC) }
        }
        val registry = EntryRegistry().apply { settingsEntries() }

        fun run() = scope.testScheduler.advanceUntilIdle()
    }

    private fun ComposeUiTest.show(graph: Graph) {
        setContent {
            ProvideDI(graph.di) {
                SettingsThemed(dark = false) {
                    NavigationHost(
                        holder = graph.navigation,
                        registry = graph.registry,
                        tabIds = emptyList(),
                        selected = null,
                        readoutFactory = { error("No tab in this test.") },
                        showMessage = { text -> graph.messages += text },
                    )
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
    fun theLogScreenFiltersExportsAndRecords() = runComposeUiTest {
        val graph = Graph(TestScope())
        var mill = DeviceId("")
        graph.scope.launch { mill = DeviceId(graph.store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.id) }
        graph.run()
        graph.sink.add(LogLevel.Info, SessionLogSink.SESSION_TAG, "${mill.value}: link 1 open")
        graph.sink.add(LogLevel.Warning, "JsonFileStore", "Cannot decode tabs.json")
        graph.connections.add(FakeDeviceSession(mill, "Mill", initialState = SessionState.Connected(0L)))
        show(graph)
        graph.navigation.push(LogsRoute)
        settle(graph)

        onNodeWithText("Mill · DeviceSession: link 1 open").assertIsDisplayed()
        onNodeWithText("JsonFileStore: Cannot decode tabs.json").assertIsDisplayed()
        onNodeWithTag(logFilterTag(LogFilter.App)).performClick()
        settle(graph)
        assertEquals(0, onAllText("Mill · DeviceSession: link 1 open"))
        onNodeWithTag(LOG_EXPORT_TAG).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        assertEquals("superdro-log-app-20261012-080000.txt", graph.files.shared.single().first)
        assertEquals(listOf("The file is ready to share."), graph.messages.map { it.english() })

        onNodeWithTag("logs-tab:Record").performClick()
        settle(graph)
        onNodeWithText("Record the byte stream").assertIsDisplayed()
        onNodeWithTag(recordSwitchTag(mill)).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        assertEquals(listOf("record ${mill.value} true"), graph.connections.calls)
        assertTrue(graph.connections.session(mill).device.value.recordStream)
        onNodeWithText("No recorded link").assertIsDisplayed()
    }

    @Test
    fun aPausedMachineShowsADisabledSwitch() = runComposeUiTest {
        val graph = Graph(TestScope())
        graph.connections.add(FakeDeviceSession(DeviceId("lathe"), "Lathe", initialState = SessionState.Disconnected))
        show(graph)
        graph.navigation.push(LogsRoute)
        settle(graph)
        onNodeWithText("No log lines").assertIsDisplayed()
        onNodeWithTag(LOG_EXPORT_TAG).assertIsNotEnabled()
        onNodeWithTag("logs-tab:Record").performClick()
        settle(graph)
        onNodeWithText("The machine is paused. Connect it first.").assertIsDisplayed()
        onNodeWithTag(recordSwitchTag(DeviceId("lathe"))).performClick()
        settle(graph)
        assertTrue(graph.connections.calls.isEmpty())
    }

    private fun ComposeUiTest.onAllText(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
