package org.superdro.core.ui.graphics.view

import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame

/** The sample machines and frames of the graphical view tests. */
object ViewSamples {

    val mill: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)

    val lathe: MachineProfile = MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true)

    /** The M10 × 1.5 external thread: crest 10.000 mm, root 8.128 mm, Full Form. */
    fun m10Thread(workspaceId: String = ""): ThreadSpec = ThreadSpec(
        id = "thread-m10",
        workspaceId = workspaceId,
        tag = "Thread 1",
        size = "M10 × 1.5",
        internal = false,
        cutter = ThreadCutter.FullForm,
        pitchUm = 1500.0,
        majorDiameterUm = 10_000.0,
        minorDiameterUm = 8_128.0,
        pitchDiameterUm = 9_026.0,
        preOpDiameterUm = 10_000.0,
        cutToDiameterUm = 8_128.0,
        startZUm = 0.0,
        stopZUm = -15_000.0,
    )

    /** A drawing with a line, a circle, and a point on two layers. */
    val drawing: Drawing = Drawing(
        id = "drawing-1",
        workspaceId = "ws",
        layers = listOf(DrawingLayer(0, "Outline"), DrawingLayer(1, "Holes")),
        shapes = listOf(
            DrawingShape(type = ShapeType.Line, layerIndex = 0, x1 = -40_000_000, y1 = -25_000_000, x2 = 40_000_000, y2 = -25_000_000),
            DrawingShape(type = ShapeType.Line, layerIndex = 0, x1 = 40_000_000, y1 = -25_000_000, x2 = 40_000_000, y2 = 25_000_000),
            DrawingShape(type = ShapeType.Circle, layerIndex = 1, style = 2, x1 = 20_000_000, y1 = 10_000_000, radius = 6_000_000),
            DrawingShape(type = ShapeType.Arc, layerIndex = 0, style = 4, x1 = -20_000_000, y1 = 0, radius = 15_000_000, startAngle = 0.0, sweepAngle = 3.0),
            DrawingShape(type = ShapeType.Point, layerIndex = 1, x1 = 0, y1 = 0, name = "P1"),
        ),
    )

    /** The mill workspace with three points and two guides. */
    fun millWorkspace(): Workspace = mill.workspaces.first().copy(
        subDatums = listOf(
            SubDatum(id = "p1", name = "Point 1", x = 10_000.0, y = 5_000.0),
            SubDatum(id = "p2", name = "Point 2", x = -15_000.0, y = 12_000.0),
            SubDatum(id = "p3", name = "Point 3", x = 25_000.0, y = -8_000.0, z = -2_000.0),
        ),
        guides = listOf(Guide(id = "g1", axis = AxisId.X, valueUm = 30_000.0), Guide(id = "g2", axis = AxisId.Y, valueUm = -20_000.0)),
        activeSubDatumId = "p1",
        toolXUm = 10_000.0,
    )

    /** Gives the frame of [profile] with [workspace] and the sums `R` of the axes. */
    fun frame(
        profile: MachineProfile,
        workspace: Workspace = profile.workspaces.first(),
        x: Double? = 0.0,
        y: Double? = 0.0,
        z: Double? = 0.0,
        angular: Double? = null,
    ): ReadoutFrame {
        val complete = workspace.copy(axisStates = AxisId.entries.associateWith { axis -> workspace.axisStates[axis] ?: AxisState.forAxis(axis, profile.machineType) })
        return ReadoutFrame(
            profile = profile.copy(workspaces = listOf(complete), activeWorkspaceId = complete.id),
            workspace = complete,
            inputs = mapOf(AxisId.X to x, AxisId.Y to y, AxisId.Z to z, AxisId.Angular to angular),
        )
    }
}
