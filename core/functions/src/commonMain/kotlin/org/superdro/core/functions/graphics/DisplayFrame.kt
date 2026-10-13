package org.superdro.core.functions.graphics

import kotlin.math.atan2
import kotlin.math.hypot
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineType
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Plane
import org.superdro.core.model.Workspace

/**
 * The Rotate Part display transform. It turns the drawing, the
 * reference image, and the sub-datums about a pivot for the display only. The readouts and the
 * stored data do not change.
 *
 * ```text
 * φ = −α                     typed source
 * φ = −(A − offset)          linked source, A = angular axis position
 * x1 = px + cos φ·(x − px) − sin φ·(y − py)
 * y1 = py + sin φ·(x − px) + cos φ·(y − py)
 * ```
 *
 * A positive α turns the points clockwise in the `XY` view.
 *
 * @property phiArcSec The angle φ in arc seconds.
 */
data class PartRotationTransform(val phiArcSec: Double, val pivot: Vec2) {

    private val cosSin = AngleMath.cosSinArcSec(phiArcSec)

    /** Gives the shown position of the stored position [point]. */
    fun apply(point: Vec2): Vec2 {
        val (cos, sin) = cosSin
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        return Vec2(pivot.x + cos * dx - sin * dy, pivot.y + sin * dx + cos * dy)
    }

    /** Gives the stored position of the shown position [point]. */
    fun inverse(point: Vec2): Vec2 {
        val (cos, sin) = cosSin
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        return Vec2(pivot.x + cos * dx + sin * dy, pivot.y - sin * dx + cos * dy)
    }

    /** Gives the transform as a matrix of the plane (x, y). */
    fun toMatrix(): Matrix3 {
        val (cos, sin) = cosSin
        return Matrix3.rotationAbout(cos, sin, pivot.x, pivot.y)
    }

    companion object {
        /** The limit of the typed angle α and of the angle offset, in degrees: −360 to 360. */
        const val ANGLE_LIMIT_DEGREES: Double = 360.0

        /** Gives the transform of the typed angle [alphaArcSec]: φ = −α. */
        fun typed(alphaArcSec: Double, pivot: Vec2): PartRotationTransform = PartRotationTransform(-alphaArcSec, pivot)

        /** Gives the transform of the linked source: φ = −(A − offset). */
        fun linked(angularArcSec: Double, offsetArcSec: Double, pivot: Vec2): PartRotationTransform =
            PartRotationTransform(-(angularArcSec - offsetArcSec), pivot)

        /**
         * Gives the transform of the stored state [rotation], or null when the rotation is not
         * active. A linked rotation uses [angularArcSec], the position of the angular axis. A
         * linked rotation without that position gives null.
         */
        fun of(rotation: PartRotation, angularArcSec: Double?): PartRotationTransform? {
            if (!rotation.active) return null
            val pivot = Vec2(rotation.pivotXUm, rotation.pivotYUm)
            if (!rotation.linkedToAngularEncoder) return typed(rotation.angleArcSec, pivot)
            val angular = angularArcSec?.takeIf { value -> value.isFinite() } ?: return null
            return linked(angular, rotation.encoderOffsetArcSec, pivot)
        }
    }
}

/**
 * The Align Axes transform of the measurement mode. It turns
 * the X and Y readouts so that they read along a part edge.
 *
 * ```text
 * X' =  cos(a)·X + sin(a)·Y
 * Y' = −sin(a)·X + cos(a)·Y
 * ```
 *
 * @property angleArcSec The angle a in arc seconds.
 */
data class AxisAlignmentTransform(val angleArcSec: Double) {

    private val cosSin = AngleMath.cosSinArcSec(angleArcSec)

    /** Gives the aligned position of the machine-frame position [point]. */
    fun apply(point: Vec2): Vec2 {
        val (cos, sin) = cosSin
        return Vec2(cos * point.x + sin * point.y, -sin * point.x + cos * point.y)
    }

    /** Gives the machine-frame position of the aligned position [point]. */
    fun inverse(point: Vec2): Vec2 {
        val (cos, sin) = cosSin
        return Vec2(cos * point.x - sin * point.y, sin * point.x + cos * point.y)
    }

    /** Gives the transform as a matrix of the plane (x, y). */
    fun toMatrix(): Matrix3 {
        val (cos, sin) = cosSin
        return Matrix3(cos, sin, 0.0, -sin, cos, 0.0, 0.0, 0.0, 1.0)
    }

    companion object {
        /** The limit of the typed angle, in degrees: −180 to 180. */
        const val TYPED_LIMIT_DEGREES: Double = 180.0

        /** The limit of the angle offset of the linked source, in degrees: −360 to 360. */
        const val OFFSET_LIMIT_DEGREES: Double = 360.0

        /** Two points closer than this (µm) give no direction. */
        const val SAME_POINT_LIMIT_UM: Double = 0.0001

        /**
         * True when the alignment applies: the switch is on, the machine is a mill in the
         * measurement mode, and the plane is `XY`.
         */
        fun isActive(switchOn: Boolean, machineType: MachineType, machineMode: MachineMode, plane: Plane): Boolean =
            switchOn && !machineType.isLathe && machineMode == MachineMode.Measurement && plane == Plane.XY

        /**
         * Gives the transform of the stored state [alignment], or null when it does not apply
         * (see [isActive]). The source [AxisAlignmentSource.AngularEncoder] uses [angularArcSec],
         * the position of the angular axis. Without that position, the function gives null.
         */
        fun of(
            alignment: AxisAlignment,
            angularArcSec: Double?,
            machineType: MachineType,
            machineMode: MachineMode,
            plane: Plane,
        ): AxisAlignmentTransform? {
            if (!isActive(alignment.active, machineType, machineMode, plane)) return null
            if (alignment.source != AxisAlignmentSource.AngularEncoder) return AxisAlignmentTransform(alignment.angleArcSec)
            val angular = angularArcSec?.takeIf { value -> value.isFinite() } ?: return null
            return AxisAlignmentTransform(linkedAngleArcSec(angular, alignment.encoderOffsetArcSec))
        }

        /** Gives the angle of the linked source in arc seconds: a = −(A − offset). */
        fun linkedAngleArcSec(angularArcSec: Double, offsetArcSec: Double): Double = -(angularArcSec - offsetArcSec)

        /**
         * Gives the angle of the two-point source in arc seconds: a = atan2(yB − yA, xB − xA). Gives
         * null when the points are closer than [SAME_POINT_LIMIT_UM].
         */
        fun measuredAngleArcSec(a: Vec2, b: Vec2): Double? {
            val dx = b.x - a.x
            val dy = b.y - a.y
            if (hypot(dx, dy) < SAME_POINT_LIMIT_UM) return null
            return AngleMath.degreesToArcSec(AngleMath.radToDegrees(atan2(dy, dx)))
        }
    }
}

/**
 * The display frame of the graphical view: first Rotate Part, then Align Axes when each is
 * active. The view shows stored positions through [apply]. A touch
 * point goes through [inverse] before the point hit test.
 */
data class DisplayFrame(
    val partRotation: PartRotationTransform? = null,
    val axisAlignment: AxisAlignmentTransform? = null,
) {

    /** True when no transform is active. */
    val isIdentity: Boolean get() = partRotation == null && axisAlignment == null

    /** Gives the shown position of the stored position [point]. */
    fun apply(point: Vec2): Vec2 {
        val turned = partRotation?.apply(point) ?: point
        return axisAlignment?.apply(turned) ?: turned
    }

    /** Gives the stored position of the shown position [point]. */
    fun inverse(point: Vec2): Vec2 {
        val unaligned = axisAlignment?.inverse(point) ?: point
        return partRotation?.inverse(unaligned) ?: unaligned
    }

    /** Gives the frame as a matrix of the plane (x, y), for the drawing and the reference image. */
    fun toMatrix(): Matrix3 {
        val rotation = partRotation?.toMatrix() ?: Matrix3.IDENTITY
        val alignment = axisAlignment?.toMatrix() ?: Matrix3.IDENTITY
        return alignment * rotation
    }

    companion object {
        /**
         * Gives the display frame of [workspace]: its Rotate Part state and its Align Axes state.
         * [angularArcSec] is the position of the angular axis, or null when there is none.
         */
        fun of(
            workspace: Workspace,
            angularArcSec: Double?,
            machineType: MachineType,
            machineMode: MachineMode,
        ): DisplayFrame = DisplayFrame(
            partRotation = PartRotationTransform.of(workspace.partRotation, angularArcSec),
            axisAlignment = AxisAlignmentTransform.of(workspace.axisAlignment, angularArcSec, machineType, machineMode, workspace.plane),
        )
    }
}
