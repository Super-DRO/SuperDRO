package org.superdro.core.ui.functions.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.text.UiText

/** The presenters of the tool library, the tool edit sheet, and the tool panel. */
class ToolPresentersTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)
    private val messages = mutableListOf<UiText>()

    @AfterTest
    fun close() = harness.close()

    private suspend fun addTool(tool: Tool) = harness.store.tools.addTool(harness.profile.id, tool)

    @Test
    fun theSharedLibrarySwitchChangesTheListAtOnce() = scope.runTest {
        harness.start()
        addTool(Tool(name = "Own", diameterUm = 6_000.0))
        val presenter = ToolLibraryPresenter(harness.profile.id, harness.services, harness.tab.undo, messages::add)
        advanceUntilIdle()
        assertEquals(listOf("Own"), presenter.uiState.value.rows.map { it.name })
        assertFalse(presenter.uiState.value.sharedLibrary)
        presenter.onSharedLibrary(true)
        advanceUntilIdle()
        assertTrue(presenter.uiState.value.sharedLibrary)
        assertEquals(emptyList(), presenter.uiState.value.rows)
        addTool(Tool(name = "Shared", diameterUm = 6_000.0))
        advanceUntilIdle()
        assertEquals(listOf("Shared"), presenter.uiState.value.rows.map { it.name })
        presenter.onSharedLibrary(false)
        advanceUntilIdle()
        assertEquals(listOf("Own"), presenter.uiState.value.rows.map { it.name })
        presenter.close()
    }

    @Test
    fun theSelectedToolsDeleteWithUndoForTenSeconds() = scope.runTest {
        harness.start()
        addTool(Tool(name = "A", diameterUm = 6_000.0))
        addTool(Tool(name = "B", diameterUm = 8_000.0))
        addTool(Tool(name = "C", diameterUm = 10_000.0))
        val presenter = ToolLibraryPresenter(harness.profile.id, harness.services, harness.tab.undo, messages::add)
        advanceUntilIdle()
        val ids = presenter.uiState.value.rows.associate { it.name to it.id }
        presenter.onToggleSelected(ids.getValue("A"))
        presenter.onToggleSelected(ids.getValue("C"))
        runCurrent()
        assertEquals(2, presenter.uiState.value.selectedIds.size)
        presenter.onDeleteSelected()
        // runCurrent and not advanceUntilIdle: the virtual time must not pass the 10 s of the undo.
        runCurrent()
        assertEquals(listOf("B"), presenter.uiState.value.rows.map { it.name })
        val undo = assertNotNull(harness.tab.undo.pending.value)
        assertTrue(harness.tab.undo.undo(undo.id))
        runCurrent()
        assertEquals(listOf("A", "B", "C"), presenter.uiState.value.rows.map { it.name })
        // A second delete times out after 10 s: the undo goes away.
        presenter.onDelete(ids.getValue("B"))
        runCurrent()
        assertNotNull(harness.tab.undo.pending.value)
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(harness.tab.undo.pending.value)
        presenter.close()
    }

    @Test
    fun theExportWritesTheSuperDroCsvWithSevenLatheValues() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "South Bend"))
        addTool(Tool(id = "4", kind = ToolKind.Lathe, name = "Boring Bar", description = "3/8 bar", internal = true, xOffsetUm = -3_175.0, zOffsetUm = 9_525.0))
        val presenter = ToolLibraryPresenter(harness.profile.id, harness.services, harness.tab.undo, messages::add)
        advanceUntilIdle()
        presenter.onExport()
        advanceUntilIdle()
        val (name, mime, bytes) = harness.filePicker.created.single()
        assertEquals("text/csv", mime)
        assertEquals("superdro-tools-South_Bend-lathe-20261012_080000.csv", name)
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("# superdro: tools\r\n"), text)
        assertFalse("device" in text)
        assertTrue("id,name,description,metric,internal,x_offset,z_offset\r\n" in text, text)
        val row = text.lines().first { it.contains("Boring Bar") }.trimEnd('\r')
        assertEquals(7, row.split(',').size)
        assertTrue(row.endsWith(",no,yes,-0.125,0.375"), row)
        assertEquals(1, messages.size)
        presenter.close()
    }

    @Test
    fun theToolPanelActivatesAndClearsAToolWithUndo() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe"))
        addTool(Tool(kind = ToolKind.Lathe, name = "OD", xOffsetUm = 500.0, zOffsetUm = -250.0))
        val panel = ToolPanelPresenter(harness.profile.id, harness.services, harness.presenter)
        advanceUntilIdle()
        val row = panel.uiState.value.rows.single()
        assertFalse(row.active)
        panel.onTap(row.id)
        runCurrent()
        assertTrue(panel.uiState.value.rows.single().active)
        assertEquals(AxisState(toolOffset = 500.0, toolOffsetActive = true, diameterMode = true), harness.readout.axisState(AxisId.X))
        assertNotNull(harness.presenter.uiState.value.undo)
        panel.onTap(row.id)
        advanceUntilIdle()
        assertFalse(panel.uiState.value.rows.single().active)
        assertFalse(harness.readout.axisState(AxisId.X).toolOffsetActive)
        panel.close()
    }

    @Test
    fun theEditSheetChecksAndStoresANewMillTool() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.HorizontalMill, "Horizontal").copy(metricDefault = true))
        val edit = ToolEditPresenter(harness.profile.id, null, harness.services, harness.presenter.frame, harness.presenter::positionUnavailable)
        advanceUntilIdle()
        assertTrue(edit.uiState.value.metric)
        assertFalse(edit.save())
        assertEquals(setOf(ToolField.Name, ToolField.Diameter, ToolField.ToothCount, ToolField.ZOffset), edit.uiState.value.errors.keys)
        edit.onName("1/4 End Mill")
        edit.onMetric(false)
        edit.onDiameter("1/4")
        edit.onToothCount("2")
        edit.onZOffset("1.25")
        assertTrue(edit.save())
        advanceUntilIdle()
        val tool = harness.store.tools.loadLibrary(harness.profile.id).single()
        assertEquals(ToolKind.Mill, tool.kind)
        assertEquals(6_350.0, tool.diameterUm, 1e-9)
        assertEquals(31_750.0, tool.zOffsetUm, 1e-9)
        assertFalse(tool.metric)
        edit.close()
    }

    @Test
    fun theSyncButtonsOfALatheToolUseTheShownPosition() = scope.runTest {
        harness.start(
            MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true),
            states = mapOf(AxisId.X to AxisState(diameterMode = true, toolOffset = 200.0, toolOffsetActive = true)),
        )
        harness.inputs(AxisId.X to 1_000.0, AxisId.Z to -2_000.0)
        val edit = ToolEditPresenter(harness.profile.id, null, harness.services, harness.presenter.frame, harness.presenter::positionUnavailable)
        advanceUntilIdle()
        edit.onSync(AxisId.X)
        edit.onSync(AxisId.Z)
        // X: −((1000 + 200) − 200) = −1000 µm (a radius). Z: −(−2000) = 2000 µm.
        assertEquals(-1_000.0, edit.uiState.value.xOffset.valueUm!!, 1e-9)
        assertEquals("-1", edit.uiState.value.xOffset.text)
        assertEquals(2_000.0, edit.uiState.value.zOffset.valueUm!!, 1e-9)
        edit.close()
    }
}
