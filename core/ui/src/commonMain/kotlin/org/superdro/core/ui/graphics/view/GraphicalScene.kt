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
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.SubDatum
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
 * The key of the structure of a scene. Two frames with equal keys have the same points, guides,
 * selection, and thread outline. A list compares by identity first, so an unchanged workspace
 * compares fast.
 */
internal data class StructureKey(
    val subDatums: List<SubDatum>,
    val guides: List<Guide>,
    val activeSubDatumId: String?,
    val plane: Plane,
    val displayFrame: DisplayFrame,
    val armed: ThreadSpec?,
    val selectedGuideId: String?,
)

/** The parts of a scene that do not change when only the tool moves. */
internal class SceneStructure(
    val displayFrame: DisplayFrame,
    val points: List<ViewPoint>,
    val ghostPoints: List<PointUm>,
    val guides: List<ViewGuide>,
    val ghostGuides: List<ViewGuide>,
    val selection: GraphSelection,
    val restricted: Boolean,
    val threadOutline: ThreadOutline?,
)

/**
 * Builds the scenes of one view and keeps the structure of the last scene. When only the live
 * parts change (the tool and the cutter), the next scene uses the same lists of points and
 * guides. Thus a frame with many sub-datums does not make the lists again, and the equality check
 * of two scenes is fast. Use one cache in only one flow: the cache has no lock.
 */
class GraphicalSceneCache {

    private var key: StructureKey? = null
    private var structure: SceneStructure? = null

    /** Gives the scene, the same as [GraphicalSceneBuilder.build]. */
    fun build(
        frame: ReadoutFrame,
        drawing: Drawing? = null,
        tools: List<Tool> = emptyList(),
        threads: List<ThreadSpec> = emptyList(),
        selectedGuideId: String? = null,
        selectedShape: Int? = null,
    ): GraphicalScene {
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        val nextKey = GraphicalSceneBuilder.structureKey(frame, plane, threads, selectedGuideId)
        val cached = structure
        val next = if (cached != null && nextKey == key) {
            cached
        } else {
            GraphicalSceneBuilder.structureOf(nextKey).also { value ->
                key = nextKey
                structure = value
            }
        }
        return GraphicalSceneBuilder.assemble(frame, plane, next, drawing, tools, selectedShape)
    }
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
     */
    fun build(
        frame: ReadoutFrame,
        drawing: Drawing? = null,
        tools: List<Tool> = emptyList(),
        threads: List<ThreadSpec> = emptyList(),
        selectedGuideId: String? = null,
        selectedShape: Int? = null,
    ): GraphicalScene {
        val plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        val key = structureKey(frame, plane, threads, selectedGuideId)
        return assemble(frame, plane, structureOf(key), drawing, tools, selectedShape)
    }

    /**
     * Gives the key of the structure of the scene: all the values that the points, the guides, the
     * selection, and the thread outline use. The tool position is not part of the key.
     */
    internal fun structureKey(frame: ReadoutFrame, plane: Plane, threads: List<ThreadSpec>, selectedGuideId: String?): StructureKey {
        val workspace = frame.workspace
        return StructureKey(
            subDatums = workspace.subDatums,
            guides = workspace.guides,
            activeSubDatumId = workspace.activeSubDatumId,
            plane = plane,
            displayFrame = displayFrameOf(frame, plane),
            armed = workspace.armedThreadId?.let { id -> threads.firstOrNull { thread -> thread.id == id } },
            selectedGuideId = selectedGuideId,
        )
    }

    /** Gives the structure of the scene from [key]. This is the slow part with many sub-datums. */
    internal fun structureOf(key: StructureKey): SceneStructure {
        val plane = key.plane
        val armed = key.armed
        val restrictedIds = armed?.let { spec -> ThreadPoints.cornerSubDatums(spec, key.subDatums).mapTo(HashSet()) { point -> point.id } }
        val allPoints = viewPoints(key.subDatums, plane, key.displayFrame)
        val points = if (restrictedIds == null) allPoints else allPoints.filter { point -> point.id in restrictedIds }
        val ghostPoints = if (restrictedIds == null) emptyList() else allPoints.filter { point -> point.id !in restrictedIds }.map { point -> point.position }
        val allGuides = GuideRules.viewsOf(plane, key.guides)
        val guides = if (restrictedIds == null) allGuides else emptyList()
        val ghostGuides = if (restrictedIds == null) emptyList() else allGuides
        val selectedGuideId = key.selectedGuideId
        val activeSubDatumId = key.activeSubDatumId
        val selection = when {
            selectedGuideId != null && guides.any { guide -> guide.id == selectedGuideId } -> GraphSelection.Guide(selectedGuideId)
            activeSubDatumId != null && points.any { point -> point.id == activeSubDatumId } -> GraphSelection.Point(activeSubDatumId)
            else -> GraphSelection.None
        }
        return SceneStructure(
            displayFrame = key.displayFrame,
            points = points,
            ghostPoints = ghostPoints,
            guides = guides,
            ghostGuides = ghostGuides,
            selection = selection,
            restricted = restrictedIds != null,
            threadOutline = armed?.let(::threadOutline),
        )
    }

    /** Gives the scene from the [structure] and the live parts of [frame]: the tool and the cutter. */
    internal fun assemble(
        frame: ReadoutFrame,
        plane: Plane,
        structure: SceneStructure,
        drawing: Drawing?,
        tools: List<Tool>,
        selectedShape: Int?,
    ): GraphicalScene {
        val profile = frame.profile
        val workspace = frame.workspace
        val machineType = profile.machineType
        val display = profile.graphicalDisplay
        val tool = toolPosition(frame, plane)
        val restricted = structure.restricted
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
            points = structure.points,
            ghostPoints = structure.ghostPoints,
            guides = structure.guides,
            ghostGuides = structure.ghostGuides,
            selection = structure.selection,
            drawing = drawing,
            drawingAlpha = if (restricted) baseAlpha / 2.0 else baseAlpha,
            drawingSelectable = !restricted,
            selectedShape = selectedShape.takeIf { !restricted },
            displayFrame = structure.displayFrame,
            threadOutline = structure.threadOutline,
            metric = frame.metric(PlaneAxes.horizontal(plane)),
            display = display,
            restricted = restricted,
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
        viewPoints(workspace.subDatums, plane, displayFrame)

    /** Gives the points of [subDatums] with both plane coordinates, through the display frame. */
    fun viewPoints(subDatums: List<SubDatum>, plane: Plane, displayFrame: DisplayFrame): List<ViewPoint> =
        subDatums.mapIndexedNotNull { index, subDatum ->
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
