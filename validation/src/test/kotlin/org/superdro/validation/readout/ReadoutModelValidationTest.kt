package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.DisplaySettings
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.FeedValues
import org.superdro.core.readout.NumberPattern
import org.superdro.core.readout.PostProcessor
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.UnitConverter
import org.superdro.core.readout.encoder.ScaleFactor
import org.superdro.core.readout.encoder.legacy.LegacyFiveSampleFilter
import org.superdro.core.readout.encoder.legacy.LegacyInput
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.operations.AxisOperations

/**
 * The input stage reference, the readout model, the
 * post-processors, the unit converters, the number format, and the axis details values.
 */
class ReadoutModelValidationTest {

    // The scale factor k.

    @Test
    fun linear_ppu_5080_gives_5_um_per_count() {
        assertEquals(5.0, ScaleFactor.linear(5080.0))
    }

    @Test
    fun linear_ppu_2540_gives_10_um_per_count() {
        assertEquals(10.0, ScaleFactor.linear(2540.0))
    }

    @Test
    fun angular_ppu_1440_gives_900_arcsec_per_count() {
        assertEquals(900.0, ScaleFactor.angular(1440.0))
    }

    @Test
    fun angular_ppu_1000_gives_1296_arcsec_per_count() {
        assertEquals(1296.0, ScaleFactor.angular(1000.0))
    }

    @Test
    fun rotary_k_is_one_over_ppu_and_zero_for_ppu_zero() {
        assertEquals(0.25, ScaleFactor.rotary(4.0))
        assertEquals(0.0, ScaleFactor.rotary(0.0))
    }

    @Test
    fun invert_negates_k() {
        assertEquals(-5.0, ScaleFactor.withDirection(5.0, invert = true))
        assertEquals(5.0, ScaleFactor.withDirection(5.0, invert = false))
        assertEquals(-5.0, ScaleFactor.of('x', EncoderProfile(input = 'x', resolution = 5080.0, reversed = true)))
        assertEquals(900.0, ScaleFactor.of('w', angularEncoder('w', 1440.0)))
    }

    // The legacy hysteresis (reference). k = 5 µm, H = 3.

    @Test
    fun hysteresis_reference_100_to_101_gives_520_and_101_to_100_gives_500() {
        val input = LegacyInput(k = 5.0, hysteresis = 3L, filterEnabled = false)
        input.process(100L)
        assertEquals(520.0, input.process(101L))
        assertEquals(500.0, input.process(100L))
    }

    @Test
    fun hysteresis_reference_previous_starts_at_0_and_d_0_keeps_the_direction() {
        val input = LegacyInput(k = 5.0, hysteresis = 3L, filterEnabled = false)
        // previous = 0, count 100: d < 0, the count went up: (100 + 3) × 5.
        assertEquals(515.0, input.process(100L))
        input.process(101L)
        // d == 0: keep the last direction (up).
        assertEquals(520.0, input.process(101L))
    }

    @Test
    fun filter_starts_with_the_first_count() {
        // The ring starts with the first count in all 5 places.
        assertEquals(1000L, LegacyFiveSampleFilter().filter(1000L))
    }

    @Test
    fun filter_converges_to_the_count_at_rest() {
        val input = LegacyInput(k = 1.0, hysteresis = 0L, filterEnabled = true)
        input.process(1000L, timeMs = 0L)
        input.process(1010L, timeMs = 0L)
        // The adapter sends no more counts. The stage gives the last count again each 100 ms.
        var time = 0L
        repeat(50) {
            time += 100L
            input.onTick(time)
        }
        assertEquals(1010.0, input.value)
        assertFalse(input.needsRepeat)
    }

    // The readout model.

    @Test
    fun R_63500_A_minus_12700_inch_format_3_gives_abs_50800_and_2_0000() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(absoluteOffset = -12700.0) }
        val reading = frame(profile, workspace, AxisId.X to 63500.0).reading(AxisId.X)
        assertEquals(50800.0, reading.absolute)
        assertEquals("2.0000", reading.text)
    }

    @Test
    fun count_12345_at_5_um_gives_2_4301_in_and_61_725_mm() {
        val profile = mill()
        val stage = stage(profile, adapter(scale('x', 5080.0)))
        stage.onAxisCount('x', 12345L, 0L)
        assertEquals(61725.0, stage.r(AxisId.X))
        val inch = ReadoutFrame.of(profile, stage.snapshot).reading(AxisId.X)
        assertEquals("2.4301", inch.text)
        val metric = ReadoutFrame.of(profile, stage.snapshot, profile.workspace.axis(AxisId.X) { state -> state.copy(metric = true) })
        assertEquals("61.725", metric.reading(AxisId.X).text)
    }

    @Test
    fun lathe_diameter_mode_slide_12700_shows_25400_and_1_0000() {
        val reading = frameOf(lathe(), AxisId.X to 12700.0).reading(AxisId.X)
        assertEquals(25400.0, reading.shown)
        assertEquals("1.0000", reading.text)
    }

    @Test
    fun lathe_set_X_to_1_25_in_stores_A_c_31750_minus_R() {
        // A = c(31750) − (R + T) = 15875 − 12700.
        val result = AxisOperations.setAbsolute(frameOf(lathe(), AxisId.X to 12700.0), AxisId.X, 31750.0)
        assertEquals(3175.0, result.workspace.axisStates.getValue(AxisId.X).absoluteOffset, 1e-9)
    }

    @Test
    fun angular_ppu_1440_count_250_gives_62_50_degrees_and_4_17_divisions() {
        val profile = mill().let { base -> base.copy(angular = base.angular.copy(enabled = true)) }
        val stage = stage(profile, adapter(angularEncoder('w', 1440.0)))
        stage.onAxisCount('w', 250L, 0L)
        assertEquals(225000.0, stage.r(AxisId.Angular))
        assertEquals("62.50", ReadoutFrame.of(profile, stage.snapshot).reading(AxisId.Angular).text)
        val divisions = profile.workspace.axis(AxisId.Angular) { state -> state.copy(divisionUnits = true, divisionsPerRev = 24) }
        assertEquals("4.17", ReadoutFrame.of(profile, stage.snapshot, divisions).reading(AxisId.Angular).text)
    }

    @Test
    fun angular_ppu_1440_count_minus_100_gives_335_00_degrees_and_22_33_divisions() {
        val profile = mill().let { base -> base.copy(angular = base.angular.copy(enabled = true)) }
        val stage = stage(profile, adapter(angularEncoder('w', 1440.0)))
        stage.onAxisCount('w', -100L, 0L)
        assertEquals(-90000.0, stage.r(AxisId.Angular))
        assertEquals("335.00", ReadoutFrame.of(profile, stage.snapshot).reading(AxisId.Angular).text)
        val divisions = profile.workspace.axis(AxisId.Angular) { state -> state.copy(divisionUnits = true, divisionsPerRev = 24) }
        assertEquals("22.33", ReadoutFrame.of(profile, stage.snapshot, divisions).reading(AxisId.Angular).text)
    }

    @Test
    fun abs_is_R_plus_active_T_plus_A() {
        val profile = mill()
        val active = profile.workspace.axis(AxisId.X) { state ->
            state.copy(absoluteOffset = 1000.0, toolOffset = 500.0, toolOffsetActive = true)
        }
        assertEquals(11500.0, frame(profile, active, AxisId.X to 10000.0).reading(AxisId.X).absolute)
        val inactive = active.axis(AxisId.X) { state -> state.copy(toolOffsetActive = false) }
        assertEquals(11000.0, frame(profile, inactive, AxisId.X to 10000.0).reading(AxisId.X).absolute)
    }

    @Test
    fun incremental_mode_adds_I_and_absolute_mode_does_not() {
        val profile = mill()
        val incremental = profile.workspace.axis(AxisId.X) { state -> state.copy(incrementalOffset = -2000.0, relative = true) }
        assertEquals(8000.0, frame(profile, incremental, AxisId.X to 10000.0).reading(AxisId.X).shown)
        val absolute = incremental.axis(AxisId.X) { state -> state.copy(relative = false) }
        assertEquals(10000.0, frame(profile, absolute, AxisId.X to 10000.0).reading(AxisId.X).shown)
    }

    @Test
    fun I_is_not_in_use_while_a_sub_datum_offset_is_active() {
        val profile = mill()
        val point = SubDatum(name = "Point 1", x = 25400.0)
        val workspace = profile.workspace
            .axis(AxisId.X) { state -> state.copy(incrementalOffset = -2000.0, relative = true) }
            .copy(subDatums = listOf(point), activeSubDatumId = point.id)
        // v = abs + S = 30000 − 25400; the incremental offset does not apply.
        assertEquals(4600.0, frame(profile, workspace, AxisId.X to 30000.0).reading(AxisId.X).shown)
    }

    @Test
    fun tachometer_uses_R_plus_A_and_no_T() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.Tachometer) { state ->
            state.copy(absoluteOffset = 100.0, toolOffset = 500.0, toolOffsetActive = true)
        }
        assertEquals(1300.0, frame(profile, workspace, AxisId.Tachometer to 1200.0).reading(AxisId.Tachometer).absolute)
    }

    // Post-processors.

    @Test
    fun diameter_post_processor_h_is_2v_and_c_is_v_over_2_only_on_a_lathe_X() {
        val diameter = AxisState(diameterMode = true)
        assertEquals(PostProcessor.Diameter, PostProcessor.of(AxisId.X, diameter, MachineType.Lathe))
        assertEquals(PostProcessor.Identity, PostProcessor.of(AxisId.X, diameter, MachineType.VerticalMill))
        assertEquals(PostProcessor.Identity, PostProcessor.of(AxisId.Z, diameter, MachineType.Lathe))
        assertEquals(25400.0, PostProcessor.Diameter.toShown(12700.0))
        assertEquals(12700.0, PostProcessor.Diameter.toInternal(25400.0))
    }

    @Test
    fun angular_post_processor_wraps_at_1296000_and_keeps_the_sign() {
        assertEquals(PostProcessor.AngularWrap, PostProcessor.of(AxisId.Angular, AxisState(), MachineType.VerticalMill))
        assertEquals(104000.0, PostProcessor.AngularWrap.toShown(1400000.0))
        assertEquals(-104000.0, PostProcessor.AngularWrap.toShown(-1400000.0))
        assertEquals(104000.0, PostProcessor.AngularWrap.toInternal(1400000.0))
    }

    // Unit converters.

    @Test
    fun linear_converters_inch_and_mm() {
        assertEquals(2.0, UnitConverter.Inch.toDisplay(50800.0))
        assertEquals(50800.0, UnitConverter.Inch.toInternal(2.0))
        assertEquals(61.725, UnitConverter.Millimeter.toDisplay(61725.0), 1e-12)
        assertEquals(61725.0, UnitConverter.Millimeter.toInternal(61.725), 1e-9)
    }

    @Test
    fun degrees_converter_both_directions() {
        assertEquals(62.5, UnitConverter.Degrees.toDisplay(225000.0))
        assertEquals(335.0, UnitConverter.Degrees.toDisplay(-90000.0))
        // d > 0: (d % 360) × 3600. Else ((d % 360) + 360) × 3600.
        assertEquals(36000.0, UnitConverter.Degrees.toInternal(370.0))
        assertEquals(1260000.0, UnitConverter.Degrees.toInternal(-10.0))
    }

    @Test
    fun divisions_converter_N_24() {
        val divisions = UnitConverter.Divisions(24)
        assertEquals(15.0, divisions.degreesPerDivision)
        assertEquals(4.1667, divisions.toDisplay(225000.0), 1e-4)
        assertEquals(22.3333, divisions.toDisplay(-90000.0), 1e-4)
        // The user types 5: h(5) = (5 × 15 mod 360) × 3600 = 270000″ = 75°.
        assertEquals(270000.0, divisions.toInternal(5.0))
    }

    @Test
    fun tachometer_converter_is_identity() {
        assertEquals(1200.0, UnitConverter.Rpm.toDisplay(1200.0))
        assertEquals(1200.0, UnitConverter.Rpm.toInternal(1200.0))
    }

    // Number format.

    @Test
    fun round_off_is_half_up_toward_plus_infinity() {
        val pattern = NumberPattern("####0")
        assertEquals("12346", pattern.format(12345.5))
        assertEquals("-12345", pattern.format(-12345.5))
    }

    @Test
    fun round_off_agrees_with_java_math_round() {
        val values = listOf(0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 12345.5, -12345.5, 0.49999999999999994, -0.49999999999999994, 24301.18, -24301.5)
        values.forEach { value -> assertEquals(Math.round(value), NumberPattern.roundHalfUp(value), "value $value") }
    }

    @Test
    fun text_never_shows_minus_zero() {
        val pattern = NumberPattern("##0.0000")
        assertEquals("0.0000", pattern.format(-0.00001))
        assertEquals("0.0000", pattern.format(-0.00005))
        assertEquals("0.0000", pattern.format(-0.0))
    }

    @Test
    fun point_as_decimal_mark_minus_sign_and_no_group_separator() {
        assertEquals("-12345.6789", NumberPattern("##0.0000").format(-12345.6789))
        assertEquals("1234567.500", NumberPattern("###0.000").format(1234567.5))
    }

    @Test
    fun no_int_limit_25000_in_with_format_5() {
        // The reading has no 32-bit integer limit (21474.83647 in).
        val profile = mill().copy(display = DisplaySettings(readoutFormat = ReadoutFormat.Format5))
        assertEquals("25000.00000", frameOf(profile, AxisId.X to 635_000_000.0).reading(AxisId.X).text)
    }

    @Test
    fun display_format_patterns() {
        assertEquals("##0.000", ReadoutPatterns.linear(ReadoutFormat.Format2, metric = false).pattern)
        assertEquals("###0.00", ReadoutPatterns.linear(ReadoutFormat.Format2, metric = true).pattern)
        assertEquals("##0.0000", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = false).pattern)
        assertEquals("###0.000", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = true).pattern)
        assertEquals("##0.00000", ReadoutPatterns.linear(ReadoutFormat.Format5, metric = false).pattern)
        assertEquals("###0.0000", ReadoutPatterns.linear(ReadoutFormat.Format5, metric = true).pattern)
        assertEquals(ReadoutFormat.Format3, DisplaySettings().readoutFormat)
    }

    @Test
    fun display_format_codes_0_1_4_map_to_2_3_5() {
        assertEquals(ReadoutFormat.Format2, ReadoutPatterns.formatForCode(0))
        assertEquals(ReadoutFormat.Format3, ReadoutPatterns.formatForCode(1))
        assertEquals(ReadoutFormat.Format2, ReadoutPatterns.formatForCode(2))
        assertEquals(ReadoutFormat.Format3, ReadoutPatterns.formatForCode(3))
        assertEquals(ReadoutFormat.Format5, ReadoutPatterns.formatForCode(4))
        assertEquals(ReadoutFormat.Format5, ReadoutPatterns.formatForCode(5))
    }

    @Test
    fun other_formats() {
        assertEquals("##0.00", ReadoutPatterns.ANGULAR.pattern)
        assertEquals("####0", ReadoutPatterns.RPM.pattern)
        assertEquals("###0.00", ReadoutPatterns.SURFACE_SPEED.pattern)
        assertEquals("###0.00", ReadoutPatterns.THREAD_PITCH_MM.pattern)
        assertEquals("###0.0", ReadoutPatterns.THREADS_PER_INCH.pattern)
    }

    @Test
    fun value_that_is_not_finite_shows_dashes() {
        assertEquals("---.----", NumberPattern("##0.0000").format(Double.NaN))
        assertEquals("---.----", NumberPattern("##0.0000").format(Double.POSITIVE_INFINITY))
        assertEquals("----.---", NumberPattern("###0.000").format(Double.NaN))
        assertEquals("---.--", ReadoutPatterns.ANGULAR.format(Double.NaN))
        // An axis with no value.
        assertEquals("---.----", frameOf(mill()).reading(AxisId.X).text)
    }

    // Display states.

    @Test
    fun input_with_no_resolution_gives_the_error_state() {
        val profile = mill()
        val stage = stage(profile, adapter(scale('x', 0.0)))
        stage.onAxisCount('x', 100L, 0L)
        val value = stage.snapshot.axes.getValue(AxisId.X)
        assertEquals(AxisValueStatus.Error, value.status)
        assertNull(value.value)
        assertEquals("Error", ReadoutText.StateError.english)
    }

    @Test
    fun input_with_no_encoder_profile_gives_the_error_state() {
        val profile = mill()
        val stage = stage(profile, null)
        stage.onAxisCount('x', 100L, 0L)
        assertEquals(AxisValueStatus.Error, stage.snapshot.axes.getValue(AxisId.X).status)
        assertNull(stage.r(AxisId.X))
    }

    @Test
    fun disabled_input_gives_no_value_and_No_input() {
        val profile = mill()
        val stage = stage(profile, adapter(EncoderProfile(input = 'x', kind = EncoderKind.Disabled)))
        stage.onAxisCount('x', 100L, 0L)
        val value = stage.snapshot.axes.getValue(AxisId.X)
        assertEquals(AxisValueStatus.NoInput, value.status)
        assertNull(value.value)
        assertEquals("No input", ReadoutText.StateNoInput.english)
    }

    @Test
    fun active_tool_offset_gives_the_warning_state() {
        val profile = mill()
        val workspace = profile.workspace.axis(AxisId.X) { state -> state.copy(toolOffset = 500.0, toolOffsetActive = true) }
        assertTrue(frame(profile, workspace, AxisId.X to 0.0).reading(AxisId.X).modified)
        assertFalse(frameOf(profile, AxisId.X to 0.0).reading(AxisId.X).modified)
    }

    @Test
    fun last_values_stay_after_the_link_starts_again() {
        val profile = mill()
        val stage = stage(profile, adapter())
        stage.onAxisCount('x', 1234L, 0L)
        stage.onLinkStart()
        assertEquals(1234.0, stage.r(AxisId.X))
    }

    // The axis details values.

    @Test
    fun slide_moves_250_um_each_50_ms_gives_300000_um_per_min() {
        val profile = mill()
        val stage = stage(profile, adapter())
        var time = 0L
        var count = 0L
        while (time <= 2000L) {
            stage.onAxisCount('x', count, time)
            time += 50L
            count += 250L
        }
        val rate = FeedValues.feedRate(stage.history, AxisId.X, 2000L)
        assertEquals(300000.0, rate, 1e-6)
        assertEquals("300.000", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = true).format(UnitConverter.Millimeter.toDisplay(rate)))
        assertEquals("11.8110", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = false).format(UnitConverter.Inch.toDisplay(rate)))
    }

    @Test
    fun feed_rate_10000_um_in_2000_ms_gives_300000_um_per_min() {
        val profile = mill()
        val stage = stage(profile, adapter())
        stage.onAxisCount('x', 0L, 1000L)
        stage.onAxisCount('x', 5000L, 2000L)
        stage.onAxisCount('x', 10000L, 3000L)
        assertEquals(300000.0, stage.history.feedRate(AxisId.X, 3000L), 1e-6)
    }

    @Test
    fun rate_is_0_when_the_history_has_no_value_for_a_time() {
        val profile = mill()
        val stage = stage(profile, adapter())
        stage.onAxisCount('x', 10000L, 2000L)
        // No sample at now − 2000 ms.
        assertEquals(0.0, stage.history.feedRate(AxisId.X, 2000L))
    }

    @Test
    fun feed_per_revolution_chip_load_and_thread_at_1200_rpm() {
        val fpr = FeedValues.feedPerRevolution(300000.0, 1200.0)
        assertEquals(250.0, fpr)
        val chipLoad = FeedValues.chipLoad(fpr, FeedValues.teeth(MachineType.VerticalMill, 4))
        assertEquals(62.5, chipLoad)
        assertEquals("0.0025", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = false).format(UnitConverter.Inch.toDisplay(chipLoad)))
        assertEquals("0.063", ReadoutPatterns.linear(ReadoutFormat.Format3, metric = true).format(UnitConverter.Millimeter.toDisplay(chipLoad)))
        assertEquals("0.25", FeedValues.threadPitchText(fpr))
        assertEquals("101.6", FeedValues.threadsPerInchText(fpr))
    }

    @Test
    fun zero_rules_and_lathe_teeth() {
        assertEquals(0.0, FeedValues.feedPerRevolution(300000.0, 0.0))
        assertEquals(0.0, FeedValues.chipLoad(250.0, 0))
        assertEquals(1, FeedValues.teeth(MachineType.Lathe, 4))
        assertEquals(4, FeedValues.teeth(MachineType.VerticalMill, 4))
        // f = rint(0.04 × 10) / 10 = 0: the text is "0.0".
        assertEquals("0.0", FeedValues.threadsPerInchText(0.04))
    }

    @Test
    fun thread_shows_only_on_a_lathe_for_Z() {
        assertTrue(FeedValues.showsThread(MachineType.Lathe, AxisId.Z))
        assertFalse(FeedValues.showsThread(MachineType.Lathe, AxisId.X))
        assertFalse(FeedValues.showsThread(MachineType.VerticalMill, AxisId.Z))
    }
}
