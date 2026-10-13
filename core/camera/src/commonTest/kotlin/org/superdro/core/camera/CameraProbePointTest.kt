package org.superdro.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.PointSource
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType

class CameraProbePointTest {

    private val camera = ProbeProfile(
        id = "cam-1",
        type = ProbeType.Camera,
        name = "Microscope",
        port = null,
        xOffsetUm = 12_000.0,
        yOffsetUm = -5_000.0,
    )

    @Test
    fun thePointIsThePositionPlusTheCameraOffset() {
        assertEquals(CrosshairPoint(112_000.0, 45_000.0), CameraProbePoint.underCrosshairs(camera, 100_000.0, 50_000.0))
    }

    @Test
    fun anOffsetInTheReadoutDoesNotAddTwoTimes() {
        // Measurement mode: the readouts show the spindle position plus the instrument offset.
        val readoutX = 100_000.0 + 12_000.0
        val readoutY = 50_000.0 - 5_000.0
        assertEquals(
            CrosshairPoint(112_000.0, 45_000.0),
            CameraProbePoint.underCrosshairs(camera, readoutX, readoutY, appliedOffsetXUm = 12_000.0, appliedOffsetYUm = -5_000.0),
        )
    }

    @Test
    fun anAxisThatIsOffHasNoValueAndOtherTypesHaveNoPoint() {
        assertEquals(CrosshairPoint(112_000.0, null), CameraProbePoint.underCrosshairs(camera, 100_000.0, null))
        assertNull(CameraProbePoint.underCrosshairs(camera.copy(type = ProbeType.TouchProbe), 1.0, 2.0))
        assertNull(CameraProbePoint.underCrosshairs(null, 1.0, 2.0))
        assertTrue(CameraProbePoint.canUse(camera, connected = true))
        assertFalse(CameraProbePoint.canUse(camera, connected = false))
        assertFalse(CameraProbePoint.canUse(camera.copy(type = ProbeType.EdgeFinder2D), connected = true))
    }

    @Test
    fun aCameraInstrumentGivesACameraSlotPoint() {
        val point = CameraProbePoint.measurementPoint(camera, 20_000.0, 30_000.0)
        assertEquals(
            CollectedPoint(
                x = 32_000.0,
                y = 25_000.0,
                z = null,
                source = PointSource.Camera,
                tipRadiusUm = null,
                approachX = 0,
                approachY = 0,
                approachZ = null,
                sourceId = "cam-1",
                instrumentId = "cam-1",
                instrumentXOffsetUm = 12_000.0,
                instrumentYOffsetUm = -5_000.0,
            ),
            point,
        )
        assertNull(CameraProbePoint.measurementPoint(camera.copy(type = ProbeType.ToolHeightSetter), 0.0, 0.0))
    }
}
