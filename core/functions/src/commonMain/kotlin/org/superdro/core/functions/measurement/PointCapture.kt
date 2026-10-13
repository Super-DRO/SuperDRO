package org.superdro.core.functions.measurement

import org.superdro.core.functions.PointUm
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.Plane
import org.superdro.core.model.PointSource
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SubDatum

/** The reason why the app refuses a probe point. */
enum class ProbeRefusal {
    /** "The position at the trigger is unknown. Touch off again." */
    PositionUnknown,

    /** "No axis was moving when the probe triggered. Feed into the surface and touch off again." */
    NoApproach,
}

/** The result of a probe trigger in a measurement function. */
sealed interface ProbeCapture {

    /** The trigger gives [point]. */
    data class Collected(val point: CollectedPoint) : ProbeCapture

    /** The app refuses the trigger for [reason]. */
    data class Refused(val reason: ProbeRefusal) : ProbeCapture
}

/**
 * Makes the slot points of the four point sources.
 * All positions are µm in the plane of the graphical view.
 */
object PointCapture {

    /**
     * Gives the tip radius of the instrument [profile] in µm, or null.
     *
     * Only a touch probe and a 2D edge finder have a tip radius. The radius is the effective
     * diameter / 2. The effective diameter is the calibrated value
     * ([ProbeProfile.effectiveDiameterUm]) when it is more than 0, else the stylus diameter. A
     * diameter of 0 gives no tip radius.
     */
    fun tipRadiusUm(profile: ProbeProfile?): Double? {
        if (profile == null) return null
        if (profile.type != ProbeType.TouchProbe && profile.type != ProbeType.EdgeFinder2D) return null
        val calibrated = profile.effectiveDiameterUm
        val diameter = if (calibrated != null && calibrated > 0.0) calibrated else profile.stylusDiameterUm
        return if (diameter > 0.0) diameter / 2.0 else null
    }

    /**
     * Gives the point of a tap on the armed slot (source `LATCH`). The
     * positions include the instrument offsets. The point has no tip radius and no approach. If X
     * or Y is not known (NaN), the result is null and nothing occurs.
     */
    fun latch(
        xUm: Double,
        yUm: Double,
        zUm: Double? = null,
        instrumentId: String? = null,
        instrumentXOffsetUm: Double = 0.0,
        instrumentYOffsetUm: Double = 0.0,
    ): CollectedPoint? {
        if (!xUm.isFinite() || !yUm.isFinite()) return null
        return CollectedPoint(
            x = xUm,
            y = yUm,
            z = zUm?.takeIf { value -> value.isFinite() },
            source = PointSource.Latch,
            instrumentId = instrumentId,
            instrumentXOffsetUm = instrumentXOffsetUm,
            instrumentYOffsetUm = instrumentYOffsetUm,
        )
    }

    /**
     * Gives the point of a tap on the armed slot with a camera instrument (source `CAMERA`).
     * The positions include the camera offsets. The point has no tip radius and no approach. If X
     * or Y is not known (NaN), the result is null.
     */
    fun camera(
        xUm: Double,
        yUm: Double,
        zUm: Double? = null,
        instrumentId: String? = null,
        instrumentXOffsetUm: Double = 0.0,
        instrumentYOffsetUm: Double = 0.0,
    ): CollectedPoint? =
        latch(xUm, yUm, zUm, instrumentId, instrumentXOffsetUm, instrumentYOffsetUm)
            ?.copy(source = PointSource.Camera)

    /**
     * Gives the point of a probe trigger (source `PROBE`).
     *
     * The positions are the positions at the trigger time stamp, with the instrument offsets. The
     * approach signs come from [ApproachSign]. X or Y NaN gives [ProbeRefusal.PositionUnknown].
     * Both plane signs 0 give [ProbeRefusal.NoApproach]. [tipRadiusUm] comes from [tipRadiusUm]
     * of the instrument profile. A tip radius of 0 or less gives no tip radius.
     */
    fun probe(
        xUm: Double,
        yUm: Double,
        zUm: Double?,
        approachX: Int,
        approachY: Int,
        approachZ: Int?,
        tipRadiusUm: Double?,
        instrumentId: String? = null,
        instrumentXOffsetUm: Double = 0.0,
        instrumentYOffsetUm: Double = 0.0,
    ): ProbeCapture {
        if (!xUm.isFinite() || !yUm.isFinite()) return ProbeCapture.Refused(ProbeRefusal.PositionUnknown)
        if (approachX == 0 && approachY == 0) return ProbeCapture.Refused(ProbeRefusal.NoApproach)
        return ProbeCapture.Collected(
            CollectedPoint(
                x = xUm,
                y = yUm,
                z = zUm?.takeIf { value -> value.isFinite() },
                source = PointSource.Probe,
                tipRadiusUm = tipRadiusUm?.takeIf { radius -> radius > 0.0 },
                approachX = approachX.coerceIn(-1, 1),
                approachY = approachY.coerceIn(-1, 1),
                approachZ = approachZ?.coerceIn(-1, 1),
                instrumentId = instrumentId,
                instrumentXOffsetUm = instrumentXOffsetUm,
                instrumentYOffsetUm = instrumentYOffsetUm,
            ),
        )
    }

    /**
     * Gives the point of a tap on a sub-datum (source `SUB_DATUM`). The
     * point has the sub-datum id, no Z, and no instrument frame. [planeX] and [planeY] are the
     * position of the sub-datum in the plane (see [planeCoordinates]).
     */
    fun subDatum(subDatumId: String, planeX: Double, planeY: Double): CollectedPoint =
        CollectedPoint(x = planeX, y = planeY, source = PointSource.SubDatum, sourceId = subDatumId)

    /**
     * Gives the position of [subDatum] in [plane], or null when the sub-datum does not have the
     * two coordinates. This is the inverse of the map of the add actions
     * ([MeasurementAddActions.subDatum]): XY (X, Y); XZ (X, Z); YZ (Y, Z); ZX (Z, X).
     */
    fun planeCoordinates(subDatum: SubDatum, plane: Plane): PointUm? {
        val (h, v) = when (plane) {
            Plane.XY -> subDatum.x to subDatum.y
            Plane.XZ -> subDatum.x to subDatum.z
            Plane.YZ -> subDatum.y to subDatum.z
            Plane.ZX -> subDatum.z to subDatum.x
        }
        if (h == null || v == null) return null
        return PointUm(h, v)
    }
}
