package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/** Gives a new ID: UUID text. */
fun newId(): String = Uuid.random().toString()

/**
 * The display state of one axis in a workspace. Lengths are µm, angles
 * are arc seconds.
 *
 * @property metric The unit of the axis, or null for the metric default of the profile.
 * @property diameterMode True on a lathe X. [forAxis] sets it.
 */
@Serializable
data class AxisState(
    val absoluteOffset: Double = 0.0,
    val incrementalOffset: Double = 0.0,
    val relative: Boolean = false,
    val toolOffset: Double = 0.0,
    val toolOffsetActive: Boolean = false,
    val metric: Boolean? = null,
    val diameterMode: Boolean = false,
    val divisionUnits: Boolean = false,
    val divisionsPerRev: Int? = null,
    val surfaceSpeedMode: Boolean = false,
) {
    companion object {
        /** The state of a new workspace: the diameter mode is on for a lathe X only. */
        fun forAxis(axis: AxisId, machineType: MachineType): AxisState =
            AxisState(diameterMode = machineType == MachineType.Lathe && axis == AxisId.X)
    }
}

/**
 * The target of the proximity warning.
 *
 * @property absolute True when [targetUm] is an absolute position, false for the shown value.
 */
@Serializable
data class ProximityTarget(
    val axis: AxisId = AxisId.X,
    val targetUm: Double = 0.0,
    val absolute: Boolean = false,
)

/** The automatic functions of the graphical view. All are off for a new workspace. */
@Serializable
data class GraphicalSwitches(
    val snapToPoint: Boolean = false,
    val snapToGuide: Boolean = false,
    val automaticToolOffset: Boolean = false,
    val automaticProximityWarning: Boolean = false,
    val automaticZero: Boolean = false,
    val automaticSubDatum: Boolean = false,
)

/** The plane of the graphical view. A mill has `XY`, `XZ`, and `YZ`. A lathe has `ZX`. */
@Serializable
enum class Plane { XY, XZ, YZ, ZX }

/**
 * The reference image of a workspace.
 *
 * @property fileName The file `images/<uuid>.jpg`.
 * @property matrix 9 values, row by row: image pixels to machine µm.
 * @property alpha The opacity: 0, 127, or 255.
 */
@Serializable
data class ReferenceImage(
    val fileName: String = "",
    val matrix: List<Double> = IDENTITY,
    val alpha: Int = 255,
) {
    companion object {
        val IDENTITY: List<Double> = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}

/** The kind of a group of sub-datums. */
@Serializable
enum class SubDatumGroupKind { Pattern, Thread, Measured }

/**
 * A saved point in a workspace. X, Y, Z are µm and A is arc seconds. A
 * coordinate is null when the point does not have it. A stored lathe X is a radius.
 *
 * @property tags The tags of the point.
 * @property groupId The group of the point, for example one hole pattern.
 * @property groupRef The reference of the group, for example the ID of a [ThreadSpec], and the
 *   role of the point in it.
 */
@Serializable
data class SubDatum(
    val id: String = newId(),
    val name: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val a: Double? = null,
    val groupId: Long? = null,
    val groupKind: SubDatumGroupKind? = null,
    val groupRef: String? = null,
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
)

/** A line at one coordinate in the graphical view. */
@Serializable
data class Guide(
    val id: String = newId(),
    val axis: AxisId = AxisId.X,
    val valueUm: Double = 0.0,
    val createdOn: Instant = Clock.System.now(),
)

/**
 * The rotate part state: a display rotation about a pivot. The readouts and the stored
 * data do not change.
 */
@Serializable
data class PartRotation(
    val active: Boolean = false,
    val angleArcSec: Double = 0.0,
    val pivotXUm: Double = 0.0,
    val pivotYUm: Double = 0.0,
    /** True when the angular encoder gives the angle. */
    val linkedToAngularEncoder: Boolean = false,
    /**
     * The angle offset of the linked source: the angle is `−(A − offset)`, where A is the
     * angular axis position.
     */
    val encoderOffsetArcSec: Double = 0.0,
)

/** The source of the axis alignment angle. */
@Serializable
enum class AxisAlignmentSource { AngularEncoder, Typed, MeasuredLine }

/** The axis alignment state of the measurement mode. */
@Serializable
data class AxisAlignment(
    val active: Boolean = false,
    val angleArcSec: Double = 0.0,
    val source: AxisAlignmentSource = AxisAlignmentSource.Typed,
    /**
     * The angle offset of the source [AxisAlignmentSource.AngularEncoder]: the angle is
     * `−(A − offset)`, where A is the angular axis position.
     */
    val encoderOffsetArcSec: Double = 0.0,
)

/**
 * A named set of sub-datums and guides with its own axis state.
 *
 * @property toolXUm The tool value X: the diameter on a mill, the X offset on a lathe.
 * @property lastOrdinal The last number of the automatic names `Point <n>`.
 * @property armedThreadId The ID of the [ThreadSpec] that is armed, or null.
 */
@Serializable
data class Workspace(
    val id: String = newId(),
    val name: String = DEFAULT_NAME,
    val axisStates: Map<AxisId, AxisState> = AxisId.entries.associateWith { AxisState() },
    val activeSubDatumId: String? = null,
    val activeToolId: String? = null,
    val toolXUm: Double = 0.0,
    val toolZUm: Double = 0.0,
    val toothCount: Int = 0,
    val proximity: ProximityTarget? = null,
    val graphical: GraphicalSwitches = GraphicalSwitches(),
    val plane: Plane = Plane.XY,
    val referenceImage: ReferenceImage? = null,
    val lastOrdinal: Int = 0,
    val subDatums: List<SubDatum> = emptyList(),
    val guides: List<Guide> = emptyList(),
    val partRotation: PartRotation = PartRotation(),
    val axisAlignment: AxisAlignment = AxisAlignment(),
    val compoundProjection: CompoundProjection = CompoundProjection(),
    val headTilt: HeadTilt = HeadTilt(),
    val armedThreadId: String? = null,
    /** The pass record of the thread cut panel. */
    val threadPass: ThreadPass = ThreadPass(),
    val createdOn: Instant = Clock.System.now(),
    val updatedOn: Instant = Clock.System.now(),
) {
    companion object {
        /** The name of the first workspace of a profile. */
        const val DEFAULT_NAME: String = "Default Workspace"

        /** A new workspace for [machineType]: the lathe uses the plane `ZX` and the X diameter mode. */
        fun forMachine(machineType: MachineType, name: String = DEFAULT_NAME): Workspace = Workspace(
            name = name,
            axisStates = AxisId.entries.associateWith { axis -> AxisState.forAxis(axis, machineType) },
            plane = if (machineType == MachineType.Lathe) Plane.ZX else Plane.XY,
        )
    }
}
