@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.superdro.core.ui.graphics.drawing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.drawing.tool.ToolGeometryCheck
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_not_a_drawing
import org.superdro.core.resources.drawingui_tool_attached
import org.superdro.core.resources.drawingui_tool_removed
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.PickedFile

/** The tests of the cutter geometry screen of a lathe tool. */
class ToolGeometryPresenterTest {

    private suspend fun TestScope.withTool(block: suspend TestScope.(GraphicsHarness, Tool, ToolGeometryPresenter) -> Unit) {
        val harness = GraphicsHarness(this)
        try {
            harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
            val tool = harness.store.tools.addTool(harness.profile.id, Tool(kind = ToolKind.Lathe, name = "Boring bar")).valueOrNull()!!
            val presenter = ToolGeometryPresenter(tool.id, harness.readout, harness.deps)
            try {
                advanceUntilIdle()
                block(harness, tool, presenter)
            } finally {
                presenter.close()
            }
        } finally {
            harness.close()
        }
    }

    private suspend fun GraphicsHarness.storedTool(id: String): Tool = store.tools.loadLibrary(profile.id).first { tool -> tool.id == id }

    @Test
    fun anImportShowsThePreviewAndAttachStoresTheGeometry() = runTest {
        withTool { harness, tool, presenter ->
            assertTrue(presenter.uiState.value.loaded)
            assertNull(presenter.uiState.value.geometry)
            harness.filePicker.picks += PickedFile("cutter.dxf", DrawingFixtures.cutter)
            presenter.onImport()
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertTrue(state.preview)
            val report = assertNotNull(state.report)
            assertEquals(emptyList(), report.checks)
            assertEquals(10_000.0, report.widthUm, 0.01)
            assertEquals(4_000.0, report.heightUm, 0.01)
            presenter.onDrawnIn(inch = true)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.drawnInInch)
            assertEquals(254_000.0, presenter.uiState.value.report!!.widthUm, 0.1)
            presenter.onAttach()
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.preview)
            assertEquals(UiText.of(Res.string.drawingui_tool_attached), presenter.uiState.value.message)
            val stored = assertNotNull(harness.storedTool(tool.id).geometry)
            assertEquals(25.4, stored.drawnIn)
            assertEquals(3, stored.shapes.size)
            assertEquals("cutter.dxf", stored.source?.fileName)
        }
    }

    @Test
    fun removeClearsTheGeometryOfTheTool() = runTest {
        withTool { harness, tool, presenter ->
            presenter.importBytes("cutter.dxf", DrawingFixtures.cutter)
            advanceUntilIdle()
            presenter.onAttach()
            advanceUntilIdle()
            assertNotNull(presenter.uiState.value.geometry)
            presenter.onRemove()
            advanceUntilIdle()
            assertNull(harness.storedTool(tool.id).geometry)
            assertNull(presenter.uiState.value.geometry)
            assertEquals(UiText.of(Res.string.drawingui_tool_removed), presenter.uiState.value.message)
        }
    }

    @Test
    fun anOpenOutlineAndAFileThatIsNotADrawingShowTheirTexts() = runTest {
        withTool { harness, tool, presenter ->
            presenter.importBytes("open.dxf", DrawingFixtures.asciiDxf(DrawingFixtures.line(0.0, 0.0, 10.0, 2.0), DrawingFixtures.line(10.0, 2.0, 10.0, -2.0)))
            advanceUntilIdle()
            val checks = presenter.uiState.value.report!!.checks
            assertTrue(ToolGeometryCheck.NotClosed in checks)
            assertEquals(UiText.Key("drawing_tool_geometry_not_closed", ToolGeometryCheck.NotClosed.message), ToolGeometryPresenter.checkText(ToolGeometryCheck.NotClosed))
            presenter.onCancelPreview()
            presenter.importBytes("notes.txt", "hello".encodeToByteArray())
            advanceUntilIdle()
            assertEquals(UiText.of(Res.string.drawing_not_a_drawing), presenter.uiState.value.message)
            assertFalse(presenter.uiState.value.preview)
            assertNull(harness.storedTool(tool.id).geometry)
        }
    }
}
