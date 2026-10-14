package org.superdro.core.ui.graphics.view

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.graphics.CursorGeometry
import org.superdro.core.functions.graphics.CutterCircle
import org.superdro.core.functions.graphics.DisplayFrame
import org.superdro.core.functions.graphics.GraphSelection
import org.superdro.core.functions.graphics.GuideRules
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.functions.graphics.ViewGuide
import org.superdro.core.functions.graphics.ViewPoint
import org.superdro.core.functions.thread.ThreadPoints
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolGeometry
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame

/**
 * The outline of the lathe tool from its cutter geometry. The shapes are drawing
 * coordinates (nm × [ToolGeometry.drawnIn]). The view draws them as view positions (h, v) from
 * [anchor], the tool position. Thus the outline looks the same as in the tool geometry screen.
 */
@Immutable
data class ToolOutline(val geometry: ToolGeometry, val anchor: PointUm)

/**
 * The thread outline of the armed lathe thread: the thread
 * profile and its mirror image, as polylines of view positions.
 */
@Immutable
data class ThreadOutline(val polylines: List<List<PointUm>>)

/**
 * Keeps the thread outline of the last thread spec. A new readout frame with the same armed
 * thread then gives the same [ThreadOutline] and builds no polylines. The scene flow of one tab
 * is its only user.
 */
class ThreadOutlineCache {
    private var spec: ThreadSpec? = null
    private var outline: ThreadOutline? = null

    /** Gives the thread outline of [spec] ([GraphicalSceneBuilder.threadOutline]). */
    fun of(spec: ThreadSpec): ThreadOutline? {
        if (spec != this.spec) {
            outline = GraphicalSceneBuilder.threadOutline(spec)
            this.spec = spec
        }
        return outline
    }
}

/**
 * The content of the graphical view at one time, in model µm. The view
 * position (h, v) has the vertical sign of the plane. The canvas draws the layers of the
 * scene in the draw order; the hit test uses the same values.
 *
 * @property tool The view position of the tool (the absolute positions with the tool offset), or
 *   null when a plane axis has no value.
 * @property cutter The cutter circle of a mill, or null.
 * @property points The sub-datum points that the view shows and that a tap can hit.
 * @property ghostPoints The points outside the view of an armed function.
 * @property guides The guides that the view shows and that a tap can hit.
 * @property ghostGuides The guides while an armed function restricts the view.
 * @property displayFrame The Rotate Part and Align Axes frame of the drawing, the reference image,
 *   and the points. The identity outside the plane `XY`.
 * @property drawingAlpha The alpha of the drawing layer: 0, 0.5, or 1, halved while an
 *   armed function restricts the view.
 * @property metric The unit of the ruler ticks of the cursor.
 */
@Immutable
data class GraphicalScene(
    val plane: Plane = Plane.XY,
    val machineType: MachineType = MachineType.VerticalMill,
    val tool: PointUm? = null,
    val cutter: CutterCircle? = null,
    val toolOutline: ToolOutline? = null,
    val points: List<ViewPoint> = emptyList(),
    val ghostPoints: List<PointUm> = emptyList(),
    val guides: List<ViewGuide> = emptyList(),
    val ghostGuides: List<ViewGuide> = emptyList(),
    val selection: GraphSelection = GraphSelection.None,
    val drawing: Drawing? = null,
    val drawingAlpha: Double = 1.0,
    val drawingSelectable: Boolean = true,
    val selectedShape: Int? = null,
    val displayFrame: DisplayFrame = DisplayFrame(),
    val threadOutline: ThreadOutline? = null,
    val metric: Boolean = true,
    val display: GraphicalDisplay = GraphicalDisplay(),
    val restricted: Boolean = false,
) {
    /** The matrix of [displayFrame] for the drawing and the reference image. */
    val frameMatrix: Matrix3 get() = if (displayFrame.isIdentity) Matrix3.IDENTITY else displayFrame.toMatrix()

    /** The selected point, when the selection is a point of [points]. */
    val selectedPoint: ViewPoint?
        get() = (selection as? GraphSelection.Point)?.let { selected -> points.firstOrNull { point -> point.id == selected.id } }
}

/**
 * Builds the [GraphicalScene] of a readout frame. The
 * function is pure, so that the tests can check each layer.
 */
object GraphicalSceneBuilder {

    /** The alpha of the drawing when it is dimmed (127 of 255). */
    const val DIMMED_DRAWING_ALPHA: Double = 127.0 / 255.0

    /**
     * Gives the scene.
     *
     * @param tools The tool library of the profile, for the lathe tool outline.
     * @param threads The threads of the workspace, for the armed thread outline.
     * @param selectedGuideId The guide that the view selected, or null.
     * @param selectedShape The index of the selected drawing shape, or null.
     * @param threadOutlines Gives the thread outline of a spec, for example [ThreadOutlineCache.of].
     */
    fun build(
        frame: ReadoutFrame,
        drawing: Drawing? = null,
        tools: List<Tool> = emptyList(),
        threads: List<ThreadSpec> = emptyList(),
        selectedGuideId: String? = null,
        selectedShape: Int? = null,
        threadOutlines: (ThreadSpec) -> ThreadOutline? = ::threadOutline,
    ): GraphicalScene {
        val profile = frame.profile
        val workspace = frame.workspace
        val machineType = profile.machineType
        val plane = PlaneAxes.planeFor(machineType.isLathe, workspace.plane)
        val display = profile.graphicalDisplay
        val displayFrame = displayFrameOf(frame, plane)
        val tool = toolPosition(frame, plane)
        val armed = workspace.armedThreadId?.let { id -> threads.firstOrNull { thread -> thread.id == id } }
        val restrictedIds = armed?.let { spec -> ThreadPoints.cornerSubDatums(spec, workspace.subDatums).mapTo(HashSet()) { point -> point.id } }
        val allPoints = viewPoints(workspace, plane, displayFrame)
        val points = if (restrictedIds == null) allPoints else allPoints.filter { point -> point.id in restrictedIds }
        val ghostPoints = if (restrictedIds == null) emptyList() else allPoints.filter { point -> point.id !in restrictedIds }.map { point -> point.position }
        val allGuides = GuideRules.viewsOf(plane, workspace.guides)
        val guides = if (restrictedIds == null) allGuides else emptyList()
        val ghostGuides = if (restrictedIds == null) emptyList() else allGuides
        val selection = when {
            selectedGuideId != null && guides.any { guide -> guide.id == selectedGuideId } -> GraphSelection.Guide(selectedGuideId)
            workspace.activeSubDatumId != null && points.any { point -> point.id == workspace.activeSubDatumId } ->
                GraphSelection.Point(workspace.activeSubDatumId!!)
            else -> GraphSelection.None
        }
        val baseAlpha = when {
            !display.drawingShown -> 0.0
            display.drawingDimmed -> DIMMED_DRAWING_ALPHA
            else -> 1.0
        }
        return GraphicalScene(
            plane = plane,
            machineType = machineType,
            tool = tool,
            cutter = tool?.let { position -> cutterOf(frame, plane, position) },
            toolOutline = tool?.let { position -> toolOutlineOf(workspace, machineType, tools, position) },
            points = points,
            ghostPoints = ghostPoints,
            guides = guides,
            ghostGuides = ghostGuides,
            selection = selection,
            drawing = drawing,
            drawingAlpha = if (restrictedIds != null) baseAlpha / 2.0 else baseAlpha,
            drawingSelectable = restrictedIds == null,
            selectedShape = selectedShape.takeIf { restrictedIds == null },
            displayFrame = displayFrame,
            threadOutline = armed?.let(threadOutlines),
            metric = frame.metric(PlaneAxes.horizontal(plane)),
            display = display,
            restricted = restrictedIds != null,
        )
    }

    /**
     * Gives the view position of the tool: the absolute positions of the plane axes with the tool
     * offset, and the vertical sign of the plane. Null when a plane axis has no value.
     */
    fun toolPosition(frame: ReadoutFrame, plane: Plane): PointUm? {
        val h = frame.reading(PlaneAxes.horizontal(plane))
        val v = frame.reading(PlaneAxes.vertical(plane))
        if (!h.hasValue || !v.hasValue) return null
        return PlaneAxes.toView(plane, h.absolute, v.absolute)
    }

    /** Gives the display frame of the plane `XY`, else the identity. */
    fun displayFrameOf(frame: ReadoutFrame, plane: Plane): DisplayFrame {
        if (plane != Plane.XY) return DisplayFrame()
        val angular = if (frame.enabled(AxisId.Angular) && frame.reading(AxisId.Angular).hasValue) frame.reading(AxisId.Angular).shown else null
        return DisplayFrame.of(frame.workspace, angular, frame.profile.machineType, frame.profile.machineMode)
    }

    /**
     * Gives the points of the sub-datums with both plane coordinates, through the display frame.
     * The rank of a point is its place in the list, so a newer point has a higher rank.
     */
    fun viewPoints(workspace: Workspace, plane: Plane, displayFrame: DisplayFrame): List<ViewPoint> =
        workspace.subDatums.mapIndexedNotNull { index, subDatum ->
            val stored = PlaneAxes.viewOf(plane, subDatum) ?: return@mapIndexedNotNull null
            val shown = if (displayFrame.isIdentity) stored else displayFrame.apply(Vec2.of(stored)).toPointUm()
            ViewPoint(subDatum.id, shown, index.toLong())
        }

    /** Gives the cutter circle (layer 4): the tool offsets in use and the stored diameter. */
    fun cutterOf(frame: ReadoutFrame, plane: Plane, tool: PointUm): CutterCircle? {
        if (frame.profile.machineType.isLathe) return null
        val offsetX = if (frame.toolOffsetInUse(AxisId.X)) frame.state(AxisId.X).toolOffset else 0.0
        val offsetY = if (frame.toolOffsetInUse(AxisId.Y)) frame.state(AxisId.Y).toolOffset else 0.0
        return CursorGeometry.cutter(plane, frame.profile.machineType, tool, offsetX, offsetY, frame.workspace.toolXUm)
    }

    /** Gives the outline of the active lathe tool when it has a cutter geometry. */
    fun toolOutlineOf(workspace: Workspace, machineType: MachineType, tools: List<Tool>, tool: PointUm): ToolOutline? {
        if (!machineType.isLathe) return null
        val active = tools.firstOrNull { item -> item.id == workspace.activeToolId } ?: return null
        val geometry = active.geometry?.takeIf { value -> value.shapes.isNotEmpty() } ?: return null
        return ToolOutline(geometry, tool)
    }

    /**
     * Gives the thread outline of [spec]: the profile from Zmin to Zmax with the pitch P,
     * between the major radius and the minor radius, and its mirror image at −X. In the plane `ZX`
     * the view position is (Z, −X).
     */
    fun threadOutline(spec: ThreadSpec): ThreadOutline? {
        val zMin = min(spec.startZUm, spec.stopZUm)
        val zMax = max(spec.startZUm, spec.stopZUm)
        val pitch = spec.pitchUm
        val major = spec.majorDiameterUm / 2.0
        val minor = spec.minorDiameterUm / 2.0
        if (!(pitch > 0.0) || !(zMax > zMin) || !(major > 0.0) || !(minor > 0.0)) return null
        val crest = if (spec.internal) minor else major
        val root = if (spec.internal) major else minor
        val half = pitch / 2.0
        val count = ((zMax - zMin) / half).toInt().coerceAtMost(MAX_TEETH * 2)
        val profile = ArrayList<Pair<Double, Double>>()
        for (step in 0..count) {
            val z = zMin + step * half
            profile += z to if (step % 2 == 0) crest else root
        }
        if (abs(profile.last().first - zMax) > 0.5) profile += zMax to if (count % 2 == 0) root else crest
        val front = profile.map { (z, x) -> PlaneAxes.toView(Plane.ZX, z, x) }
        val back = profile.map { (z, x) -> PlaneAxes.toView(Plane.ZX, z, -x) }
        return ThreadOutline(listOf(front, back))
    }

    /** The largest number of teeth of the thread outline. A longer thread shows the first teeth. */
    const val MAX_TEETH: Int = 2000
}
