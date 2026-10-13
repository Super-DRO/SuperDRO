package org.superdro.core.ui.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Instant
import org.superdro.core.camera.CalibrationResult
import org.superdro.core.camera.ImageCalibrationMath
import org.superdro.core.camera.ImagePoint
import org.superdro.core.camera.MachinePointUm
import org.superdro.core.camera.VideoFit
import org.superdro.core.model.CameraCalibration
import org.superdro.core.readout.UnitConverter

/** The two methods of the image calibration. */
enum class CalibrationMethod {
    /** Two touches on the image with a distance between them that the user types. */
    KnownDistance,

    /** One feature, touched before and after a machine move that the readout shows. */
    MachineMove,
}

/** The step of a calibration. */
enum class CalibrationStep {
    /** The user touches the first point. */
    FirstPoint,

    /** The user touches the second point of [CalibrationMethod.KnownDistance]. */
    SecondPoint,

    /** The user types the distance of [CalibrationMethod.KnownDistance]. */
    Distance,

    /** The user moves the machine and touches the same feature again ([CalibrationMethod.MachineMove]). */
    MoveAndTouch,
}

/**
 * One calibration in progress. The image points are in image pixels (X to the right, Y down).
 *
 * @property firstMachine The machine position (µm, the sum `R` of X and Y) at the first touch.
 */
@Immutable
data class CalibrationSession(
    val method: CalibrationMethod,
    val firstImage: ImagePoint? = null,
    val firstMachine: MachinePointUm? = null,
    val secondImage: ImagePoint? = null,
) {
    /** The current step. */
    val step: CalibrationStep
        get() = when {
            firstImage == null -> CalibrationStep.FirstPoint
            method == CalibrationMethod.MachineMove -> CalibrationStep.MoveAndTouch
            secondImage == null -> CalibrationStep.SecondPoint
            else -> CalibrationStep.Distance
        }

    /** The name of the instruction text of the step in `strings_camera.xml`. */
    val instructionKey: String
        get() = when (step) {
            CalibrationStep.FirstPoint -> "camera_calibration_first_point"
            CalibrationStep.SecondPoint -> "camera_calibration_second_point"
            CalibrationStep.Distance -> "camera_calibration_distance"
            CalibrationStep.MoveAndTouch -> "camera_calibration_move"
        }
}

/** The result of one action of a calibration. */
sealed interface CalibrationOutcome {
    /** The calibration continues with [session]. */
    data class Next(val session: CalibrationSession) : CalibrationOutcome

    /** The calibration gives [calibration]. Store it. */
    data class Done(val calibration: CameraCalibration) : CalibrationOutcome

    /**
     * The calibration did not give a scale. [messageKey] names the text of the problem. The
     * calibration goes on with [session].
     */
    data class Problem(val messageKey: String, val session: CalibrationSession) : CalibrationOutcome
}

/**
 * The image calibration of the camera screen. The math is in
 * [ImageCalibrationMath]. This delegate keeps the steps and converts the touches and the typed
 * distance.
 */
object CameraCalibrationFlow {

    /** The text of two points that are too near. */
    const val POINTS_TOO_CLOSE_KEY: String = "camera_calibration_points_too_close"

    /** The text of a distance that is not more than 0. */
    const val DISTANCE_TOO_SMALL_KEY: String = "camera_calibration_distance_too_small"

    /** The text of a calibration with no video. */
    const val NEEDS_VIDEO_KEY: String = "camera_ui_needs_video"

    /**
     * Gives the image point of a touch at ([viewX], [viewY]) in a view of [viewWidth] ×
     * [viewHeight] px. The video fills the view and the view center is the image center
     * ([VideoFit.scale] with fill).
     */
    fun imagePoint(viewX: Double, viewY: Double, viewWidth: Double, viewHeight: Double, videoWidth: Int, videoHeight: Int): ImagePoint {
        val scale = VideoFit.scale(viewWidth, viewHeight, videoWidth, videoHeight, fill = true)
        return ImagePoint(
            x = (viewX - viewWidth / 2.0) / scale + videoWidth / 2.0,
            y = (viewY - viewHeight / 2.0) / scale + videoHeight / 2.0,
        )
    }

    /** Gives the view position of the image point [point]: the reverse of [imagePoint]. */
    fun viewPoint(point: ImagePoint, viewWidth: Double, viewHeight: Double, videoWidth: Int, videoHeight: Int): Pair<Double, Double> {
        val scale = VideoFit.scale(viewWidth, viewHeight, videoWidth, videoHeight, fill = true)
        return ((point.x - videoWidth / 2.0) * scale + viewWidth / 2.0) to ((point.y - videoHeight / 2.0) * scale + viewHeight / 2.0)
    }

    /**
     * A touch on the image at [point]. [machine] is the machine position of X and Y at the touch
     * (µm), or null when the readout has no position.
     */
    fun onTouch(
        session: CalibrationSession,
        point: ImagePoint,
        machine: MachinePointUm?,
        imageWidth: Int,
        imageHeight: Int,
        now: Instant,
    ): CalibrationOutcome = when (session.step) {
        CalibrationStep.FirstPoint -> CalibrationOutcome.Next(session.copy(firstImage = point, firstMachine = machine))
        CalibrationStep.SecondPoint -> CalibrationOutcome.Next(session.copy(secondImage = point))
        CalibrationStep.Distance -> CalibrationOutcome.Next(session)
        CalibrationStep.MoveAndTouch -> {
            val first = session.firstImage
            val firstMachine = session.firstMachine
            if (first == null || firstMachine == null || machine == null) {
                CalibrationOutcome.Problem(NEEDS_VIDEO_KEY, CalibrationSession(session.method))
            } else {
                outcome(
                    ImageCalibrationMath.fromMachineMove(first, point, firstMachine, machine, imageWidth, imageHeight, now),
                    session,
                    restart = CalibrationSession(session.method),
                )
            }
        }
    }

    /**
     * The typed distance of [CalibrationMethod.KnownDistance] in the axis unit ([metric]: mm, else
     * inch). A comma or a point is the decimal mark.
     */
    fun onDistance(session: CalibrationSession, text: String, metric: Boolean, imageWidth: Int, imageHeight: Int, now: Instant): CalibrationOutcome {
        val first = session.firstImage
        val second = session.secondImage
        if (session.step != CalibrationStep.Distance || first == null || second == null) return CalibrationOutcome.Next(session)
        val value = parseDistance(text)
        val distanceUm = value?.let { display -> UnitConverter.linear(metric).toInternal(display) } ?: 0.0
        return outcome(
            ImageCalibrationMath.fromKnownDistance(first, second, distanceUm, imageWidth, imageHeight, now),
            session,
            restart = CalibrationSession(session.method),
        )
    }

    /** Reads a typed distance with a point or a comma as the decimal mark, or null. */
    fun parseDistance(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { value -> value.isFinite() }

    private fun outcome(result: CalibrationResult, session: CalibrationSession, restart: CalibrationSession): CalibrationOutcome = when (result) {
        is CalibrationResult.Ok -> CalibrationOutcome.Done(result.calibration)
        CalibrationResult.PointsTooClose -> CalibrationOutcome.Problem(POINTS_TOO_CLOSE_KEY, restart)
        CalibrationResult.DistanceTooSmall ->
            if (session.method == CalibrationMethod.KnownDistance) {
                CalibrationOutcome.Problem(DISTANCE_TOO_SMALL_KEY, session)
            } else {
                CalibrationOutcome.Problem(DISTANCE_TOO_SMALL_KEY, restart)
            }
        CalibrationResult.NoImageSize -> CalibrationOutcome.Problem(NEEDS_VIDEO_KEY, restart)
    }
}
