package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.model.CameraCalibration

class ImageCalibrationTest {

    private fun ok(result: CalibrationResult): CameraCalibration {
        assertTrue(result is CalibrationResult.Ok, "result $result")
        return result.calibration
    }

    @Test
    fun twoPointsWithAKnownDistanceGiveThePixelsForEachMm() {
        // 300 px across and 400 px down: 500 px for 10 mm.
        val now = Instant.fromEpochMilliseconds(5_000L)
        val calibration = ok(
            ImageCalibrationMath.fromKnownDistance(ImagePoint(100.0, 200.0), ImagePoint(400.0, 600.0), 10_000.0, 1280, 720, now)
        )
        assertEquals(CameraCalibration(50.0, 1280, 720, 0.0, now), calibration)
    }

    @Test
    fun badInputGivesNoCalibration() {
        assertEquals(
            CalibrationResult.PointsTooClose,
            ImageCalibrationMath.fromKnownDistance(ImagePoint(0.0, 0.0), ImagePoint(19.0, 0.0), 1_000.0, 640, 480),
        )
        assertEquals(
            CalibrationResult.DistanceTooSmall,
            ImageCalibrationMath.fromKnownDistance(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 0.0, 640, 480),
        )
        assertEquals(
            CalibrationResult.NoImageSize,
            ImageCalibrationMath.fromKnownDistance(ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), 1_000.0, 0, 480),
        )
        assertEquals(
            CalibrationResult.DistanceTooSmall,
            ImageCalibrationMath.fromMachineMove(
                ImagePoint(0.0, 0.0), ImagePoint(100.0, 0.0), MachinePointUm(5.0, 5.0), MachinePointUm(5.0, 5.0), 640, 480,
            ),
        )
    }

    @Test
    fun aMachineMoveGivesTheScaleAndTheRotation() {
        // The machine moves 2 mm in X. The feature moves 100 px to the right in the image.
        val straight = ok(
            ImageCalibrationMath.fromMachineMove(
                ImagePoint(320.0, 240.0), ImagePoint(420.0, 240.0), MachinePointUm(0.0, 0.0), MachinePointUm(2_000.0, 0.0), 640, 480,
            )
        )
        assertEquals(50.0, straight.pixelsPerMm, 1e-9)
        assertEquals(0.0, straight.rotationArcSec, 1e-6)
        // The machine moves 2 mm in Y. The feature moves 100 px to the right: the image turns −90°.
        val turned = ok(
            ImageCalibrationMath.fromMachineMove(
                ImagePoint(320.0, 240.0), ImagePoint(420.0, 240.0), MachinePointUm(0.0, 0.0), MachinePointUm(0.0, 2_000.0), 640, 480,
            )
        )
        assertEquals(-324_000.0, turned.rotationArcSec, 1e-6)
        // The feature moves up when the machine moves in +X and the image is turned +90°.
        val up = ok(
            ImageCalibrationMath.fromMachineMove(
                ImagePoint(320.0, 240.0), ImagePoint(320.0, 140.0), MachinePointUm(1_000.0, 0.0), MachinePointUm(3_000.0, 0.0), 640, 480,
            )
        )
        assertEquals(324_000.0, up.rotationArcSec, 1e-6)
        // A half turn is +648000″, not −648000″.
        val back = ok(
            ImageCalibrationMath.fromMachineMove(
                ImagePoint(420.0, 240.0), ImagePoint(320.0, 240.0), MachinePointUm(0.0, 0.0), MachinePointUm(2_000.0, 0.0), 640, 480,
            )
        )
        assertEquals(648_000.0, back.rotationArcSec, 1e-6)
    }

    @Test
    fun aModeWithTheSameAspectUsesTheWidthRatio() {
        val calibration = CameraCalibration(50.0, 1280, 720)
        assertEquals(75.0, ImageCalibrationMath.pixelsPerMmFor(calibration, 1920, 1080)!!, 1e-9)
        assertEquals(25.0, ImageCalibrationMath.pixelsPerMmFor(calibration, 640, 360)!!, 1e-9)
        assertNull(ImageCalibrationMath.pixelsPerMmFor(calibration, 640, 480))
        assertNull(ImageCalibrationMath.pixelsPerMmFor(null, 1280, 720))
        assertNull(ImageCalibrationMath.pixelsPerMmFor(CameraCalibration(0.0, 1280, 720), 1280, 720))
    }

    @Test
    fun anImageOffsetGivesAMachineOffset() {
        assertEquals(30.0, ImageCalibrationMath.screenPixelsPerMm(50.0, 0.6), 1e-9)
        val plain = ImageCalibrationMath.imageOffsetToMachineUm(100.0, -50.0, 50.0)
        assertEquals(2_000.0, plain.x, 1e-9)
        assertEquals(1_000.0, plain.y, 1e-9)
        // The image of the second case above: 100 px to the right is +2 mm in Y.
        val turned = ImageCalibrationMath.imageOffsetToMachineUm(100.0, 0.0, 50.0, rotationArcSec = -324_000.0)
        assertEquals(0.0, turned.x, 1e-9)
        assertEquals(2_000.0, turned.y, 1e-9)
    }
}
