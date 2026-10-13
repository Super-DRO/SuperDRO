package org.superdro.core.camera

import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.PointSource
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType

/**
 * The point under the crosshairs of a camera probe profile. All lengths are µm.
 *
 * The X and Y offsets of a [ProbeType.Camera] profile are the distance from the spindle center to
 * the crosshairs. The point is the spindle position plus the offset of the profile. When the
 * readout has the instrument offset already (measurement mode with this instrument, holder `H`),
 * that offset comes off, so that the offset does not add two times:
 *
 * ```
 * X = (profile X offset − applied offset X) + X position
 * Y = (profile Y offset − applied offset Y) + Y position
 * ```
 *
 * The camera does not read the video. The user moves the machine until the feature is under the
 * crosshairs, then uses the point.
 */
object CameraProbePoint {

    /** The camera type has no probe signal, no diameter, and no Z offset; it has X and Y offsets. */
    fun isCamera(profile: ProbeProfile?): Boolean = profile?.type == ProbeType.Camera

    /**
     * Gives the point under the crosshairs, or null when [profile] is not a camera profile.
     *
     * @param xUm The X position of the readout, or null when the X axis is off.
     * @param yUm The Y position of the readout, or null when the Y axis is off.
     * @param appliedOffsetXUm The instrument offset that the X readout has already, or null.
     * @param appliedOffsetYUm The instrument offset that the Y readout has already, or null.
     */
    fun underCrosshairs(
        profile: ProbeProfile?,
        xUm: Double?,
        yUm: Double?,
        appliedOffsetXUm: Double? = null,
        appliedOffsetYUm: Double? = null,
    ): CrosshairPoint? {
        if (profile == null || !isCamera(profile)) return null
        return CrosshairPoint(
            xUm = xUm?.let { position -> profile.xOffsetUm - (appliedOffsetXUm ?: 0.0) + position },
            yUm = yUm?.let { position -> profile.yOffsetUm - (appliedOffsetYUm ?: 0.0) + position },
        )
    }

    /**
     * True when the edge finder can use the point: a camera profile and an app that is connected
     * to the adapter.
     */
    fun canUse(profile: ProbeProfile?, connected: Boolean): Boolean = connected && isCamera(profile)

    /**
     * Gives the slot point of a camera instrument for the measurement: the point source
     * [PointSource.Camera], the point under the crosshairs, no tip radius, and no approach signs.
     * The camera point has no tip compensation. Null when [profile] is not a camera profile.
     */
    fun measurementPoint(
        profile: ProbeProfile?,
        xUm: Double,
        yUm: Double,
        appliedOffsetXUm: Double? = null,
        appliedOffsetYUm: Double? = null,
    ): CollectedPoint? {
        val point = underCrosshairs(profile, xUm, yUm, appliedOffsetXUm, appliedOffsetYUm) ?: return null
        val camera = profile ?: return null
        return CollectedPoint(
            x = point.xUm ?: xUm,
            y = point.yUm ?: yUm,
            z = null,
            source = PointSource.Camera,
            tipRadiusUm = null,
            approachX = 0,
            approachY = 0,
            approachZ = null,
            sourceId = camera.id,
            instrumentId = camera.id,
            instrumentXOffsetUm = camera.xOffsetUm,
            instrumentYOffsetUm = camera.yOffsetUm,
        )
    }
}

/** A point under the crosshairs. A null value is an axis that is off. Lengths are µm. */
data class CrosshairPoint(val xUm: Double?, val yUm: Double?)
