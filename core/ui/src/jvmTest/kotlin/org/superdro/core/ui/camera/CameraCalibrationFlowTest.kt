package org.superdro.core.ui.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant
import org.superdro.core.camera.ImagePoint
import org.superdro.core.camera.MachinePointUm

/** The steps of the image calibration of the camera screen. */
class CameraCalibrationFlowTest {

    private val now = Instant.parse("2026-10-12T08:00:00Z")

    @Test
    fun aTouchGivesTheImagePointOfTheFilledVideo() {
        // A view of 1000 × 500 px with a 1280 × 720 video: the fill scale is 1000 / 1280 = 0.78125.
        assertEquals(ImagePoint(640.0, 360.0), CameraCalibrationFlow.imagePoint(500.0, 250.0, 1000.0, 500.0, 1280, 720))
        assertEquals(ImagePoint(740.0, 360.0), CameraCalibrationFlow.imagePoint(578.125, 250.0, 1000.0, 500.0, 1280, 720))
        assertEquals(578.125 to 250.0, CameraCalibrationFlow.viewPoint(ImagePoint(740.0, 360.0), 1000.0, 500.0, 1280, 720))
    }

    @Test
    fun twoTouchesAndAKnownDistanceGiveTheScale() {
        var session = CalibrationSession(CalibrationMethod.KnownDistance)
        assertEquals("camera_calibration_first_point", session.instructionKey)
        session = (CameraCalibrationFlow.onTouch(session, ImagePoint(100.0, 100.0), null, 1280, 720, now) as CalibrationOutcome.Next).session
        assertEquals(CalibrationStep.SecondPoint, session.step)
        session = (CameraCalibrationFlow.onTouch(session, ImagePoint(300.0, 100.0), null, 1280, 720, now) as CalibrationOutcome.Next).session
        assertEquals(CalibrationStep.Distance, session.step)
        // 200 px for 10 mm: 20 pixels for each mm.
        val metric = CameraCalibrationFlow.onDistance(session, "10", metric = true, 1280, 720, now)
        assertEquals(20.0, assertIs<CalibrationOutcome.Done>(metric).calibration.pixelsPerMm, 1e-9)
        assertEquals(1280 to 720, metric.calibration.imageWidth to metric.calibration.imageHeight)
        // 0,5 in is 12.7 mm: 200 / 12.7 pixels for each mm.
        val inch = CameraCalibrationFlow.onDistance(session, "0,5", metric = false, 1280, 720, now)
        assertEquals(200.0 / 12.7, assertIs<CalibrationOutcome.Done>(inch).calibration.pixelsPerMm, 1e-9)
    }

    @Test
    fun theProblemsGiveTheirTexts() {
        val near = CalibrationSession(CalibrationMethod.KnownDistance, ImagePoint(100.0, 100.0), null, ImagePoint(110.0, 100.0))
        val tooClose = assertIs<CalibrationOutcome.Problem>(CameraCalibrationFlow.onDistance(near, "10", true, 1280, 720, now))
        assertEquals("camera_calibration_points_too_close", tooClose.messageKey)
        assertEquals(CalibrationStep.FirstPoint, tooClose.session.step, "Too near points start the method again")

        val far = near.copy(secondImage = ImagePoint(300.0, 100.0))
        val zero = assertIs<CalibrationOutcome.Problem>(CameraCalibrationFlow.onDistance(far, "0", true, 1280, 720, now))
        assertEquals("camera_calibration_distance_too_small", zero.messageKey)
        assertEquals(CalibrationStep.Distance, zero.session.step, "The user types the distance again")
        val text = assertIs<CalibrationOutcome.Problem>(CameraCalibrationFlow.onDistance(far, "abc", true, 1280, 720, now))
        assertEquals("camera_calibration_distance_too_small", text.messageKey)
        assertNull(CameraCalibrationFlow.parseDistance(""))
    }

    @Test
    fun aMachineMoveGivesTheScaleAndTheRotation() {
        var session = CalibrationSession(CalibrationMethod.MachineMove)
        session = (CameraCalibrationFlow.onTouch(session, ImagePoint(640.0, 360.0), MachinePointUm(0.0, 0.0), 1280, 720, now) as CalibrationOutcome.Next).session
        assertEquals(CalibrationStep.MoveAndTouch, session.step)
        assertEquals("camera_calibration_move", session.instructionKey)
        // The feature moves 200 px to the right for a machine move of 10 mm in X.
        val done = assertIs<CalibrationOutcome.Done>(
            CameraCalibrationFlow.onTouch(session, ImagePoint(840.0, 360.0), MachinePointUm(10_000.0, 0.0), 1280, 720, now),
        )
        assertEquals(20.0, done.calibration.pixelsPerMm, 1e-9)
        assertEquals(0.0, done.calibration.rotationArcSec, 1e-6)
        // A machine move with no image move is too near.
        val still = assertIs<CalibrationOutcome.Problem>(
            CameraCalibrationFlow.onTouch(session, ImagePoint(645.0, 360.0), MachinePointUm(10_000.0, 0.0), 1280, 720, now),
        )
        assertEquals("camera_calibration_points_too_close", still.messageKey)
        // No machine move.
        val noMove = assertIs<CalibrationOutcome.Problem>(
            CameraCalibrationFlow.onTouch(session, ImagePoint(840.0, 360.0), MachinePointUm(0.0, 0.0), 1280, 720, now),
        )
        assertEquals("camera_calibration_distance_too_small", noMove.messageKey)
    }
}
