package org.superdro.validation.readout

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
import org.superdro.core.model.EncoderType
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.calibration.BlockCheck
import org.superdro.core.readout.calibration.CalibrationMath
import org.superdro.core.readout.calibration.CalibrationSampler
import org.superdro.core.readout.calibration.CalibrationStart
import org.superdro.core.readout.calibration.SampleCheck

/**
 * The block check, the sample checks, the formulas, the three
 * worked examples, the edge names, and the apply.
 */
class CalibrationValidationTest {

    // Step 1: the block size.

    @Test
    fun block_um_is_value_times_1000_or_25400() {
        assertEquals(50800.0, CalibrationMath.blockUm(2.0, metric = false))
        assertEquals(75000.0, CalibrationMath.blockUm(75.0, metric = true))
    }

    @Test
    fun block_0_gives_Block_Size_Required() {
        val check = assertIs<BlockCheck.Required>(CalibrationMath.checkBlock(0.0, metric = true))
        assertEquals("Block Size Required", check.message.english)
    }

    @Test
    fun block_less_than_50000_um_gives_Block_Too_Small() {
        val check = assertIs<BlockCheck.TooSmall>(CalibrationMath.checkBlock(1.9, metric = false))
        assertEquals("Block Too Small", check.message.english)
        assertEquals("at least 50.000 mm / 2.0000 in", check.detail.english)
        assertIs<BlockCheck.TooSmall>(CalibrationMath.checkBlock(49.999, metric = true))
        assertEquals(BlockCheck.Ok(50000.0), CalibrationMath.checkBlock(50.0, metric = true))
        assertEquals(BlockCheck.Ok(50800.0), CalibrationMath.checkBlock(2.0, metric = false))
    }

    @Test
    fun sample_time_is_15000_ms_with_progress_each_1000_ms() {
        assertEquals(15000L, CalibrationMath.SAMPLE_TIME_MS)
        assertEquals(1000L, CalibrationMath.PROGRESS_INTERVAL_MS)
    }

    // The checks for each sample.

    @Test
    fun N_0_gives_No_Reading_Received() {
        val check = CalibrationMath.checkSample(emptyList())
        assertEquals(SampleCheck.NoReading, check)
        assertEquals("No Reading Received", check.message?.english)
    }

    @Test
    fun N_less_than_10_gives_Reading_Sample_Too_Small() {
        val check = assertIs<SampleCheck.TooFew>(CalibrationMath.checkSample(List(9) { 1250.0 }))
        assertEquals(9, check.count)
        assertEquals("Reading Sample Too Small", check.message.english)
    }

    @Test
    fun trim_removes_one_minimum_and_one_maximum_then_the_mean() {
        val values = listOf(0.0) + List(8) { 1250.0 } + listOf(99999.0)
        assertEquals(SampleCheck.Ok(1250.0), CalibrationMath.checkSample(values))
        val oneCount = listOf(1300.0, 1249.0) + List(7) { 1250.0 } + listOf(1251.0, 1100.0)
        // Trimmed: 1249, 1250 × 7, 1251: spread 2 > 1 count.
        assertIs<SampleCheck.Jitter>(CalibrationMath.checkSample(oneCount))
    }

    @Test
    fun jitter_more_than_1_count_gives_Excessive_Jitter_Detected() {
        val values = listOf(1248.0) + List(7) { 1250.0 } + listOf(1252.0, 1260.0)
        val check = assertIs<SampleCheck.Jitter>(CalibrationMath.checkSample(values))
        assertEquals("Excessive Jitter Detected", check.message.english)
        val exactlyOne = listOf(1249.0) + List(7) { 1250.0 } + listOf(1251.0, 1300.0)
        // Trimmed: 1250 × 7, 1251: spread 1, no warning. Mean = (1250 × 7 + 1251) / 8.
        assertEquals(SampleCheck.Ok(1250.125), CalibrationMath.checkSample(exactlyOne))
    }

    @Test
    fun means_less_than_10_counts_apart_give_Readings_Too_Close() {
        assertTrue(CalibrationMath.tooClose(1250.0, 1259.0))
        assertTrue(CalibrationMath.tooClose(1259.0, 1250.0))
        assertFalse(CalibrationMath.tooClose(1250.0, 1260.0))
        assertEquals("Readings Too Close", CalibrationMath.tooCloseMessage.english)
    }

    // The worked examples.

    @Test
    fun linear_inch_2_in_means_1250_and_11410_gives_5080_000() {
        val result = CalibrationMath.linear(1250.0, 11410.0, CalibrationMath.blockUm(2.0, metric = false))
        assertEquals(10160.0, result.delta)
        assertEquals(5080.0, result.resolution)
        assertEquals("5080.000", result.resolutionText)
        assertEquals("5.00", result.umPerCountText)
        assertFalse(result.reversed)
    }

    @Test
    fun linear_mm_75_mm_means_20000_and_4250_4_gives_5333_865_reversed() {
        val result = CalibrationMath.linear(20000.0, 4250.4, CalibrationMath.blockUm(75.0, metric = true))
        assertEquals(-15749.6, result.delta, 1e-9)
        assertEquals(5333.865, result.resolution)
        assertEquals("5333.865", result.resolutionText)
        assertEquals("4.76", result.umPerCountText)
        assertTrue(result.reversed)
    }

    @Test
    fun angular_means_120_and_minus_35880_gives_7200_000_reversed() {
        val result = CalibrationMath.angular(120.0, -35880.0)
        assertEquals(-36000.0, result.delta)
        assertEquals(7200.0, result.resolution)
        assertEquals("7200.000", result.resolutionText)
        assertEquals(0.05, result.degreesPerCount!!, 1e-12)
        assertTrue(result.reversed)
        assertNull(result.umPerCount)
        assertEquals(5, CalibrationMath.ANGULAR_REVOLUTIONS)
    }

    @Test
    fun resolution_has_3_decimals() {
        assertEquals(5333.865, CalibrationMath.roundResolution(5333.8645333))
    }

    @Test
    fun apply_writes_resolution_direction_status_date_machine_type_and_axis() {
        val now = Instant.fromEpochMilliseconds(1_760_000_000_000L)
        val result = CalibrationMath.linear(20000.0, 4250.4, 75000.0)
        val written = CalibrationMath.apply(scale('x'), result, MachineType.Lathe, AxisId.X, now)
        assertEquals(5333.865, written.resolution)
        assertTrue(written.reversed)
        assertEquals(CalibrationStatus.Calibrated, written.calibration.status)
        assertEquals(now, written.calibration.calibratedOn)
        assertEquals(MachineType.Lathe, written.calibration.machineType)
        assertEquals(AxisId.X, written.calibration.axisRole)
    }

    @Test
    fun the_error_map_corrects_each_sample() {
        // Points 0,0 and 10000,4. Count 5000 gives 5002; count 12000 gives 12004.
        val profile = scale('x').copy(errorMap = listOf(MapPoint(0L, 0L), MapPoint(10000L, 4L)))
        val sampler = CalibrationSampler(profile)
        assertEquals(5002.0, sampler.valueOf(5000L))
        assertEquals(12004.0, sampler.valueOf(12000L))
    }

    @Test
    fun sample_adds_the_hysteresis_of_the_calibrated_input_when_the_count_goes_up() {
        val profile = scale('w').copy(type = EncoderType.Scale, backlashMode = BacklashMode.Constant, backlashCounts = 7L)
        val sampler = CalibrationSampler(profile)
        assertEquals(7L, sampler.hysteresis)
        sampler.observe(100L)
        sampler.sample(101L)
        assertEquals(listOf(108.0), sampler.sampleValues)
        sampler.sample(100L)
        assertEquals(listOf(108.0, 100.0), sampler.sampleValues)
    }

    @Test
    fun start_needs_the_secondary_switch_and_a_channel() {
        assertEquals("Secondary Input Disabled", CalibrationStart.check(null, secondary = true)?.english)
        assertEquals("Input Prefix Missing", CalibrationStart.check(null, secondary = false)?.english)
        assertNull(CalibrationStart.check(InputConfig('x'), secondary = false))
        assertNull(CalibrationStart.check(InputConfig('w'), secondary = true))
    }

    @Test
    fun wizard_reads_the_raw_counts_of_the_channel() {
        val stage = stage(mill(), adapter(scale('x').copy(errorMap = listOf(MapPoint(0L, 0L), MapPoint(10000L, 4L)))))
        stage.onAxisCount('x', 5000L, 0L)
        val value = stage.snapshot.channels.getValue('x')
        assertEquals(5000L, value.rawCount)
        assertEquals(5002L, value.correctedCount)
    }

    // The edge names.

    @Test
    fun edge_names_by_machine_type_and_axis() {
        val rows = listOf(
            Triple(MachineType.VerticalMill, AxisId.X, "left" to "right"),
            Triple(MachineType.VerticalMill, AxisId.Y, "front" to "back"),
            Triple(MachineType.VerticalMill, AxisId.Z, "bottom" to "top"),
            Triple(MachineType.HorizontalMill, AxisId.X, "left" to "right"),
            Triple(MachineType.HorizontalMill, AxisId.Y, "bottom" to "top"),
            Triple(MachineType.HorizontalMill, AxisId.Z, "front" to "back"),
            Triple(MachineType.Lathe, AxisId.X, "front" to "back"),
            Triple(MachineType.Lathe, AxisId.Z, "left" to "right"),
            Triple(MachineType.Lathe, AxisId.Y, "first" to "second"),
            Triple(MachineType.Lathe, AxisId.Caliper, "first" to "second"),
        )
        rows.forEach { (type, axis, expected) ->
            val (first, second) = CalibrationStart.edges(type, axis)
            assertEquals(expected, first.text.english to second.text.english, "$type $axis")
        }
    }
}
