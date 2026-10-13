package org.superdro.core.ui.camera

import org.superdro.core.camera.CameraProbePoint
import org.superdro.core.camera.CrosshairPoint
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.readout.ReadoutFrame

/**
 * The point under the crosshairs of a camera probe profile for the UI. The math is in
 * [CameraProbePoint]. The camera screen shows the point. The edge
 * finder and the measurement of the functions area can use the same functions for the button
 * "Take the point at the crosshairs" (`camera_point_under_crosshairs`).
 */
object CameraCrosshairPoint {

    /** Gives the camera profiles of [profiles] ([org.superdro.core.model.ProbeType.Camera]), in their order. */
    fun cameraProfiles(profiles: List<ProbeProfile>): List<ProbeProfile> = profiles.filter(CameraProbePoint::isCamera)

    /**
     * Gives the camera profile that the camera screen uses first: the edge finder profile of
     * [roles] when it is a camera, else the measurement instrument when it is a camera, else the
     * first camera profile, else null.
     */
    fun preferred(profiles: List<ProbeProfile>, roles: ProbeRoles): ProbeProfile? {
        val cameras = cameraProfiles(profiles)
        return cameras.firstOrNull { profile -> profile.id == roles.edgeFinderProfileId }
            ?: cameras.firstOrNull { profile -> profile.id == roles.measurementInstrumentProfileId }
            ?: cameras.firstOrNull()
    }

    /**
     * Gives the point under the crosshairs of [profile] for the absolute X and Y positions of
     * [frame], or null when [profile] is not a camera profile. An axis that is off or has no value
     * gives null for that axis. When the readout has the offset of this instrument already (the
     * measurement mode with this profile, `H`), that offset comes off.
     */
    fun of(frame: ReadoutFrame, profile: ProbeProfile?): CrosshairPoint? {
        if (profile == null || !CameraProbePoint.isCamera(profile)) return null
        val x = frame.reading(AxisId.X).takeIf { reading -> reading.enabled && reading.hasValue }?.absolute
        val y = frame.reading(AxisId.Y).takeIf { reading -> reading.enabled && reading.hasValue }?.absolute
        val applied = frame.instrumentInUse?.id == profile.id
        return CameraProbePoint.underCrosshairs(
            profile = profile,
            xUm = x,
            yUm = y,
            appliedOffsetXUm = if (applied) frame.instrumentOffset(AxisId.X) else null,
            appliedOffsetYUm = if (applied) frame.instrumentOffset(AxisId.Y) else null,
        )
    }

    /** Gives the text of [valueUm] on [axis] in the unit and the number format of the readout of [frame]. */
    fun text(frame: ReadoutFrame, axis: AxisId, valueUm: Double): String =
        frame.pattern(axis).format(frame.converter(axis).toDisplay(valueUm))
}
