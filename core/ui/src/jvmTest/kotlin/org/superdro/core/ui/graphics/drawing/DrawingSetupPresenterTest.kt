@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.superdro.core.ui.graphics.drawing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.drawing.SnapKind
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.ShapeType
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawingui_applied
import org.superdro.core.resources.drawingui_group_holes
import org.superdro.core.resources.drawingui_group_points
import org.superdro.core.resources.drawingui_mismatch_closer
import org.superdro.core.resources.drawingui_mismatch_further
import org.superdro.core.resources.drawingui_needs_pick
import org.superdro.core.resources.drawingui_needs_values
import org.superdro.core.resources.drawingui_reset_done
import org.superdro.core.resources.drawingui_status_extents
import org.superdro.core.resources.drawingui_status_picked
import org.superdro.core.resources.drawingui_status_rotated
import org.superdro.core.resources.drawingui_unit_inch_as_mm
import org.superdro.core.resources.readout_unit_inch
import org.superdro.core.resources.readout_unit_mm
import org.superdro.core.functions.drawing.UnitCheck
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText

/** The tests of "Set Up Drawing". */
class DrawingSetupPresenterTest {

    private val mm = UiText.of(Res.string.readout_unit_mm)
    private val inch = UiText.of(Res.string.readout_unit_inch)

    private class Setup(val harness: GraphicsHarness, val controller: DrawingImportController, val presenter: DrawingSetupPresenter)

    private suspend fun TestScope.withSetup(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true),
        file: ByteArray = DrawingFixtures.part,
        block: suspend TestScope.(Setup) -> Unit,
    ) {
        val harness = GraphicsHarness(this)
        try {
            harness.start(machine)
            val controller = DrawingImportController(harness.deviceId, harness.readout, harness.deps)
            controller.importBytes("part.dxf", file)
            advanceUntilIdle()
            val presenter = DrawingSetupPresenter(controller.uiState.value.pending!!, harness.readout, harness.deps, controller)
            presenter.onPreviewSize(1000.0, 800.0)
            advanceUntilIdle()
            try {
                block(Setup(harness, controller, presenter))
            } finally {
                presenter.close()
                controller.close()
            }
        } finally {
            harness.close()
        }
    }

    /** Lets the state flow run, then taps the mark of the snap point at the model position ([h], [v]) µm. */
    private fun TestScope.tapMark(presenter: DrawingSetupPresenter, h: Double, v: Double) {
        advanceUntilIdle()
        val state = presenter.uiState.value
        val mark = state.marks.first { mark -> kotlin.math.abs(mark.h - h) < 1.0 && kotlin.math.abs(mark.v - v) < 1.0 }
        val screen = state.view.toScreen(mark.h, mark.v)
        presenter.onPreviewTap(screen.x, screen.y, density = 1.0)
    }

    private fun DrawingSetupPresenter.shape(type: ShapeType) = uiState.value.previewShapes.first { shape -> shape.type == type }

    @Test
    fun theSnapGroupsHaveTheirDefaultsAndTheirLabels() = runTest {
        withSetup { setup ->
            val groups = setup.presenter.uiState.value.groups
            assertEquals(listOf(SnapKind.NamedPoint, SnapKind.CircleCenter), groups.map { group -> group.kind })
            assertEquals(UiText.of(Res.string.drawingui_group_points), groups[0].label)
            assertEquals(UiText.of(Res.string.drawingui_group_holes, "6.000", 1, mm), groups[1].label)
            assertTrue(groups[0].create, "Named points have Create as sub-datums on")
            assertTrue(groups.all { group -> group.shown })
            assertEquals(2, setup.presenter.uiState.value.marks.size)
            val status = setup.presenter.uiState.value.status
            assertEquals(UiText.of(Res.string.drawingui_status_extents, 3, "0.000", "20.000", "0.000", "8.000", mm), status)
        }
    }

    @Test
    fun onAnInchProfileTheSummaryHasInchValuesAndTheInchUnit() = runTest {
        withSetup(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = false)) { setup ->
            val presenter = setup.presenter
            val state = presenter.uiState.value
            assertEquals(false, state.metric)
            assertEquals(UiText.of(Res.string.drawingui_status_extents, 3, "0.0000", "0.7874", "0.0000", "0.3150", inch), state.status)
            assertEquals(UiText.of(Res.string.drawingui_group_holes, "0.2362", 1, inch), state.groups[1].label)
            tapMark(presenter, 10_000.0, 5_000.0)
            advanceUntilIdle()
            val status = assertIs<UiText.Resource>(presenter.uiState.value.status)
            assertEquals(Res.string.drawingui_status_picked, status.resource)
            assertEquals(listOf("0.3937", "0.1969", inch), status.args.drop(1))
        }
    }

    @Test
    fun aPickFillsTheNextFreeCardAndOneReferenceMovesTheDrawing() = runTest {
        withSetup { setup ->
            val presenter = setup.presenter
            tapMark(presenter, 10_000.0, 5_000.0)
            advanceUntilIdle()
            val card = presenter.uiState.value.cards[0]
            assertEquals("10.000", card.xText)
            assertEquals("5.000", card.yText)
            assertTrue(card.hasPick)
            val status = assertIs<UiText.Resource>(presenter.uiState.value.status)
            assertEquals(Res.string.drawingui_status_picked, status.resource)
            assertEquals(listOf("10.000", "5.000", mm), status.args.drop(1))
            presenter.onCardXText(0, "110")
            presenter.onCardYText(0, "200 + 5")
            presenter.onCardSet(0)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.cards[0].set)
            val line = presenter.shape(ShapeType.Line)
            assertEquals(100_000_000L, line.x1)
            assertEquals(200_000_000L, line.y1)
            assertEquals(120_000_000L, line.x2)
        }
    }

    @Test
    fun twoReferencesRotateTheDrawing() = runTest {
        withSetup { setup ->
            val presenter = setup.presenter
            tapMark(presenter, 10_000.0, 5_000.0)
            presenter.onCardSet(0)
            tapMark(presenter, 5_000.0, 5_000.0)
            presenter.onCardXText(1, "10")
            presenter.onCardYText(1, "0")
            presenter.onCardSet(1)
            advanceUntilIdle()
            val status = assertIs<UiText.Resource>(presenter.uiState.value.status)
            assertEquals(Res.string.drawingui_status_rotated, status.resource)
            assertEquals("90.0", status.args[1])
            val line = presenter.shape(ShapeType.Line)
            assertEquals(15_000_000.0, line.x1!!.toDouble(), 2.0)
            assertEquals(-5_000_000.0, line.y1!!.toDouble(), 2.0)
            assertNull(presenter.uiState.value.banner)
        }
    }

    @Test
    fun theUnitTestFindsAnInchDrawingAndApplyScalesAndStoresIt() = runTest {
        withSetup { setup ->
            val presenter = setup.presenter
            tapMark(presenter, 10_000.0, 5_000.0)
            presenter.onCardXText(0, "0")
            presenter.onCardYText(0, "0")
            presenter.onCardSet(0)
            // The first reference moved the drawing: the point (5, 5) mm is now at (−5, 0) mm.
            tapMark(presenter, -5_000.0, 0.0)
            presenter.onCardXText(1, "-127")
            presenter.onCardYText(1, "0")
            presenter.onCardSet(1)
            presenter.onGroupCreate(1, true)
            advanceUntilIdle()
            assertEquals(UiText.of(Res.string.drawingui_unit_inch_as_mm), presenter.uiState.value.banner)
            presenter.onApply()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.done)
            val workspace = setup.harness.workspace()
            val drawing = assertNotNull(setup.harness.deps.drawings.loadDrawing(workspace.id))
            assertEquals("part.dxf", drawing.source?.fileName)
            val circle = drawing.shapes.first { shape -> shape.type == ShapeType.Circle }
            assertEquals(0.0, circle.x1!!.toDouble(), 2.0)
            assertEquals(0.0, circle.y1!!.toDouble(), 2.0)
            assertEquals(76_200_000L, circle.radius)
            val points = workspace.subDatums.associateBy { point -> point.name }
            val named = assertNotNull(points["Point 1"])
            assertEquals(listOf("Point"), named.tags)
            assertEquals(-127_000.0, named.x!!, 0.01)
            assertEquals(0.0, named.y!!, 0.01)
            val hole = assertNotNull(points["⌀152.4 Hole 1"])
            assertEquals(listOf("Hole_152.4mm"), hole.tags)
            assertNull(setup.controller.uiState.value.pending)
            assertEquals(UiText.of(Res.string.drawingui_applied), (setup.controller.uiState.value.event as DrawingEvent.Message).text)
        }
    }

    @Test
    fun theDistanceMessageTellsFurtherOrCloser() {
        val format = ReadoutFormat.Format3
        assertEquals(UiText.of(Res.string.drawingui_mismatch_further, "1.250", mm), DrawingSetupPresenter.bannerOf(UnitCheck.Mismatch(1.25), true, format))
        assertEquals(UiText.of(Res.string.drawingui_mismatch_closer, "0.500", mm), DrawingSetupPresenter.bannerOf(UnitCheck.Mismatch(-0.5), true, format))
        assertEquals(UiText.of(Res.string.drawingui_mismatch_further, "0.0500", inch), DrawingSetupPresenter.bannerOf(UnitCheck.Mismatch(1.27), false, format))
        assertNull(DrawingSetupPresenter.bannerOf(UnitCheck.Agrees, true, format))
    }

    @Test
    fun resetPutsTheDrawingBackAfterAMirror() = runTest {
        withSetup { setup ->
            val presenter = setup.presenter
            presenter.onMirrorHorizontal()
            advanceUntilIdle()
            assertEquals(-20_000_000L, presenter.shape(ShapeType.Line).x2)
            presenter.onRotateLeft()
            presenter.onReset()
            advanceUntilIdle()
            assertEquals(20_000_000L, presenter.shape(ShapeType.Line).x2)
            assertEquals(UiText.of(Res.string.drawingui_reset_done), presenter.uiState.value.message)
            assertTrue(presenter.uiState.value.cards.none { card -> card.set })
        }
    }

    @Test
    fun aSetNeedsAPickAndTwoValues() = runTest {
        withSetup { setup ->
            val presenter = setup.presenter
            presenter.onPreviewTap(2.0, 2.0, density = 1.0)
            presenter.onCardSet(0)
            advanceUntilIdle()
            assertEquals(UiText.of(Res.string.drawingui_needs_pick, 1), presenter.uiState.value.cards[0].error)
            tapMark(presenter, 10_000.0, 5_000.0)
            presenter.onCardXText(0, "")
            presenter.onCardSet(0)
            advanceUntilIdle()
            assertEquals(UiText.of(Res.string.drawingui_needs_values, 1), presenter.uiState.value.cards[0].error)
            assertTrue(presenter.uiState.value.cards.none { card -> card.set })
        }
    }

    @Test
    fun theSyncButtonCopiesTheCurrentPosition() = runTest {
        withSetup { setup ->
            setup.harness.moveTo(100L, x = 12_000L, y = 3_000L, z = 0L)
            setup.presenter.onCardSync(1)
            advanceUntilIdle()
            val card = setup.presenter.uiState.value.cards[1]
            assertEquals("12.000", card.xText)
            assertEquals("3.000", card.yText)
            assertEquals("12.000", setup.presenter.uiState.value.horizontalValue)
        }
    }

    @Test
    fun onALatheTheMachinePointHasTheVerticalSignOfThePlane() = runTest {
        withSetup(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true)) { setup ->
            val presenter = setup.presenter
            assertEquals(AxisId.Z, presenter.uiState.value.horizontalAxis)
            assertEquals(AxisId.X, presenter.uiState.value.verticalAxis)
            tapMark(presenter, 10_000.0, 5_000.0)
            presenter.onCardXText(0, "10")
            presenter.onCardYText(0, "5")
            presenter.onCardSet(0)
            advanceUntilIdle()
            val circle = presenter.shape(ShapeType.Circle)
            assertEquals(10_000_000L, circle.x1)
            assertEquals(-5_000_000L, circle.y1)
        }
    }

    @Test
    fun cancelClosesAndClearsThePendingDrawing() = runTest {
        withSetup { setup ->
            setup.presenter.onCancel()
            advanceUntilIdle()
            assertTrue(setup.presenter.uiState.value.done)
            assertNull(setup.controller.uiState.value.pending)
            assertNull(setup.harness.deps.drawings.loadDrawing(setup.harness.workspace().id))
        }
    }
}
