package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The test result of one camera mode. */
@Serializable
enum class CameraModeStatus { Good, Bad }

/**
 * The test result of one mode of one camera model. All machine profiles share it.
 *
 * @property vidPid The key `%04x:%04x` of the vendor ID and the product ID.
 * @property modeKey The key of the UVC mode.
 */
@Serializable
data class CameraModeResult(
    val vidPid: String = "",
    val modeKey: String = "",
    val status: CameraModeStatus = CameraModeStatus.Good,
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val measuredFps: Double? = null,
    val updatedOn: Instant = Clock.System.now(),
)

/**
 * The camera and the mode that one machine profile uses.
 *
 * @property calibration The image scale of this camera on this machine, or null.
 */
@Serializable
data class CameraSelection(
    val profileId: String = "",
    val cameraKey: String = "",
    val modeKey: String = "",
    val updatedOn: Instant = Clock.System.now(),
    val calibration: CameraCalibration? = null,
)

/**
 * The image scale of a camera: the pixels of the video image for each mm on the
 * work, from two known points. The scale is for the image size [imageWidth] × [imageHeight]. A
 * mode with a different size and the same aspect ratio uses the scale times the width ratio.
 *
 * @property rotationArcSec The angle from the machine X axis to the image X axis, in arc seconds.
 *   0 when the calibration had no machine move.
 */
@Serializable
data class CameraCalibration(
    val pixelsPerMm: Double = 0.0,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val rotationArcSec: Double = 0.0,
    val calibratedOn: Instant? = null,
)
