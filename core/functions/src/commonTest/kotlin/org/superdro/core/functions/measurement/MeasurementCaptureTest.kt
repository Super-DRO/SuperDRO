package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.superdro.core.functions.PointUm
import org.superdro.core.model.Plane
import org.superdro.core.model.PointSource
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SubDatum

class MeasurementCaptureTest {

    // The tolerance
    @Test
    fun theToleranceIsFourTimesTheLargestStep() {
        assertEquals(20.0, MeasurementTolerance.toleranceUm(listOf(5080.0, 25400.0)), 1e-12)
        assertEquals(4.0, MeasurementTolerance.toleranceUm(listOf(25400.0, 25400.0)), 1e-12)
        assertEquals(4.0, MeasurementTolerance.toleranceUm(listOf(null, 0.0)), 1e-12)
        assertEquals(4.0, MeasurementTolerance.toleranceUm(emptyList()), 1e-12)
        assertEquals(20.0, MeasurementTolerance.toleranceUm(listOf(null, 5080.0)), 1e-12)
        assertEquals(5.0, MeasurementTolerance.resolutionUm(listOf(5080.0)), 1e-12)
    }

    @Test
    fun aMotionOfFiveMicrometersInTheWindowGivesTheSign() {
        val history = listOf(HistorySample(0, 0.0), HistorySample(400, 2.0), HistorySample(800, 5.0))
        assertEquals(1, ApproachSign.of(history, 900))
        val down = listOf(HistorySample(0, 5.0), HistorySample(400, 2.0), HistorySample(800, 0.0))
        assertEquals(-1, ApproachSign.of(down, 900))
    }

    @Test
    fun aMotionOfFourMicrometersGivesNoSign() {
        val history = listOf(HistorySample(0, 0.0), HistorySample(400, 2.0), HistorySample(800, 4.0))
        assertEquals(0, ApproachSign.of(history, 900))
    }

    @Test
    fun aGapOfMoreThan500MillisecondsGivesNoSign() {
        assertEquals(0, ApproachSign.of(listOf(HistorySample(0, 0.0), HistorySample(600, 10.0)), 650))
        assertEquals(1, ApproachSign.of(listOf(HistorySample(0, 0.0), HistorySample(500, 10.0)), 550))
        // The last sample is more than 500 ms before the trigger: the axis stands still.
        assertEquals(0, ApproachSign.of(listOf(HistorySample(0, 0.0), HistorySample(100, 10.0)), 700))
    }

    @Test
    fun aMotionOutsideTheWindowGivesNoSign() {
        val history = listOf(HistorySample(0, 0.0), HistorySample(450, 1.0), HistorySample(900, 2.0), HistorySample(1300, 3.0))
        assertEquals(0, ApproachSign.of(history, 1300))
        assertEquals(0, ApproachSign.of(emptyList(), 100))
        assertEquals(0, ApproachSign.of(listOf(HistorySample(200, 0.0)), 100))
    }

    @Test
    fun theTipRadiusIsHalfTheEffectiveDiameterOfAProbe() {
        assertEquals(1000.0, PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.TouchProbe, stylusDiameterUm = 2000.0)))
        assertEquals(
            995.0,
            PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.TouchProbe, stylusDiameterUm = 2000.0, effectiveDiameterUm = 1990.0)),
        )
        assertEquals(2540.0, PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.EdgeFinder2D, stylusDiameterUm = 5080.0)))
        assertNull(PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.EdgeFinder2D, stylusDiameterUm = 0.0)))
        assertNull(PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.Camera, stylusDiameterUm = 2000.0)))
        assertNull(PointCapture.tipRadiusUm(ProbeProfile(type = ProbeType.ToolHeightSetter, stylusDiameterUm = 2000.0)))
        assertNull(PointCapture.tipRadiusUm(null))
    }

    @Test
    fun aProbeTriggerWithNoPositionOrNoApproachIsRefused() {
        val unknown = PointCapture.probe(Double.NaN, 0.0, null, 1, 0, null, 1000.0)
        assertEquals(ProbeRefusal.PositionUnknown, assertIs<ProbeCapture.Refused>(unknown).reason)
        val still = PointCapture.probe(0.0, 0.0, null, 0, 0, 1, 1000.0)
        assertEquals(ProbeRefusal.NoApproach, assertIs<ProbeCapture.Refused>(still).reason)
    }

    @Test
    fun aProbeTriggerGivesAPointWithTheApproachAndTheTipRadius() {
        val capture = PointCapture.probe(100.0, 200.0, -50.0, 0, -1, 1, 1000.0, "probe-1", 10.0, -20.0)
        val point = assertIs<ProbeCapture.Collected>(capture).point
        assertEquals(PointSource.Probe, point.source)
        assertEquals(1000.0, point.tipRadiusUm)
        assertEquals(listOf(0, -1, 1), listOf(point.approachX, point.approachY, point.approachZ))
        assertEquals("probe-1", point.instrumentId)
        assertEquals(10.0, point.instrumentXOffsetUm)
        assertEquals(-50.0, point.z)
        val noRadius = assertIs<ProbeCapture.Collected>(PointCapture.probe(0.0, 0.0, null, 1, 0, null, 0.0)).point
        assertNull(noRadius.tipRadiusUm)
    }

    @Test
    fun aTapGivesALatchPointOrACameraPoint() {
        val latch = PointCapture.latch(1.0, 2.0, 3.0)!!
        assertEquals(PointSource.Latch, latch.source)
        assertNull(latch.tipRadiusUm)
        assertEquals(listOf(0, 0), listOf(latch.approachX, latch.approachY))
        assertNull(PointCapture.latch(Double.NaN, 2.0))
        val camera = PointCapture.camera(1.0, 2.0, null, "cam", 5000.0, -3000.0)!!
        assertEquals(PointSource.Camera, camera.source)
        assertEquals(5000.0, camera.instrumentXOffsetUm)
        assertEquals("cam", camera.instrumentId)
    }

    @Test
    fun aSubDatumTapGivesItsPlanePosition() {
        val subDatum = SubDatum(id = "s1", x = 1.0, y = 2.0, z = 3.0)
        assertEquals(PointUm(1.0, 2.0), PointCapture.planeCoordinates(subDatum, Plane.XY))
        assertEquals(PointUm(1.0, 3.0), PointCapture.planeCoordinates(subDatum, Plane.XZ))
        assertEquals(PointUm(2.0, 3.0), PointCapture.planeCoordinates(subDatum, Plane.YZ))
        assertEquals(PointUm(3.0, 1.0), PointCapture.planeCoordinates(subDatum, Plane.ZX))
        assertNull(PointCapture.planeCoordinates(SubDatum(x = 1.0, y = 2.0), Plane.XZ))
        val point = PointCapture.subDatum("s1", 1.0, 2.0)
        assertEquals(PointSource.SubDatum, point.source)
        assertEquals("s1", point.sourceId)
        assertNull(point.z)
    }
}
