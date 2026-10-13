package org.superdro.core.readout.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.assertNear
import org.superdro.core.readout.encoder.ScaleFactor

/** The calibration math. */
class CalibrationTest {

    @Test
    fun theLinearInchExample() {
        val block = assertIs<BlockCheck.Ok>(CalibrationMath.checkBlock(2.0, metric = false))
        assertEquals(50800.0, block.blockUm)
        val result = CalibrationMath.linear(1250.0, 11410.0, block.blockUm)
        assertEquals(10160.0, result.delta)
        assertEquals("5080.000", result.resolutionText)
        assertEquals("5.00", result.umPerCountText)
        assertFalse(result.reversed)
    }

    @Test
    fun theLinearMmExample() {
        val block = CalibrationMath.blockUm(75.0, metric = true)
        assertEquals(75000.0, block)
        val result = CalibrationMath.linear(20000.0, 4250.4, block)
        assertNear(-15749.6, result.delta, 1e-9)
        assertEquals(5333.865, result.resolution)
        assertEquals("5333.865", result.resolutionText)
        assertEquals("4.76", result.umPerCountText)
        assertTrue(result.reversed)
    }

    @Test
    fun theAngularExample() {
        val result = CalibrationMath.angular(120.0, -35880.0)
        assertEquals(-36000.0, result.delta)
        assertEquals(7200.0, result.resolution)
        assertEquals("7200.000", result.resolutionText)
        assertEquals(0.05, result.degreesPerCount)
        assertEquals("0.0500", result.degreesPerCountText)
        assertTrue(result.reversed)
        assertEquals(180.0, ScaleFactor.angular(result.resolution), "180 arc seconds for each count")
    }

    @Test
    fun theRoundOffToThreeDecimalsGoesHalfUp() {
        assertEquals(5333.865, CalibrationMath.roundResolution(5333.8645333))
        // 0.0625 × 1000 is exactly 62.5, so this is a half step.
        assertEquals(0.063, CalibrationMath.roundResolution(0.0625))
        assertEquals(5080.0, CalibrationMath.roundResolution(5079.99996))
    }

    @Test
    fun theBlockSizeChecks() {
        val required = assertIs<BlockCheck.Required>(CalibrationMath.checkBlock(0.0, metric = true))
        assertEquals("Block Size Required", required.message.english)
        val small = assertIs<BlockCheck.TooSmall>(CalibrationMath.checkBlock(1.9, metric = false))
        assertNear(48260.0, small.blockUm, 1e-6)
        assertEquals("Block Too Small", small.message.english)
        assertEquals("at least 50.000 mm / 2.0000 in", small.detail.english)
        assertIs<BlockCheck.Ok>(CalibrationMath.checkBlock(50.0, metric = true))
    }

    @Test
    fun theSampleChecks() {
        val none = CalibrationMath.checkSample(emptyList())
        assertEquals(SampleCheck.NoReading, none)
        assertEquals("No Reading Received", none.message!!.english)
        val few = CalibrationMath.checkSample(List(9) { 100.0 })
        assertEquals(SampleCheck.TooFew(9), few)
        assertEquals("Reading Sample Too Small", few.message!!.english)
        // The trim removes one minimum and one maximum.
        val good = CalibrationMath.checkSample(listOf(0.0, 100.0, 100.0, 101.0, 100.0, 101.0, 100.0, 100.0, 101.0, 500.0))
        assertEquals(SampleCheck.Ok((100.0 * 5 + 101.0 * 3) / 8), good)
        assertNull(good.message)
        val jitter = CalibrationMath.checkSample(listOf(100.0, 100.0, 100.0, 102.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 103.0))
        assertIs<SampleCheck.Jitter>(jitter)
        assertEquals("Excessive Jitter Detected", jitter.message.english)
    }

    @Test
    fun theReadingsTooCloseCheck() {
        assertTrue(CalibrationMath.tooClose(1000.0, 1009.9))
        assertFalse(CalibrationMath.tooClose(1000.0, 1010.0))
        assertTrue(CalibrationMath.tooClose(1000.0, 991.0))
        assertEquals("Readings Too Close", CalibrationMath.tooCloseMessage.english)
    }

    @Test
    fun applyWritesTheEncoderProfile() {
        val now = Instant.parse("2026-10-12T10:00:00Z")
        val result = CalibrationMath.linear(20000.0, 4250.4, 75000.0)
        val applied = CalibrationMath.apply(EncoderProfile.defaultFor('x'), result, MachineType.VerticalMill, AxisId.X, now)
        assertEquals(5333.865, applied.resolution)
        assertTrue(applied.reversed)
        assertEquals(CalibrationStatus.Calibrated, applied.calibration.status)
        assertEquals(now, applied.calibration.calibratedOn)
        assertEquals(MachineType.VerticalMill, applied.calibration.machineType)
        assertEquals(AxisId.X, applied.calibration.axisRole)
    }

    @Test
    fun theErrorMapCorrectsEachSample() {
        val profile = EncoderProfile(errorMap = listOf(MapPoint(0, 0), MapPoint(10000, 4)))
        val sampler = CalibrationSampler(profile)
        sampler.sample(5000L)
        assertEquals(listOf(5002.0), sampler.sampleValues)
    }

    @Test
    fun theSamplerAddsTheHysteresisOfTheCalibratedInputWhenTheCountGoesUp() {
        val primary = EncoderProfile(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val secondary = EncoderProfile(input = 'w', backlashMode = BacklashMode.Constant, backlashCounts = 8L)
        val first = CalibrationSampler(primary)
        val second = CalibrationSampler(secondary)
        listOf(first, second).forEach { sampler ->
            sampler.observe(4990L)
            sampler.sample(5000L)
        }
        assertEquals(listOf(5003.0), first.sampleValues)
        assertEquals(listOf(5008.0), second.sampleValues, "each input uses its own hysteresis")
        second.startSample()
        second.observe(4000L)
        second.sample(4000L)
        assertEquals(listOf(4000.0), second.sampleValues, "no hysteresis when the count goes down")
        val leadscrew = CalibrationSampler(secondary.copy(type = EncoderType.LeadscrewEncoder))
        assertEquals(0L, leadscrew.hysteresis, "a leadscrew encoder has a backlash, not a hysteresis")
    }

    @Test
    fun theSamplerChecksTheSample() {
        val sampler = CalibrationSampler(EncoderProfile())
        assertEquals(SampleCheck.NoReading, sampler.check())
        repeat(12) { sampler.sample(1250L) }
        assertEquals(SampleCheck.Ok(1250.0), sampler.check())
    }

    @Test
    fun theEdgeNamesByMachineTypeAndAxis() {
        assertEquals(CalibrationEdge.Left to CalibrationEdge.Right, CalibrationStart.edges(MachineType.VerticalMill, AxisId.X))
        assertEquals(CalibrationEdge.Front to CalibrationEdge.Back, CalibrationStart.edges(MachineType.VerticalMill, AxisId.Y))
        assertEquals(CalibrationEdge.Bottom to CalibrationEdge.Top, CalibrationStart.edges(MachineType.VerticalMill, AxisId.Z))
        assertEquals(CalibrationEdge.Left to CalibrationEdge.Right, CalibrationStart.edges(MachineType.HorizontalMill, AxisId.X))
        assertEquals(CalibrationEdge.Bottom to CalibrationEdge.Top, CalibrationStart.edges(MachineType.HorizontalMill, AxisId.Y))
        assertEquals(CalibrationEdge.Front to CalibrationEdge.Back, CalibrationStart.edges(MachineType.HorizontalMill, AxisId.Z))
        assertEquals(CalibrationEdge.Front to CalibrationEdge.Back, CalibrationStart.edges(MachineType.Lathe, AxisId.X))
        assertEquals(CalibrationEdge.Left to CalibrationEdge.Right, CalibrationStart.edges(MachineType.Lathe, AxisId.Z))
        assertEquals(CalibrationEdge.First to CalibrationEdge.Second, CalibrationStart.edges(MachineType.Lathe, AxisId.Y))
        assertEquals(CalibrationEdge.First to CalibrationEdge.Second, CalibrationStart.edges(MachineType.Lathe, AxisId.Caliper))
        assertEquals(CalibrationEdge.First to CalibrationEdge.Second, CalibrationStart.edges(MachineType.VerticalMill, null))
        assertEquals("left", CalibrationEdge.Left.text.english)
        assertEquals("second", CalibrationEdge.Second.text.english)
    }

    @Test
    fun theStartChecksAndTheAxisOfAnInput() {
        assertEquals("Secondary Input Disabled", CalibrationStart.check(null, secondary = true)!!.english)
        assertEquals("Input Prefix Missing", CalibrationStart.check(InputConfig(' '), secondary = false)!!.english)
        assertNull(CalibrationStart.check(InputConfig('x'), secondary = false))
        val mill = Fixtures.mill().let { profile -> profile.copy(z = profile.z.copy(secondary = InputConfig('w'))) }
        assertEquals(AxisId.Z, CalibrationStart.axisForInput(mill, 'W'))
        assertEquals(AxisId.X, CalibrationStart.axisForInput(mill, 'x'))
        assertNull(CalibrationStart.axisForInput(Fixtures.mill().copy(caliper = Fixtures.mill().caliper.copy(input = InputConfig('u'))), 'v'))
        assertEquals(AxisId.Caliper, CalibrationStart.axisForInput(Fixtures.mill().copy(caliper = Fixtures.mill().caliper.copy(input = InputConfig('u'))), 'u'))
        assertTrue(CalibrationStart.angular(EncoderProfile(kind = EncoderKind.Angular)))
    }

    @Test
    fun theSampleTimeIs15Seconds() {
        assertEquals(15000L, CalibrationMath.SAMPLE_TIME_MS)
        assertEquals(1000L, CalibrationMath.PROGRESS_INTERVAL_MS)
    }
}
