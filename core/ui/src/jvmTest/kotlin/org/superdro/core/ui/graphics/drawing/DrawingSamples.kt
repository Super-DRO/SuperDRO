@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.superdro.core.ui.graphics.drawing

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.functions.drawing.ShapeGeometry
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsHarness

/** The states of the drawing screens for the UI tests and the screenshots. */
object DrawingSamples {

    /** The problem list of a broken file. */
    val problems: List<DrawingProblem> = listOf(
        DrawingProblem(DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED, "12000 entities exceeds the 10000 cap"),
        DrawingProblem(DrawingProblemCodes.WIREFRAME_INVALID, "entity 5: circle radius must be > 0, got 0 nm"),
        DrawingProblem(DrawingProblemCodes.TOO_MANY_PROBLEMS, "stopped after 50 problems; the rest of the file was not checked"),
    )

    /** A drawing of a plate: an outline, four holes, two arcs, and a named point. */
    val plate: ByteArray = DrawingFixtures.asciiDxf(
        DrawingFixtures.line(0.0, 0.0, 80.0, 0.0),
        DrawingFixtures.line(80.0, 0.0, 80.0, 50.0),
        DrawingFixtures.line(80.0, 50.0, 0.0, 50.0),
        DrawingFixtures.line(0.0, 50.0, 0.0, 0.0),
        DrawingFixtures.circle(15.0, 15.0, 3.0),
        DrawingFixtures.circle(65.0, 15.0, 3.0),
        DrawingFixtures.circle(15.0, 35.0, 3.0),
        DrawingFixtures.circle(65.0, 35.0, 3.0),
        DrawingFixtures.circle(40.0, 25.0, 10.0),
        DrawingFixtures.point(40.0, 25.0),
    )

    /**
     * Gives the state of "Set Up Drawing" for the plate with one reference set and a second point
     * picked, on a mill in mm.
     */
    fun setupState(): DrawingSetupUiState {
        lateinit var result: DrawingSetupUiState
        runTest {
            val harness = GraphicsHarness(this)
            harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
            harness.moveTo(100L, x = 12_500L, y = -4_250L, z = 0L)
            val controller = DrawingImportController(harness.deviceId, harness.readout, harness.deps)
            controller.importBytes("plate.dxf", plate)
            advanceUntilIdle()
            val presenter = DrawingSetupPresenter(controller.uiState.value.pending!!, harness.readout, harness.deps, controller)
            presenter.onPreviewSize(760.0, 600.0)
            advanceUntilIdle()
            fun tap(h: Double, v: Double) {
                val state = presenter.uiState.value
                val mark = state.marks.first { mark -> kotlin.math.abs(mark.h - h) < 1.0 && kotlin.math.abs(mark.v - v) < 1.0 }
                val screen = state.view.toScreen(mark.h, mark.v)
                presenter.onPreviewTap(screen.x, screen.y, 1.0)
            }
            tap(15_000.0, 15_000.0)
            presenter.onCardSet(0)
            advanceUntilIdle()
            tap(65_000.0, 35_000.0)
            presenter.onCardXText(1, "65.2")
            advanceUntilIdle()
            result = presenter.uiState.value
            presenter.close()
            controller.close()
            harness.close()
        }
        return result
    }

    /** Gives the state of the cutter geometry screen in the preview of an imported cutter. */
    fun toolGeometryState(): ToolGeometryUiState {
        lateinit var result: ToolGeometryUiState
        runTest {
            val harness = GraphicsHarness(this)
            harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
            val tool = harness.store.tools.addTool(harness.profile.id, Tool(kind = ToolKind.Lathe, name = "Threading tool")).valueOrNull()!!
            val presenter = ToolGeometryPresenter(tool.id, harness.readout, harness.deps)
            advanceUntilIdle()
            presenter.importBytes("cutter.dxf", DrawingFixtures.cutter)
            advanceUntilIdle()
            result = presenter.uiState.value
            presenter.close()
            harness.close()
        }
        return result
    }

    /** Gives [state] with the view fitted to the preview size [width] × [height] px. */
    fun fitted(state: DrawingSetupUiState, width: Double, height: Double): DrawingSetupUiState {
        val box = ShapeGeometry.extents(state.previewShapes) ?: return state
        val view = PreviewViewport(widthPx = width, heightPx = height).fit(
            minOf(0.0, box.minX / 1000.0),
            minOf(0.0, box.minY / 1000.0),
            maxOf(0.0, box.maxX / 1000.0),
            maxOf(0.0, box.maxY / 1000.0),
        )
        return state.copy(view = view)
    }
}

/** A [DrawingSetupEvents] that records the calls. */
class RecordingSetupEvents : DrawingSetupEvents {
    val calls = mutableListOf<String>()
    var onSize: (Double, Double) -> Unit = { _, _ -> }

    override fun onPreviewSize(width: Double, height: Double) = onSize(width, height)
    override fun onPreviewPan(dx: Double, dy: Double) { calls += "pan" }
    override fun onPreviewZoom(factor: Double, focusX: Double, focusY: Double) { calls += "zoom" }
    override fun onPreviewFit() { calls += "fit" }
    override fun onPreviewTap(x: Double, y: Double, density: Double) { calls += "tap" }
    override fun onRotateLeft() { calls += "rotateLeft" }
    override fun onRotateRight() { calls += "rotateRight" }
    override fun onMirrorHorizontal() { calls += "mirrorHorizontal" }
    override fun onMirrorVertical() { calls += "mirrorVertical" }
    override fun onCardXText(index: Int, text: String) { calls += "x$index=$text" }
    override fun onCardYText(index: Int, text: String) { calls += "y$index=$text" }
    override fun onCardSync(index: Int) { calls += "sync$index" }
    override fun onCardSet(index: Int) { calls += "set$index" }
    override fun onGroupShown(index: Int, shown: Boolean) { calls += "shown$index=$shown" }
    override fun onGroupCreate(index: Int, create: Boolean) { calls += "create$index=$create" }
    override fun onApply() { calls += "apply" }
    override fun onCancel() { calls += "cancel" }
    override fun onReset() { calls += "reset" }
}
