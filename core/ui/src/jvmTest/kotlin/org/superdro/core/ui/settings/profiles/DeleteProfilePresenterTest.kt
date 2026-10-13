package org.superdro.core.ui.settings.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture

/** The delete question of a profile. */
class DeleteProfilePresenterTest {

    private class Harness(val scope: TestScope, toolCount: Int) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        lateinit var id: String
        lateinit var presenter: DeleteProfilePresenter

        suspend fun start(toolCount: Int) {
            id = store.machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!.id
            repeat(toolCount) { index -> store.tools.addTool(id, Tool(name = "Tool ${index + 1}", diameterUm = 1000.0 * (index + 1))) }
            store.tabs.remember(id)
            connections.add(FakeDeviceSession(DeviceId(id), "Mill", initialState = SessionState.Connected(0L)))
            presenter = DeleteProfilePresenter(id, store.machines, store.tools, store.tabs, connections, scope.testPresenterContext())
        }

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun theQuestionUsesTheTextOfTheStore() {
        assertEquals("Delete the tools of this profile too?", DELETE_TOOLS_QUESTION_TEXT.english())
    }

    @Test
    fun deleteTheToolsRemovesTheProfileItsToolsAndItsTab() = runTest {
        val harness = Harness(this, 2)
        harness.start(2)
        runCurrent()
        val state = harness.presenter.uiState.value
        assertTrue(state.loaded)
        assertEquals("Mill", state.name)
        assertEquals(2, state.toolCount)
        harness.presenter.delete(deleteTools = true)
        runCurrent()
        assertTrue(harness.presenter.uiState.value.done)
        assertEquals("Mill is deleted.", harness.presenter.uiState.value.result!!.english())
        assertNull(harness.store.machines.loadProfile(harness.id))
        assertTrue(harness.store.tools.sharedTools(ToolKind.Mill).first().isEmpty())
        assertTrue(harness.store.tabs.state.value.remembered.isEmpty())
        assertEquals(listOf("remove ${harness.id}"), harness.connections.calls)
        assertTrue(harness.connections.sessions.value.isEmpty())
        harness.close()
    }

    @Test
    fun keepTheToolsMovesThemToTheSharedLibrary() = runTest {
        val harness = Harness(this, 2)
        harness.start(2)
        runCurrent()
        harness.presenter.delete(deleteTools = false)
        runCurrent()
        assertNull(harness.store.machines.loadProfile(harness.id))
        assertEquals(listOf("Tool 1", "Tool 2"), harness.store.tools.sharedTools(ToolKind.Mill).first().map { it.name }.sorted())
        harness.close()
    }

    @Test
    fun aProfileWithNoToolHasNoToolQuestionAndASecondDeleteDoesNothing() = runTest {
        val harness = Harness(this, 0)
        harness.start(0)
        runCurrent()
        assertEquals(0, harness.presenter.uiState.value.toolCount)
        harness.presenter.delete(deleteTools = false)
        runCurrent()
        harness.presenter.delete(deleteTools = true)
        runCurrent()
        assertEquals(listOf("remove ${harness.id}"), harness.connections.calls)
        assertFalse(harness.presenter.uiState.value.busy)
        harness.close()
    }
}
