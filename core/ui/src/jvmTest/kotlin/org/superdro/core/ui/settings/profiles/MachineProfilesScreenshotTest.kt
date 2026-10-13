package org.superdro.core.ui.settings.profiles

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.renderSettings
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture

/**
 * Draws the machine profiles screen, the create sheet, the type change, and the delete question to
 * PNG files (`core/ui/build/screenshots/settings-profiles-*.png`) in light and dark mode.
 */
class MachineProfilesScreenshotTest {

    private class Graph {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val presenter = MachineProfilesPresenter(store.machines, connections, scope.testPresenterContext())
        val ids = mutableMapOf<String, String>()

        init {
            scope.launch {
                listOf("Bridgeport" to MachineType.VerticalMill, "Myford" to MachineType.Lathe, "Horizontal" to MachineType.HorizontalMill)
                    .forEach { (name, type) -> ids[name] = store.machines.createProfile(MachineProfile.create(type, name)).valueOrNull()!!.id }
                val mill = ids.getValue("Bridgeport")
                store.machines.updateProfile(mill) { profile -> profile.copy(metricDefault = true) }
                store.tools.addTool(mill, Tool(name = "Drill", diameterUm = 5000.0))
                val workspace = store.machines.workspaces(mill).first()
                store.machines.addSubDatum(workspace.first().id, SubDatum(name = "Hole", x = 1000.0, y = 2000.0))
            }
            scope.testScheduler.advanceUntilIdle()
            connections.add(FakeDeviceSession(DeviceId(ids.getValue("Bridgeport")), "Bridgeport", initialState = SessionState.Connected(0L)))
            scope.testScheduler.advanceUntilIdle()
        }

        fun render(name: String, windows: List<SettingsWindow> = SettingsWindow.entries) {
            scope.testScheduler.advanceUntilIdle()
            val files = renderSettings(name, windows) {
                val state by presenter.uiState.collectAsState()
                MachineProfilesScreen(state, presenter, MachineProfilesNavigation(hasTab = { id -> id == ids["Bridgeport"] }), onBack = {})
            }
            assertTrue(files.all { file -> file.length() > 0 })
        }

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun theListOfTheProfiles() {
        val graph = Graph()
        graph.render("profiles-list")
        graph.presenter.openMenu(graph.ids.getValue("Bridgeport"))
        graph.render("profiles-menu", listOf(SettingsWindow.Landscape))
        graph.close()
    }

    @Test
    fun theCreateSheet() {
        val graph = Graph()
        graph.presenter.openCreate()
        graph.render("profiles-create")
        graph.close()
    }

    @Test
    fun theRenameDialogWithTheRefusal() {
        val graph = Graph()
        graph.presenter.openRename(graph.ids.getValue("Myford"))
        graph.presenter.setRenameName("bridgeport")
        graph.presenter.confirmRename()
        graph.render("profiles-rename", listOf(SettingsWindow.Landscape))
        graph.close()
    }

    @Test
    fun theTypeChangeDialogs() {
        val graph = Graph()
        graph.presenter.openChangeType(graph.ids.getValue("Bridgeport"))
        graph.presenter.selectType(MachineType.Lathe)
        graph.render("profiles-type-choice", listOf(SettingsWindow.Landscape))
        graph.presenter.confirmType()
        graph.render("profiles-type-change")
        graph.close()
    }

    @Test
    fun theDeleteQuestion() {
        val graph = Graph()
        val presenter = DeleteProfilePresenter(
            graph.ids.getValue("Bridgeport"), graph.store.machines, graph.store.tools, graph.store.tabs, graph.connections, graph.scope.testPresenterContext(),
        )
        graph.scope.testScheduler.advanceUntilIdle()
        val files = renderSettings("profiles-delete") {
            val state by presenter.uiState.collectAsState()
            DeleteProfileDialog(state, onDelete = {}, onDismiss = {})
        }
        assertTrue(files.all { file -> file.length() > 0 })
        presenter.close()
        graph.close()
    }
}
