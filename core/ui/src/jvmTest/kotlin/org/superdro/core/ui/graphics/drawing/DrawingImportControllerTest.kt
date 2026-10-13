@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.superdro.core.ui.graphics.drawing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_export_no_workspace
import org.superdro.core.resources.drawing_not_a_drawing
import org.superdro.core.resources.drawing_too_large
import org.superdro.core.resources.drawingui_export_saved
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.navigation.DrawingProblemsRoute
import org.superdro.core.ui.navigation.DrawingSetupRoute
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.PickedFile
import org.superdro.core.model.DxfImportLimits
import org.superdro.core.drawing.DrawingProblemCodes

/** The tests of the drawing import and of the actions of the drawing layer. */
class DrawingImportControllerTest {

    private suspend fun TestScope.withController(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill"),
        block: suspend TestScope.(GraphicsHarness, DrawingImportController) -> Unit,
    ) {
        val harness = GraphicsHarness(this)
        try {
            harness.start(machine)
            val controller = DrawingImportController(harness.deviceId, harness.readout, harness.deps)
            try {
                advanceUntilIdle()
                block(harness, controller)
            } finally {
                controller.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun anAsciiDxfFileOpensTheSetupWithItsShapes() = runTest {
        withController { _, controller ->
            controller.importBytes("part.dxf", DrawingFixtures.part)
            advanceUntilIdle()
            val state = controller.uiState.value
            val pending = assertNotNull(state.pending)
            assertEquals("part.dxf", pending.fileName)
            assertEquals(DrawingFormat.AsciiDxf, pending.format)
            assertEquals(listOf(ShapeType.Line, ShapeType.Circle, ShapeType.Point), DrawingConversion.toShapes(pending.document).map { shape -> shape.type })
            val event = assertIs<DrawingEvent.Navigate>(state.event)
            assertEquals(DrawingSetupRoute, event.route)
            assertFalse(state.busy)
            controller.onEventHandled(event.id)
            assertNull(controller.uiState.value.event)
        }
    }

    @Test
    fun aBinaryDxfFileOpensTheSetupToo() = runTest {
        withController { _, controller ->
            controller.importBytes("r12_mm.dxb", DrawingFixtures.binaryDxf())
            advanceUntilIdle()
            val pending = assertNotNull(controller.uiState.value.pending)
            assertEquals(DrawingFormat.BinaryDxf, pending.format)
            assertTrue(pending.document.entities.isNotEmpty())
        }
    }

    @Test
    fun aFileThatIsNotADrawingGivesTheMessage() = runTest {
        withController { _, controller ->
            controller.importBytes("notes.txt", "G0 X10 Y20\nG1 Z-1\n".encodeToByteArray())
            advanceUntilIdle()
            val event = assertIs<DrawingEvent.Message>(controller.uiState.value.event)
            assertEquals(UiText.of(Res.string.drawing_not_a_drawing), event.text)
            assertNull(controller.uiState.value.pending)
            controller.importBytes("big.dxf", ByteArray(2_162_689))
            advanceUntilIdle()
            assertEquals(UiText.of(Res.string.drawing_too_large), (controller.uiState.value.event as DrawingEvent.Message).text)
        }
    }

    @Test
    fun theImportUsesTheDxfLimitsOfTheAppSettings() = runTest {
        withController { harness, controller ->
            // Each limit is a setting. A source limit of 100 bytes refuses the fixture.
            harness.store.appSettings.update { settings -> settings.copy(dxfImportLimits = DxfImportLimits(maxSourceBytes = 100L)) }
            advanceUntilIdle()
            assertTrue(DrawingFixtures.part.size > 100)
            controller.importBytes("part.dxf", DrawingFixtures.part)
            advanceUntilIdle()
            val state = controller.uiState.value
            assertNull(state.pending)
            assertEquals(listOf(DrawingProblemCodes.SOURCE_TOO_LARGE), state.problems.map { problem -> problem.code })
            // The default limits import the same file.
            harness.store.appSettings.update { settings -> settings.copy(dxfImportLimits = DxfImportLimits()) }
            advanceUntilIdle()
            controller.importBytes("part.dxf", DrawingFixtures.part)
            advanceUntilIdle()
            assertNotNull(controller.uiState.value.pending)
        }
    }

    @Test
    fun aBrokenWireframeOpensTheProblemList() = runTest {
        withController { _, controller ->
            controller.importBytes("broken.json", DrawingFixtures.brokenWireframe)
            advanceUntilIdle()
            val state = controller.uiState.value
            assertEquals(DrawingProblemsRoute, assertIs<DrawingEvent.Navigate>(state.event).route)
            assertTrue(state.problems.isNotEmpty())
            assertTrue(state.problems.all { problem -> problem.code.isNotEmpty() && problem.message.isNotEmpty() })
            assertNull(state.pending)
            controller.clearProblems()
            assertTrue(controller.uiState.value.problems.isEmpty())
        }
    }

    @Test
    fun importFileUsesThePickerWithEachType() = runTest {
        withController { harness, controller ->
            harness.filePicker.picks += PickedFile("part.dxf", DrawingFixtures.part)
            controller.importFile()
            advanceUntilIdle()
            assertEquals(listOf(listOf("*/*")), harness.filePicker.mimeRequests)
            assertNotNull(controller.uiState.value.pending)
            harness.filePicker.picks += null
            controller.clearPending()
            controller.importFile()
            advanceUntilIdle()
            assertNull(controller.uiState.value.pending)
            assertFalse(controller.uiState.value.busy)
        }
    }

    @Test
    fun theLayerActionsChangeTheStoredDrawing() = runTest {
        withController { harness, controller ->
            val workspaceId = harness.workspace().id
            val drawing = Drawing(
                workspaceId = workspaceId,
                layers = listOf(DrawingLayer(0, "Outline"), DrawingLayer(1, "Holes")),
                shapes = listOf(
                    DrawingShape(ShapeType.Line, layerIndex = 0, x1 = 0, y1 = 0, x2 = 10_000_000, y2 = 0),
                    DrawingShape(ShapeType.Circle, layerIndex = 1, x1 = 5_000_000, y1 = 5_000_000, radius = 3_000_000),
                ),
            )
            harness.deps.drawings.replaceDrawing(drawing)
            advanceUntilIdle()
            assertEquals(2, controller.drawing.value?.shapes?.size)
            controller.setLayerHidden(1, hidden = true)
            advanceUntilIdle()
            assertEquals(true, harness.deps.drawings.loadDrawing(workspaceId)!!.layers.first { layer -> layer.index == 1 }.hidden)
            controller.deleteShape(0)
            advanceUntilIdle()
            assertEquals(listOf(ShapeType.Circle), harness.deps.drawings.loadDrawing(workspaceId)!!.shapes.map { shape -> shape.type })
            controller.deleteDrawing()
            advanceUntilIdle()
            assertNull(harness.deps.drawings.loadDrawing(workspaceId))
            assertNull(controller.drawing.value)
        }
    }

    @Test
    fun theDxfExportOfAnInchWorkspaceHasTheInchHeaderAndNoHiddenLayer() = runTest {
        val machine = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = false)
        withController(machine) { harness, controller ->
            val workspaceId = harness.workspace().id
            harness.store.machines.addSubDatum(workspaceId, SubDatum(name = "Point 1", tags = listOf("Holes"), x = 25_400.0, y = 12_700.0))
            harness.store.machines.saveGuide(workspaceId, Guide(axis = AxisId.X, valueUm = 50_800.0))
            harness.deps.drawings.replaceDrawing(
                Drawing(
                    workspaceId = workspaceId,
                    layers = listOf(DrawingLayer(0, "Outline"), DrawingLayer(1, "Secret", hidden = true)),
                    shapes = listOf(
                        DrawingShape(ShapeType.Line, layerIndex = 0, x1 = 0, y1 = 0, x2 = 25_400_000, y2 = 0),
                        DrawingShape(ShapeType.Line, layerIndex = 1, x1 = 0, y1 = 0, x2 = 0, y2 = 25_400_000),
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals(false, harness.readout.frame.value!!.metric(AxisId.X))
            controller.exportDxf()
            advanceUntilIdle()
            val (name, mime, content) = harness.filePicker.created.single()
            assertEquals("superdro-workspace-Default_Workspace-Mill-mill-20261012_080000.dxf", name)
            assertEquals("image/vnd.dxf", mime)
            val text = content.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("\$MEASUREMENT\r\n 70\r\n0\r\n"), "MEASUREMENT is 0 for inch")
            assertTrue(text.contains("\$INSUNITS\r\n 70\r\n1\r\n"), "INSUNITS is 1 for inch")
            assertTrue(text.contains("TAG_Holes"))
            assertTrue(text.contains("GUIDES"))
            assertTrue(text.contains("Outline"))
            assertFalse(text.contains("Secret"), "A hidden layer stays out of the file")
            assertEquals(UiText.of(Res.string.drawingui_export_saved), (controller.uiState.value.event as DrawingEvent.Message).text)
            controller.exportDxf(includeHiddenLayers = true)
            advanceUntilIdle()
            assertTrue(harness.filePicker.created.last().third.toString(Charsets.ISO_8859_1).contains("Secret"))
        }
    }

    @Test
    fun theExportWithNoWorkspaceGivesTheMessage() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start()
            harness.readout.close()
            val controller = DrawingImportController(harness.deviceId, NoFrameReadout(harness.deviceId), harness.deps)
            controller.exportDxf()
            advanceUntilIdle()
            assertEquals(UiText.of(Res.string.drawing_export_no_workspace), (controller.uiState.value.event as DrawingEvent.Message).text)
            controller.close()
        } finally {
            harness.close()
        }
    }

    @Test
    fun theStampIsTheLocalTime() {
        assertEquals(
            "20261012_103005",
            DrawingImportController.localStamp(kotlin.time.Instant.parse("2026-10-12T08:30:05Z"), 2 * 3600),
        )
    }
}
