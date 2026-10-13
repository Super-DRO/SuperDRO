package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.superdro.core.functions.errormap.MapBuilderSteps
import org.superdro.core.functions.errormap.MapSample
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.BacklashStage
import org.superdro.core.readout.encoder.DeadZoneBacklash
import org.superdro.core.readout.encoder.EncoderProfileCheck
import org.superdro.core.readout.encoder.EncoderProfileProblem
import org.superdro.core.readout.encoder.EncoderStages
import org.superdro.core.readout.encoder.ErrorCorrection
import org.superdro.core.readout.encoder.LagBacklash
import org.superdro.core.readout.encoder.MapProblem
import org.superdro.core.readout.encoder.MapTable
import org.superdro.core.readout.encoder.MapText
import org.superdro.core.readout.encoder.MapValidation
import org.superdro.core.readout.encoder.SpikeFilter
import org.superdro.core.readout.input.ChannelHistory
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputStage

/**
 * The encoder profile, the channel order, the error map, the
 * backlash models, the spike filter, the input history, and the worked examples.
 */
class EncoderPipelineValidationTest {

    /** The error map of the run time example. */
    private val runTimeMap = listOf(MapPoint(0L, 0L), MapPoint(10000L, 4L), MapPoint(20000L, 9L), MapPoint(30000L, 6L))

    private fun stageWith(vararg encoders: EncoderProfile): InputStage = InputStage(InputConfiguration.from(mill(), adapter(*encoders)))

    private fun InputStage.value(input: Char): Double? = snapshot.channels.getValue(input).value

    private fun InputStage.corrected(input: Char): Long? = snapshot.channels.getValue(input).correctedCount

    // The encoder profile.

    @Test
    fun default_rows_X_to_V_and_T() {
        val encoders = AdapterProfile().encoders
        assertEquals(setOf('x', 'y', 'z', 'w', 'u', 'v', 't'), encoders.keys)
        "xyzwuv".forEach { letter ->
            val profile = encoders.getValue(letter)
            assertEquals(EncoderType.Scale, profile.type, "input $letter")
            assertEquals(EncoderKind.Linear, profile.kind, "input $letter")
            assertEquals(25400.0, profile.resolution, "input $letter")
            assertEquals(BacklashMode.None, profile.backlashMode, "input $letter")
            assertEquals(CalibrationStatus.NotCalibrated, profile.calibration.status, "input $letter")
        }
        val t = encoders.getValue('t')
        assertEquals(EncoderType.RotaryEncoder, t.type)
        assertEquals(EncoderKind.Angular, t.kind)
        assertEquals(1.0, t.resolution)
        assertEquals(BacklashMode.None, t.backlashMode)
    }

    @Test
    fun no_profile_disabled_or_resolution_not_more_than_0_gives_no_value() {
        assertFalse(EncoderProfile.givesValue(null))
        assertFalse(EncoderProfile.givesValue(scale('x', 0.0)))
        assertFalse(EncoderProfile.givesValue(scale('x', -5.0)))
        assertFalse(EncoderProfile.givesValue(scale('x').copy(kind = EncoderKind.Disabled)))
        assertTrue(EncoderProfile.givesValue(scale('x')))
    }

    @Test
    fun scale_factors_of_the_pipeline() {
        val stage = stageWith(scale('x', 5080.0), scale('y', 5080.0).copy(reversed = true), angularEncoder('w', 1440.0))
        stage.onAxisCount('x', 100L, 0L)
        stage.onAxisCount('y', 100L, 0L)
        stage.onAxisCount('w', 100L, 0L)
        stage.onAxisCount('t', 600L, 0L)
        assertEquals(500.0, stage.value('x'))
        assertEquals(-500.0, stage.value('y'))
        assertEquals(90000.0, stage.value('w'))
        assertEquals(600.0, stage.value('t'))
    }

    // The order of the stages.

    @Test
    fun map_works_on_the_raw_count_before_the_direction_sign() {
        val profile = scale('x').copy(reversed = true, errorMap = listOf(MapPoint(0L, 0L), MapPoint(10000L, 4L)))
        val stage = stageWith(profile)
        stage.onAxisCount('x', 5000L, 0L)
        assertEquals(5002L, stage.corrected('x'))
        assertEquals(-5002.0, stage.value('x'))
    }

    @Test
    fun first_count_after_a_connection_has_d_0() {
        val profile = scale('x').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val stage = stageWith(profile)
        stage.onAxisCount('x', 1000L, 0L)
        // d = 0: the LAG state starts as "down", and the first output is x.
        assertEquals(1000.0, stage.value('x'))
        stage.onAxisCount('x', 1010L, 10L)
        assertEquals(1013.0, stage.value('x'))
        stage.onLinkStart()
        stage.onAxisCount('x', 1020L, 20L)
        assertEquals(1020.0, stage.value('x'))
    }

    @Test
    fun sample_with_an_earlier_time_is_dropped() {
        val stage = stageWith(scale('x'))
        stage.onAxisCount('x', 100L, 10L)
        stage.onAxisCount('x', 200L, 5L)
        assertEquals(100.0, stage.value('x'))
        assertEquals(100L, stage.snapshot.channels.getValue('x').rawCount)
    }

    // Worked example: the run time table (map and LAG, B = 3).

    @Test
    fun run_time_table_with_the_stages() {
        val map = ErrorCorrection(MapTable.of(runTimeMap))
        val lag = LagBacklash(3L)
        // Before sample 1, the LAG state is "up" and k_last = 14996.
        lag.apply(14996L, -1L)
        assertTrue(lag.up)
        val rows = listOf(
            // raw, d, E, output
            listOf(15000L, -10L, 15006L, 15009L),
            listOf(14998L, 2L, 15004L, 15007L),
            listOf(14994L, 4L, 15000L, 15000L),
            listOf(35000L, -20006L, 35006L, 35009L),
            listOf(-500L, 35500L, -500L, -500L),
        )
        rows.forEachIndexed { index, (raw, d, e, output) ->
            val corrected = map.apply(raw)
            assertEquals(e, corrected, "sample ${index + 1}: E")
            assertEquals(output, lag.apply(corrected, d), "sample ${index + 1}: output")
        }
    }

    @Test
    fun rint_rounds_2_5_to_2() {
        // Sample 1: frac 0.5, delta 5 × 0.5 = 2.5, rint 2.
        assertEquals(6L, ErrorCorrection(MapTable.of(runTimeMap)).correction(15000L))
    }

    @Test
    fun run_time_table_through_the_input_stage_gives_15009_15007_15000_35009_minus_500() {
        val profile = scale('x').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L, errorMap = runTimeMap)
        val stage = stageWith(profile)
        // The previous raw count of sample 1 is 14990.
        stage.onAxisCount('x', 14990L, 0L)
        val values = listOf(15000L, 14998L, 14994L, 35000L, -500L).mapIndexed { index, raw ->
            stage.onAxisCount('x', raw, 10L * (index + 1))
            stage.value('x')
        }
        assertEquals(listOf(15009.0, 15007.0, 15000.0, 35009.0, -500.0), values)
    }

    // Worked example: the leadscrew table (DEAD_ZONE, B = 30).

    @Test
    fun leadscrew_table_gives_1000_1070_1050_1050_1070() {
        val stage = DeadZoneBacklash.constant(30L)
        val outputs = listOf(1000L, 1100L, 1050L, 1060L, 1100L).map { raw -> stage.apply(raw, 0L) }
        assertEquals(listOf(1000L, 1070L, 1050L, 1050L, 1070L), outputs)
    }

    @Test
    fun leadscrew_table_through_the_input_stage() {
        val profile = EncoderProfile(input = 'z', type = EncoderType.LeadscrewEncoder, kind = EncoderKind.Linear, resolution = 25400.0)
            .copy(backlashMode = BacklashMode.Constant, backlashCounts = 30L)
        val stage = stageWith(profile)
        val values = listOf(1000L, 1100L, 1050L, 1060L, 1100L).mapIndexed { index, raw ->
            stage.onAxisCount('z', raw, 10L * index)
            stage.value('z')
        }
        assertEquals(listOf(1000.0, 1070.0, 1050.0, 1050.0, 1070.0), values)
    }

    @Test
    fun dead_zone_B_10_example() {
        // Counts 0, 15 give 0, 5. Counts 10, 6 give 5, 5. Count 0 gives 0.
        val stage = DeadZoneBacklash.constant(10L)
        assertEquals(listOf(0L, 5L, 5L, 5L, 0L), listOf(0L, 15L, 10L, 6L, 0L).map { raw -> stage.apply(raw, 0L) })
    }

    @Test
    fun lag_changes_the_state_only_after_more_than_4_counts_back() {
        // J = 3, up. Counts 100, 101, 102 give 103, 104, 105. Counts 101, 100,
        // 99, 98 give 104, 103, 102, 101. Count 97 gives 97.
        val lag = LagBacklash(3L)
        val outputs = listOf(100L to -1L, 101L to -1L, 102L to -1L, 101L to 1L, 100L to 1L, 99L to 1L, 98L to 1L, 97L to 1L)
            .map { (x, d) -> lag.apply(x, d) }
        assertEquals(listOf(103L, 104L, 105L, 104L, 103L, 102L, 101L, 97L), outputs)
    }

    @Test
    fun model_from_the_encoder_type() {
        val constant = scale('x').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        assertIs<LagBacklash>(BacklashStage.of(constant))
        assertIs<LagBacklash>(BacklashStage.of(constant.copy(type = EncoderType.RotaryEncoder)))
        assertIs<LagBacklash>(BacklashStage.of(constant.copy(type = EncoderType.RackPinion)))
        assertIs<DeadZoneBacklash>(BacklashStage.of(constant.copy(type = EncoderType.LeadscrewEncoder)))
        assertEquals(BacklashStage.None, BacklashStage.of(scale('x')))
    }

    // Worked example: the pin.

    @Test
    fun pin_for_targets_0_to_200000_is_100000() {
        assertEquals(100000.0, MapBuilderSteps.pin(listOf(0.0, 50000.0, 100000.0, 150000.0, 200000.0)))
    }

    @Test
    fun pin_for_targets_minus_50000_to_150000_is_0() {
        assertEquals(0.0, MapBuilderSteps.pin(listOf(-50000.0, 0.0, 50000.0, 150000.0)))
    }

    // Worked example: the node fit with K = 2.

    @Test
    fun node_fit_with_K_2() {
        val samples = listOf(
            MapSample(100.0, 3.0), MapSample(110.0, 5.0), MapSample(200.0, 4.0),
            MapSample(210.0, 2.0), MapSample(300.0, -1.0), MapSample(310.0, 1.0),
        )
        assertEquals(listOf(MapPoint(105L, 4L), MapPoint(205L, 3L), MapPoint(305L, 0L)), MapBuilderSteps.fitNodes(2, samples))
    }

    // The error map lookup.

    @Test
    fun lookup_rounds_half_to_even_and_is_flat_outside_the_map() {
        assertEquals(4L, MapTable.of(listOf(MapPoint(0L, 0L), MapPoint(10L, 7L))).lookup(5L))
        assertEquals(2L, MapTable.of(listOf(MapPoint(0L, 0L), MapPoint(10L, 5L))).lookup(5L))
        assertEquals(-2L, MapTable.of(listOf(MapPoint(0L, 0L), MapPoint(10L, -5L))).lookup(5L))
        val table = MapTable.of(runTimeMap)
        assertEquals(0L, table.lookup(-500L))
        assertEquals(6L, table.lookup(35000L))
        assertEquals(4L, table.lookup(10000L))
    }

    @Test
    fun one_point_is_a_constant_and_an_empty_map_is_0() {
        val one = MapTable.of(listOf(MapPoint(500L, 7L)))
        assertEquals(7L, one.lookup(-100000L))
        assertEquals(7L, one.lookup(100000L))
        assertEquals(0L, MapTable.of(emptyList()).lookup(1234L))
    }

    @Test
    fun unsorted_points_are_sorted_and_the_first_point_of_a_position_stays() {
        val table = MapTable.of(listOf(MapPoint(100L, 10L), MapPoint(0L, 0L), MapPoint(100L, 50L)))
        assertEquals(10L, table.lookup(100L))
        assertEquals(5L, table.lookup(50L))
    }

    @Test
    fun more_than_20000_points_give_no_map() {
        val points = (0 until 20001).map { index -> MapPoint(index.toLong(), 1L) }
        assertTrue(MapTable.of(points).isEmpty)
        assertEquals(0L, MapTable.of(points).lookup(10L))
        assertEquals(1L, MapTable.of(points.take(20000)).lookup(10L))
    }

    // The cyclic switch.

    private val cyclicMap = listOf(MapPoint(0L, 0L), MapPoint(20L, 40L), MapPoint(980L, -40L), MapPoint(1000L, 0L))

    @Test
    fun cyclic_map_position_N_plus_10_gives_the_correction_of_10_and_minus_10_gives_N_minus_10() {
        val wrap = ErrorCorrection(MapTable.of(cyclicMap), cyclicCounts = 1000.0)
        assertEquals(wrap.correction(10L), wrap.correction(1010L))
        assertEquals(wrap.correction(990L), wrap.correction(-10L))
        assertEquals(20L, wrap.correction(1010L))
        assertEquals(-20L, wrap.correction(-10L))
    }

    @Test
    fun cyclic_switch_is_on_by_default_and_works_through_the_input_stage() {
        assertTrue(EncoderProfile().errorMapCyclic)
        val angular = angularEncoder('w', 1000.0).copy(errorMap = cyclicMap)
        val stage = stageWith(angular)
        stage.onAxisCount('w', 1010L, 0L)
        assertEquals(1030L, stage.corrected('w'))
        stage.onAxisCount('w', -10L, 10L)
        assertEquals(-30L, stage.corrected('w'))
    }

    @Test
    fun cyclic_switch_off_and_a_linear_input_do_not_wrap() {
        val off = EncoderStages(angularEncoder('w', 1000.0).copy(errorMap = cyclicMap, errorMapCyclic = false))
        assertEquals(1010L, off.errorCorrection.apply(1010L))
        assertEquals(-10L, off.errorCorrection.apply(-10L))
        val linear = EncoderStages(scale('x', 1000.0).copy(errorMap = cyclicMap))
        assertEquals(1010L, linear.errorCorrection.apply(1010L))
    }

    // The map text grammar and the validation.

    @Test
    fun text_grammar_comments_header_separators_and_line_ends() {
        val text = "# TouchDRO error correction map\nposition,correction\n100, 5\n200\t7\n300  9\r\n400,10\r500,11"
        val parse = MapText.parse(text)
        assertEquals(
            listOf(MapPoint(100L, 5L), MapPoint(200L, 7L), MapPoint(300L, 9L), MapPoint(400L, 10L), MapPoint(500L, 11L)),
            parse.points,
        )
        assertEquals(listOf("TouchDRO error correction map"), parse.comments)
        assertTrue(parse.malformedLines.isEmpty())
        assertFalse(parse.exceededLimit)
    }

    @Test
    fun up_to_5_header_lines_before_the_first_point() {
        val headers = (1..6).joinToString("\n") { index -> "head$index name$index" }
        val parse = MapText.parse("$headers\n0,0\nfoo bar\n")
        assertEquals(listOf(6, 8), parse.malformedLines)
        assertEquals(listOf(MapPoint(0L, 0L)), parse.points)
    }

    @Test
    fun line_numbers_count_the_blank_lines_and_decimals_are_flagged() {
        val parse = MapText.parse("\n\n1.5,2\n10,1\n")
        assertEquals(listOf(3), parse.malformedLines)
        assertTrue(parse.sawDecimalValues)
    }

    @Test
    fun text_with_more_than_20000_points_gives_no_points() {
        val ok = (0 until 20000).joinToString("\n") { index -> "$index,0" }
        assertEquals(20000, MapText.parse(ok).points.size)
        val tooMany = (0 until 20001).joinToString("\n") { index -> "$index,0" }
        val parse = MapText.parse(tooMany)
        assertTrue(parse.exceededLimit)
        assertTrue(parse.points.isEmpty())
        assertTrue(MapValidation.checkErrorMapText(tooMany).problems.any { problem -> problem is MapProblem.TooManyPoints })
    }

    @Test
    fun validation_gives_a_problem_list() {
        val malformed = MapValidation.checkErrorMapText("position,correction\n0,0\nabc def ghi\n10000,4\n")
        assertFalse(malformed.valid)
        assertEquals(listOf<MapProblem>(MapProblem.MalformedLine(3)), malformed.problems)
        assertTrue(MapValidation.checkErrorMapText("0,0\n10000,4\n").valid)
        assertTrue(MapValidation.checkErrorMapText("").valid)
        assertEquals(listOf<MapProblem>(MapProblem.NoPoints), MapValidation.checkErrorMapText("# only a comment\n").problems)
    }

    @Test
    fun duplicate_reversal_and_dead_band() {
        assertEquals(
            listOf<MapProblem>(MapProblem.DuplicatePosition(0L)),
            MapValidation.errorMapProblems(listOf(MapPoint(0L, 0L), MapPoint(0L, 5L), MapPoint(100L, -200L))),
        )
        assertEquals(
            listOf<MapProblem>(MapProblem.Reversal(0L, 100L)),
            MapValidation.errorMapProblems(listOf(MapPoint(100L, -150L), MapPoint(0L, 0L))),
        )
        assertEquals(
            listOf<MapProblem>(MapProblem.DeadBand(0L, 100L)),
            MapValidation.errorMapProblems(listOf(MapPoint(0L, 0L), MapPoint(100L, -100L))),
        )
        assertTrue(MapValidation.errorMapProblems(runTimeMap).isEmpty())
    }

    @Test
    fun backlash_map_negative_value_and_value_not_less_than_one_screw_turn() {
        val problems = MapValidation.backlashMapProblems(listOf(MapPoint(0L, -1L), MapPoint(100L, 400L), MapPoint(200L, 30L)), 400.0)
        assertEquals(listOf(MapProblem.NegativeBacklash(0L, -1L), MapProblem.BacklashExceedsScrewTurn(100L, 400L, 400L)), problems)
    }

    @Test
    fun backlash_map_mode_on_a_scale_is_refused() {
        val scaleWithMap = scale('x').copy(backlashMode = BacklashMode.Map, backlashMap = listOf(MapPoint(0L, 5L)))
        assertTrue(EncoderProfileCheck.problems(scaleWithMap).contains(EncoderProfileProblem.BacklashMapNotAllowed))
        val leadscrew = scaleWithMap.copy(type = EncoderType.LeadscrewEncoder)
        assertFalse(EncoderProfileCheck.problems(leadscrew).contains(EncoderProfileProblem.BacklashMapNotAllowed))
    }

    // The spike filter.

    @Test
    fun threshold_J_is_max_1_and_int_resolution_times_0_25() {
        assertEquals(6350L, SpikeFilter.thresholdFor(25400.0))
        assertEquals(1270L, SpikeFilter.thresholdFor(5080.0))
        assertEquals(250L, SpikeFilter.thresholdFor(1000.0))
        assertEquals(1L, SpikeFilter.thresholdFor(1.0))
    }

    @Test
    fun resolution_5080_example() {
        // Counts 1000, 1001, 1002, 1003 give 1000, 1000, 1002, 1002. A spike
        // 1003, 5000, 1004 gives 1002, 1002 (held), 1004.
        val filter = SpikeFilter(SpikeFilter.thresholdFor(5080.0))
        val outputs = listOf(1000L, 1001L, 1002L, 1003L, 5000L, 1004L).map { count -> filter.filter(count) }
        assertEquals(listOf(1000L, 1000L, 1002L, 1002L, 1002L, 1004L), outputs)
    }

    @Test
    fun one_jump_is_held_for_one_sample_and_the_next_sample_passes() {
        val jump = SpikeFilter(6350L)
        assertEquals(listOf(0L, 0L, 10000L), listOf(0L, 10000L, 10000L).map { count -> jump.filter(count) })
        val spike = SpikeFilter(6350L)
        assertEquals(listOf(0L, 0L, 0L), listOf(0L, 10000L, 0L).map { count -> spike.filter(count) })
    }

    @Test
    fun plus_or_minus_1_count_does_not_change_the_output() {
        val filter = SpikeFilter(6350L)
        assertEquals(listOf(1000L, 1000L, 1000L, 1000L), listOf(1000L, 1001L, 999L, 1000L).map { count -> filter.filter(count) })
    }

    @Test
    fun held_spike_forces_the_next_sample_through_the_stages() {
        val stage = stageWith(scale('x').copy(filter = true))
        stage.onAxisCount('x', 0L, 0L)
        stage.onAxisCount('x', 10000L, 10L)
        assertEquals(0.0, stage.value('x'))
        // The same raw count: d = 0, but the filter holds a spike.
        stage.onAxisCount('x', 10000L, 20L)
        assertEquals(10000.0, stage.value('x'))
    }

    // The input history.

    @Test
    fun history_stores_the_count_after_the_backlash() {
        val stage = stageWith(scale('x').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L))
        stage.onAxisCount('x', 0L, 0L)
        stage.onAxisCount('x', 100L, 10L)
        assertEquals(103L, stage.history.channel('x')?.newestCount)
        assertEquals(103.0, stage.history.positionAt('x', 10L))
    }

    @Test
    fun history_keeps_the_last_2048_pairs() {
        val history = ChannelHistory()
        assertEquals(2048, history.capacity)
        (0 until 2049).forEach { index -> history.add(index.toLong(), index.toLong()) }
        assertEquals(2048, history.size)
        assertEquals(1L, history.timeAt(0))
        assertTrue(history.countAt(0L).isNaN())
    }

    @Test
    fun position_at_a_time() {
        val history = ChannelHistory()
        assertTrue(history.countAt(0L).isNaN())
        history.add(1000L, 100L)
        history.add(1200L, 300L)
        // Before the oldest sample.
        assertTrue(history.countAt(999L).isNaN())
        // Between two samples: the linear interpolation.
        assertEquals(150.0, history.countAt(1050L))
        // At or after the newest sample by 500 ms or less.
        assertEquals(300.0, history.countAt(1700L))
        // More than 500 ms after the newest sample, not at rest.
        assertTrue(history.countAt(1701L).isNaN())
        // The same time replaces the newest sample.
        history.add(1200L, 310L)
        assertEquals(310.0, history.countAt(1200L))
    }

    @Test
    fun more_than_500_ms_after_the_newest_sample_at_rest_gives_the_newest_count() {
        val history = ChannelHistory()
        history.add(0L, 501L)
        history.add(400L, 499L)
        history.add(1000L, 500L)
        assertEquals(500.0, history.countAt(5000L))
        val moving = ChannelHistory()
        moving.add(0L, 490L)
        moving.add(1000L, 500L)
        assertTrue(moving.countAt(5000L).isNaN())
    }

    @Test
    fun samples_more_than_500_ms_apart_with_different_counts_give_NaN() {
        val history = ChannelHistory()
        history.add(0L, 0L)
        history.add(600L, 100L)
        assertTrue(history.countAt(300L).isNaN())
        val same = ChannelHistory()
        same.add(0L, 100L)
        same.add(600L, 100L)
        assertEquals(100.0, same.countAt(300L))
    }

    @Test
    fun speed_at_a_given_time() {
        val stage = stageWith(scale('x', 5080.0))
        var time = 0L
        while (time <= 2000L) {
            // 1 count each ms = 5 µm each ms.
            stage.onAxisCount('x', time, time)
            time += 100L
        }
        assertEquals(5000.0, stage.history.speed('x', 2000L, 1000L), 1e-9)
        assertEquals(5000.0, stage.history.axisSpeed(AxisId.X, 1500L, 1000L), 1e-9)
        assertEquals(7500.0, stage.history.positionAt('x', 1500L), 1e-9)
    }

    // A settings change takes effect at once.

    @Test
    fun change_takes_effect_at_once_and_only_the_changed_channel_starts_again() {
        val profile = mill()
        val lagY = scale('y').copy(backlashMode = BacklashMode.Constant, backlashCounts = 3L)
        val stage = InputStage(InputConfiguration.from(profile, adapter(scale('x'), lagY)))
        stage.onAxisCount('x', 1000L, 0L)
        stage.onAxisCount('y', 0L, 0L)
        stage.onAxisCount('y', 100L, 10L)
        assertEquals(103.0, stage.r(AxisId.Y))
        stage.reconfigure(InputConfiguration.from(profile, adapter(scale('x', 12700.0), lagY)))
        // The new resolution applies with no new sample.
        assertEquals(2000.0, stage.r(AxisId.X))
        // Y keeps its LAG state "up": a reversal of 1 count keeps + B.
        stage.onAxisCount('y', 99L, 20L)
        assertEquals(102.0, stage.r(AxisId.Y))
    }
}
