package org.superdro.core.ui.settings.profiles

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.settings.SettingsThemed
import org.superdro.core.ui.settings.typechange.TYPE_CHANGE_CONFIRM_TAG
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture

/** The main flows of the machine profiles screen and of the delete question. */
@OptIn(ExperimentalTestApi::class)
class MachineProfilesUiTest {

    private class Graph(val scope: TestScope) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val presenter = MachineProfilesPresenter(store.machines, connections, scope.testPresenterContext())
        val deleted = mutableListOf<String>()

        fun run() = scope.testScheduler.advanceUntilIdle()

        fun close() {
            presenter.close()
            store.close()
        }
    }

    private fun ComposeUiTest.show(graph: Graph) {
        graph.run()
        setContent {
            SettingsThemed(dark = false) {
                val state by graph.presenter.uiState.collectAsState()
                MachineProfilesScreen(
                    state = state,
                    presenter = graph.presenter,
                    navigation = MachineProfilesNavigation(onDelete = { id -> graph.deleted += id }),
                    onBack = {},
                )
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
    fun newProfileCreatesAMillWithTheSetup() = runComposeUiTest {
        val graph = Graph(TestScope())
        show(graph)
        onNodeWithText("No machine profile yet. Use New profile to make one.").assertIsDisplayed()
        onNodeWithTag(ProfilesTags.NEW).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        onNodeWithText("New machine profile").assertIsDisplayed()
        onNodeWithTag(ProfilesTags.CREATE_NAME).performTextClearance()
        settle(graph)
        onNodeWithTag(ProfilesTags.CREATE_NAME).performTextInput("Bridgeport")
        settle(graph)
        onNodeWithTag(ProfilesTags.CREATE_CONFIRM).performScrollTo().performClick()
        settle(graph)
        assertEquals(0, onAllNodesWithTag(ProfilesTags.CREATE_SHEET).fetchSemanticsNodes().size)
        val profile = graph.presenter.uiState.value.rows.single()
        assertEquals("Bridgeport", profile.name)
        assertEquals(MachineType.VerticalMill, profile.machineType)
        onNodeWithText("Bridgeport").assertIsDisplayed()
        graph.close()
    }

    @Test
    fun theRowMenuRenamesAndAsksForTheDelete() = runComposeUiTest {
        val graph = Graph(TestScope())
        var id = ""
        graph.scope.launch { id = graph.store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.id }
        show(graph)
        onNodeWithTag(ProfilesTags.menu(id)).assertHeightIsAtLeast(64.dp).performClick()
        settle(graph)
        onNodeWithTag(ProfilesTags.item("rename")).performClick()
        settle(graph)
        onNodeWithTag(ProfilesTags.NAME_FIELD).performTextClearance()
        settle(graph)
        onNodeWithTag(ProfilesTags.NAME_FIELD).performTextInput("Deckel")
        settle(graph)
        onNodeWithTag(ProfilesTags.NAME_CONFIRM).performClick()
        settle(graph)
        assertNull(graph.presenter.uiState.value.dialog)
        onNodeWithText("Deckel").assertIsDisplayed()
        onNodeWithTag(ProfilesTags.menu(id)).performClick()
        settle(graph)
        onNodeWithTag(ProfilesTags.item("delete")).performClick()
        settle(graph)
        assertEquals(listOf(id), graph.deleted)
        graph.close()
    }

    @Test
    fun theTypeChangeShowsTheWarningAndChangesTheType() = runComposeUiTest {
        val graph = Graph(TestScope())
        var id = ""
        graph.scope.launch {
            id = graph.store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.id
            graph.store.tools.addTool(id, Tool(name = "Drill", diameterUm = 5000.0))
        }
        show(graph)
        onNodeWithTag(ProfilesTags.menu(id)).performClick()
        settle(graph)
        onNodeWithTag(ProfilesTags.item("type")).performClick()
        settle(graph)
        onNodeWithTag(ProfilesTags.type(MachineType.Lathe)).performClick()
        onNodeWithTag(ProfilesTags.TYPE_CONTINUE).performClick()
        settle(graph)
        onNodeWithText("Some data does not fit the new machine type.").assertIsDisplayed()
        onNodeWithText("• Tools of the other machine kind: 1").assertIsDisplayed()
        onNodeWithTag(TYPE_CHANGE_CONFIRM_TAG).performClick()
        settle(graph)
        assertEquals(MachineType.Lathe, graph.presenter.uiState.value.rows.single().machineType)
        graph.close()
    }

    @Test
    fun theDeleteQuestionAsksAboutTheToolsAndDeletesThem() = runComposeUiTest {
        val scope = TestScope()
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        var id = ""
        scope.launch {
            id = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.id
            store.tools.addTool(id, Tool(name = "Drill", diameterUm = 5000.0))
        }
        scope.testScheduler.advanceUntilIdle()
        val presenter = DeleteProfilePresenter(id, store.machines, store.tools, store.tabs, connections, scope.testPresenterContext())
        scope.testScheduler.advanceUntilIdle()
        setContent {
            SettingsThemed(dark = false) {
                val state by presenter.uiState.collectAsState()
                DeleteProfileDialog(state, onDelete = presenter::delete, onDismiss = {})
            }
        }
        onNodeWithText("Delete Mill?").assertIsDisplayed()
        onNodeWithText("Delete the tools of this profile too?").assertIsDisplayed()
        onNodeWithText("This profile has 1 tool.").assertIsDisplayed()
        onNodeWithTag(DeleteProfileTags.DELETE_TOOLS).assertHeightIsAtLeast(64.dp).performClick()
        repeat(3) {
            waitForIdle()
            scope.testScheduler.advanceUntilIdle()
        }
        assertTrue(presenter.uiState.value.done)
        var profile: MachineProfile? = MachineProfile()
        scope.launch { profile = store.machines.loadProfile(id) }
        scope.testScheduler.advanceUntilIdle()
        assertNull(profile)
        presenter.close()
        store.close()
    }
}
