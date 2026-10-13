package org.superdro.core.camera

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.time.Instant
import org.superdro.core.model.CameraCalibration

/** A point in the video image, in image pixels. X goes to the right, Y goes down. */
data class ImagePoint(val x: Double, val y: Double)

/** A machine position in the plane of the camera, in µm. */
data class MachinePointUm(val x: Double, val y: Double)

/** The result of an image calibration. */
sealed interface CalibrationResult {
    data class Ok(val calibration: CameraCalibration) : CalibrationResult

    /** The two image points are less than [ImageCalibrationMath.MIN_PIXEL_DISTANCE] apart. */
    data object PointsTooClose : CalibrationResult

    /** The known distance or the machine move is not more than 0. */
    data object DistanceTooSmall : CalibrationResult

    /** The image size is not known. */
    data object NoImageSize : CalibrationResult
}

/**
 * The image calibration in pixels for each mm. The scale
 * comes from two known points, so that the crosshair ticks measure the image.
 *
 * Two methods:
 *
 * - [fromKnownDistance]: the user touches two points of the image with a known distance between
 *   them, for example two lines of a scale, and types the distance.
 * - [fromMachineMove]: the user puts a feature under the crosshairs, touches it, moves the machine
 *   by a distance that the readout shows, and touches the same feature again. This method also
 *   gives the rotation of the image.
 */
object ImageCalibrationMath {

    /** The minimum distance between the two image points. */
    const val MIN_PIXEL_DISTANCE: Double = 20.0

    /** Arc seconds in one degree. */
    const val ARC_SEC_PER_DEGREE: Double = 3600.0

    /**
     * Gives the scale of two image points [first] and [second] that are [distanceUm] apart on the
     * work. The image has the size [imageWidth] × [imageHeight].
     */
    fun fromKnownDistance(
        first: ImagePoint,
        second: ImagePoint,
        distanceUm: Double,
        imageWidth: Int,
        imageHeight: Int,
        now: Instant? = null,
    ): CalibrationResult {
        if (imageWidth <= 0 || imageHeight <= 0) return CalibrationResult.NoImageSize
        if (!(distanceUm > 0.0)) return CalibrationResult.DistanceTooSmall
        val pixels = hypot(second.x - first.x, second.y - first.y)
        if (pixels < MIN_PIXEL_DISTANCE) return CalibrationResult.PointsTooClose
        return CalibrationResult.Ok(
            CameraCalibration(
                pixelsPerMm = pixels / (distanceUm / 1000.0),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                rotationArcSec = 0.0,
                calibratedOn = now,
            )
        )
    }

    /**
     * Gives the scale and the rotation from a machine move. [firstImage] and [secondImage] are the
     * image points of one feature at the machine positions [firstMachine] and [secondMachine].
     *
     * The rotation is the angle of the image move (with Y up) minus the angle of the machine
     * move, in arc seconds in the range (−648 000, 648 000].
     */
    fun fromMachineMove(
        firstImage: ImagePoint,
        secondImage: ImagePoint,
        firstMachine: MachinePointUm,
        secondMachine: MachinePointUm,
        imageWidth: Int,
        imageHeight: Int,
        now: Instant? = null,
    ): CalibrationResult {
        if (imageWidth <= 0 || imageHeight <= 0) return CalibrationResult.NoImageSize
        val moveX = secondMachine.x - firstMachine.x
        val moveY = secondMachine.y - firstMachine.y
        val moveUm = hypot(moveX, moveY)
        if (!(moveUm > 0.0)) return CalibrationResult.DistanceTooSmall
        val imageX = secondImage.x - firstImage.x
        val imageUpY = -(secondImage.y - firstImage.y)
        val pixels = hypot(imageX, imageUpY)
        if (pixels < MIN_PIXEL_DISTANCE) return CalibrationResult.PointsTooClose
        val radians = atan2(imageUpY, imageX) - atan2(moveY, moveX)
        return CalibrationResult.Ok(
            CameraCalibration(
                pixelsPerMm = pixels / (moveUm / 1000.0),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                rotationArcSec = normalizeArcSec(radians * 180.0 / PI * ARC_SEC_PER_DEGREE),
                calibratedOn = now,
            )
        )
    }

    /**
     * Gives the scale of [calibration] for an image of [width] × [height]: the scale times the
     * width ratio. A different aspect ratio (more than 0.5 % difference) gives null: the sensor can
     * cut a different part of the picture, so the mode needs its own calibration.
     */
    fun pixelsPerMmFor(calibration: CameraCalibration?, width: Int, height: Int): Double? {
        if (calibration == null || calibration.pixelsPerMm <= 0.0) return null
        if (width <= 0 || height <= 0 || calibration.imageWidth <= 0 || calibration.imageHeight <= 0) return null
        val oldAspect = calibration.imageWidth.toDouble() / calibration.imageHeight
        val newAspect = width.toDouble() / height
        if (kotlin.math.abs(newAspect / oldAspect - 1.0) > ASPECT_TOLERANCE) return null
        return calibration.pixelsPerMm * width.toDouble() / calibration.imageWidth.toDouble()
    }

    /**
     * Gives the screen pixels for each mm when the video shows with [videoScale] screen pixels
     * for each image pixel ([VideoFit.scale]).
     */
    fun screenPixelsPerMm(imagePixelsPerMm: Double, videoScale: Double): Double = imagePixelsPerMm * videoScale

    /**
     * Gives the machine offset in µm of an image offset ([dxPx], [dyPx] from the image center,
     * Y down) with the scale [pixelsPerMm] and the rotation [rotationArcSec].
     */
    fun imageOffsetToMachineUm(dxPx: Double, dyPx: Double, pixelsPerMm: Double, rotationArcSec: Double = 0.0): MachinePointUm {
        val scaleUm = 1000.0 / pixelsPerMm
        val upX = dxPx * scaleUm
        val upY = -dyPx * scaleUm
        val radians = -rotationArcSec / ARC_SEC_PER_DEGREE * PI / 180.0
        return MachinePointUm(
            x = upX * cos(radians) - upY * sin(radians),
            y = upX * sin(radians) + upY * cos(radians),
        )
    }

    /** The aspect ratio tolerance of [pixelsPerMmFor]: 0.5 %. */
    const val ASPECT_TOLERANCE: Double = 0.005

    private const val HALF_TURN_ARC_SEC: Double = 648_000.0

    private fun normalizeArcSec(value: Double): Double {
        var result = value
        while (result > HALF_TURN_ARC_SEC) result -= 2 * HALF_TURN_ARC_SEC
        while (result <= -HALF_TURN_ARC_SEC) result += 2 * HALF_TURN_ARC_SEC
        return result
    }
}
