package org.superdro.validation.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.probe.Approach
import org.superdro.core.functions.probe.AxisCommand
import org.superdro.core.functions.probe.AxisHistory
import org.superdro.core.functions.probe.CoordinateMode
import org.superdro.core.functions.probe.Edge
import org.superdro.core.functions.probe.EdgeFinder
import org.superdro.core.functions.probe.EdgeFinderSettings
import org.superdro.core.functions.probe.EdgeFinderState
import org.superdro.core.functions.probe.EdgeOrigins
import org.superdro.core.functions.probe.EdgeSide
import org.superdro.core.functions.probe.EdgeStatus
import org.superdro.core.functions.probe.FeatureType
import org.superdro.core.functions.probe.OriginOption
import org.superdro.core.functions.probe.PositionSample
import org.superdro.core.functions.probe.ProbeTechnique
import org.superdro.core.functions.probe.ProbeTrigger
import org.superdro.core.functions.probe.SampleSet
import org.superdro.core.functions.tipcal.SpreadLimits
import org.superdro.core.functions.tipcal.Touch
import org.superdro.core.functions.tipcal.TouchStatistics
import org.superdro.core.functions.zreference.ZReference
import org.superdro.core.functions.zreference.ZReferenceResult
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ReadoutFormat
import org.superdro.validation.functions.Expect.MM

/**
 * The edge finder and the Z reference.
 */
class EdgeFinderValidationTest {

    private val probeOn = EdgeFinderSettings(probeOn = true, sampleCount = 1)

    /** A history that moves [axis] in the sign [sign] by 20 µm in the 400 ms before the time [t]. */
    private fun moving(sign: Int, t: Long = 10_000L, end: Double = 0.0): AxisHistory = AxisHistory(
        listOf(
            PositionSample(t - 400, end - sign * 20.0),
            PositionSample(t - 200, end - sign * 10.0),
            PositionSample(t, end),
        ),
    )

    private fun still(t: Long = 10_000L, at: Double = 0.0): AxisHistory = AxisHistory(
        listOf(PositionSample(t - 400, at), PositionSample(t - 200, at), PositionSample(t, at)),
    )

    private fun trigger(latched: Map<AxisId, Double>, histories: Map<AxisId, AxisHistory>, t: Long = 10_000L) =
        ProbeTrigger(t, latched, histories)

    @Test
    fun the_three_origin_formulas_external_and_internal() {
        val a = 10 * MM
        val b = 30 * MM
        val r = 1 * MM
        assertEquals(11 * MM, EdgeOrigins.origin(OriginOption.SideA, a, b, FeatureType.External, r))
        assertEquals(20 * MM, EdgeOrigins.origin(OriginOption.Center, a, b, FeatureType.External, r))
        assertEquals(29 * MM, EdgeOrigins.origin(OriginOption.SideB, a, b, FeatureType.External, r))
        assertEquals(9 * MM, EdgeOrigins.origin(OriginOption.SideA, a, b, FeatureType.Internal, r))
        assertEquals(20 * MM, EdgeOrigins.origin(OriginOption.Center, a, b, FeatureType.Internal, r))
        assertEquals(31 * MM, EdgeOrigins.origin(OriginOption.SideB, a, b, FeatureType.Internal, r))
    }

    @Test
    fun one_edge_enables_its_side_and_two_edges_select_center() {
        assertEquals(setOf(OriginOption.SideA), EdgeOrigins.enabledOptions(1.0, null))
        assertEquals(OriginOption.SideA, EdgeOrigins.autoOption(1.0, null))
        assertEquals(setOf(OriginOption.SideB), EdgeOrigins.enabledOptions(null, 1.0))
        assertEquals(OriginOption.SideB, EdgeOrigins.autoOption(null, 1.0))
        assertEquals(OriginOption.entries.toSet(), EdgeOrigins.enabledOptions(1.0, 2.0))
        assertEquals(OriginOption.Center, EdgeOrigins.autoOption(1.0, 2.0))
        assertEquals(emptySet(), EdgeOrigins.enabledOptions(null, null))
    }

    @Test
    fun spread_is_max_minus_min_of_the_samples() {
        val samples = SampleSet().add(0.0).add(50.0).add(0.0)
        assertEquals(50.0, samples.spreadUm, "the spread is max − min, not |first − last|")
        assertEquals(50.0 / 3.0, samples.meanUm!!, 1e-9)
        assertEquals(50.0, SpreadLimits.sampleSpreadUm(listOf(0.0, 50.0, 0.0)))
    }

    @Test
    fun single_edge_sampler_mean_offset_spread_message_and_sounds() {
        val settings = EdgeFinderSettings(probeOn = true, sampleCount = 3, spreadLimitUm = 20.0, xOffsetUm = 100.0)
        var state = EdgeFinder.tapEdge(EdgeFinderState(settings = settings), EdgeSide.Left, 0.0, probeClosed = false).state
        assertEquals(EdgeStatus.Scanning, state.edge(EdgeSide.Left).status)
        val sounds = mutableListOf<FunctionSound>()
        for (x in listOf(0.0, 50.0, 0.0)) {
            val step = EdgeFinder.onTrigger(state, trigger(mapOf(AxisId.X to x), emptyMap()))
            sounds += step.sounds
            state = step.state
        }
        assertEquals(listOf(FunctionSound.Short, FunctionSound.Short, FunctionSound.Long), sounds)
        val edge = state.edge(EdgeSide.Left)
        assertEquals(EdgeStatus.Located, edge.status)
        assertEquals(50.0 / 3.0 + 100.0, edge.valueUm!!, 1e-9, "edge value = mean + offset")
        assertEquals("Warning: maximum spread between the samples is 0.050 mm", state.message?.text)
    }

    @Test
    fun spread_message_in_inch_and_no_message_at_the_limit() {
        val inch = EdgeFinderSettings(probeOn = true, sampleCount = 2, spreadLimitUm = 20.0, metricDisplay = false, readoutFormat = ReadoutFormat.Format3)
        var state = EdgeFinder.tapEdge(EdgeFinderState(settings = inch), EdgeSide.Near, 0.0, false).state
        state = EdgeFinder.onTrigger(state, trigger(mapOf(AxisId.Y to 0.0), emptyMap())).state
        state = EdgeFinder.onTrigger(state, trigger(mapOf(AxisId.Y to 50.0), emptyMap())).state
        assertEquals("Warning: maximum spread between the samples is 0.0020 in.", state.message?.text)
        var atLimit = EdgeFinder.tapEdge(EdgeFinderState(settings = inch), EdgeSide.Near, 0.0, false).state
        atLimit = EdgeFinder.onTrigger(atLimit, trigger(mapOf(AxisId.Y to 0.0), emptyMap())).state
        atLimit = EdgeFinder.onTrigger(atLimit, trigger(mapOf(AxisId.Y to 20.0), emptyMap())).state
        assertNull(atLimit.message, "a spread equal to the limit gives no warning")
    }

    @Test
    fun manual_capture_tap_long_press_and_the_probe_closed_message() {
        val off = EdgeFinderState(settings = EdgeFinderSettings(probeOn = false, xOffsetUm = 100.0))
        val tapped = EdgeFinder.tapEdge(off, EdgeSide.Right, 12345.0, probeClosed = false).state
        assertEquals(Edge(EdgeStatus.Located, 12345.0), tapped.edge(EdgeSide.Right), "a manual capture adds no offset")
        val on = EdgeFinderState(settings = probeOn)
        val closed = EdgeFinder.tapEdge(on, EdgeSide.Left, 0.0, probeClosed = true).state
        assertEquals("Probe is in triggered state. Please move the probe away from the workpiece.", closed.message?.text)
        assertEquals(EdgeStatus.Unknown, closed.edge(EdgeSide.Left).status)
        val captured = EdgeFinder.longPressEdge(on, EdgeSide.Far, 777.0).state
        assertEquals(Edge(EdgeStatus.Located, 777.0), captured.edge(EdgeSide.Far))
        val cleared = EdgeFinder.longPressEdge(captured, EdgeSide.Far, 0.0).state
        assertEquals(EdgeStatus.Unknown, cleared.edge(EdgeSide.Far).status)
    }

    @Test
    fun set_origin_absolute_and_incremental_apply_P_minus_O_one_time() {
        val edges = EdgeSide.entries.associateWith { Edge() } +
            (EdgeSide.Left to Edge(EdgeStatus.Located, 10 * MM)) + (EdgeSide.Right to Edge(EdgeStatus.Located, 30 * MM))
        val state = EdgeFinderState(settings = probeOn, edges = edges, xOption = OriginOption.Center)
        val absolute = EdgeFinder.setOrigin(state, mapOf(AxisId.X to 50 * MM, AxisId.Y to 0.0))
        assertEquals(listOf(AxisCommand(AxisId.X, 30 * MM, CoordinateMode.Absolute)), absolute.commands)
        assertTrue(absolute.close)
        val incremental = EdgeFinder.setOrigin(state.copy(mode = CoordinateMode.Incremental), mapOf(AxisId.X to 50 * MM))
        assertEquals(listOf(AxisCommand(AxisId.X, 30 * MM, CoordinateMode.Incremental)), incremental.commands, "the value is not halved")
        assertNull(absolute.state.auto)
        assertNull(absolute.state.sampler)
    }

    @Test
    fun add_sub_datum_uses_the_origins_or_the_current_positions_and_the_current_z() {
        val edges = EdgeSide.entries.associateWith { Edge() } + (EdgeSide.Left to Edge(EdgeStatus.Located, 10 * MM))
        val state = EdgeFinderState(settings = probeOn, edges = edges, xOption = OriginOption.SideA)
        val current = mapOf(AxisId.X to 50 * MM, AxisId.Y to 7 * MM, AxisId.Z to -1 * MM)
        val tap = EdgeFinder.addSubDatum(state, current, zEnabled = true, use3dSubDatums = true, keepEdges = false)
        assertEquals(10 * MM, tap.point.x)
        assertEquals(7 * MM, tap.point.y)
        assertEquals(-1 * MM, tap.point.z)
        assertTrue(EdgeSide.entries.all { tap.state.edge(it).status == EdgeStatus.Unknown }, "a tap clears the edges")
        val longPress = EdgeFinder.addSubDatum(state, current, zEnabled = true, use3dSubDatums = false, keepEdges = true)
        assertNull(longPress.point.z, "no Z when 3D sub-datums are off")
        assertEquals(EdgeStatus.Located, longPress.state.edge(EdgeSide.Left).status, "a long press keeps the edges")
    }

    @Test
    fun direction_to_edge_table() {
        assertEquals(EdgeSide.Left, Approach.XPositive.edge(FeatureType.External))
        assertEquals(EdgeSide.Right, Approach.XPositive.edge(FeatureType.Internal))
        assertEquals(EdgeSide.Right, Approach.XNegative.edge(FeatureType.External))
        assertEquals(EdgeSide.Left, Approach.XNegative.edge(FeatureType.Internal))
        assertEquals(EdgeSide.Near, Approach.YPositive.edge(FeatureType.External))
        assertEquals(EdgeSide.Far, Approach.YPositive.edge(FeatureType.Internal))
        assertEquals(EdgeSide.Far, Approach.YNegative.edge(FeatureType.External))
        assertEquals(EdgeSide.Near, Approach.YNegative.edge(FeatureType.Internal))
    }

    @Test
    fun auto_direction_is_the_first_difference_of_5_um_in_the_1000_ms_before_t() {
        val t = 50_000_000L
        assertEquals(1, moving(+1, t).direction(t))
        assertEquals(-1, moving(-1, t).direction(t))
        assertEquals(0, still(t).direction(t))
        val small = AxisHistory(listOf(PositionSample(t - 500, 0.0), PositionSample(t, 4.9)))
        assertEquals(0, small.direction(t), "a motion of less than 5 µm is no motion")
        val old = AxisHistory(listOf(PositionSample(t - 1500, 0.0), PositionSample(t - 1100, 100.0), PositionSample(t, 100.0)))
        assertEquals(0, old.direction(t), "a motion older than 1000 ms is no motion")
    }

    @Test
    fun auto_locates_x_and_y_edges_and_stops_after_the_fourth_edge() {
        var state = EdgeFinder.toggleAuto(EdgeFinderState(settings = probeOn), probeClosed = false).state
        assertNotNull(state.auto)
        val runs = listOf(
            Triple(AxisId.Y, +1, EdgeSide.Near),
            Triple(AxisId.Y, -1, EdgeSide.Far),
            Triple(AxisId.X, +1, EdgeSide.Left),
            Triple(AxisId.X, -1, EdgeSide.Right),
        )
        for ((index, run) in runs.withIndex()) {
            val (axis, sign, side) = run
            val other = if (axis == AxisId.X) AxisId.Y else AxisId.X
            val step = EdgeFinder.onTrigger(
                state,
                trigger(mapOf(axis to 1000.0 * (index + 1), other to 0.0), mapOf(axis to moving(sign), other to still(), AxisId.Z to still())),
            )
            state = step.state
            assertEquals(EdgeStatus.Located, state.edge(side).status, "edge $side")
        }
        assertNull(state.auto, "AUTO stops when the fourth edge is located")
        // A new start and a stop leave no AUTO state.
        val restarted = EdgeFinder.toggleAuto(state, false).state
        assertNotNull(restarted.auto)
        assertNull(EdgeFinder.toggleAuto(restarted, false).state.auto)
    }

    private fun autoState(sampleCount: Int = 2): EdgeFinderState =
        EdgeFinder.toggleAuto(EdgeFinderState(settings = EdgeFinderSettings(probeOn = true, sampleCount = sampleCount)), false).state

    @Test
    fun auto_messages_in_the_order_of_the_checks() {
        val start = autoState()
        fun message(latched: Map<AxisId, Double>, histories: Map<AxisId, AxisHistory>): Pair<String?, List<FunctionSound>> {
            val step = EdgeFinder.onTrigger(start, trigger(latched, histories))
            assertNotNull(step.state.auto, "AUTO continues")
            return step.state.message?.text to step.sounds
        }
        val all = mapOf(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        assertEquals(
            "No axis was moving when the probe triggered." to listOf(FunctionSound.Error),
            message(all, mapOf(AxisId.X to still(), AxisId.Y to still(), AxisId.Z to still())),
        )
        assertEquals(
            "Please move one axis at a time" to listOf(FunctionSound.Error),
            message(all, mapOf(AxisId.X to moving(1), AxisId.Y to moving(-1), AxisId.Z to still())),
        )
        assertEquals(
            "Z axis probing is not supported yet" to listOf(FunctionSound.Error),
            message(all, mapOf(AxisId.X to still(), AxisId.Y to still(), AxisId.Z to moving(-1))),
        )
        assertEquals(
            "The axis position at the trigger is unknown. Try again." to listOf(FunctionSound.Error),
            message(mapOf(AxisId.Y to 0.0), mapOf(AxisId.X to moving(1), AxisId.Y to still(), AxisId.Z to still())),
        )
    }

    @Test
    fun change_of_direction_and_no_motion_has_priority_over_the_locked_direction() {
        var state = autoState(sampleCount = 3)
        state = EdgeFinder.onTrigger(state, trigger(mapOf(AxisId.X to 0.0), mapOf(AxisId.X to moving(1)))).state
        assertEquals(Approach.XPositive, state.auto?.locked)
        assertEquals(EdgeStatus.Scanning, state.edge(EdgeSide.Left).status)
        val changed = EdgeFinder.onTrigger(state, trigger(mapOf(AxisId.X to 0.0), mapOf(AxisId.X to moving(-1))))
        assertEquals("Change in direction detected during probe sampling", changed.state.message?.text)
        assertEquals(listOf(FunctionSound.Error), changed.sounds)
        assertEquals(1, changed.state.auto?.samples?.count, "AUTO keeps its samples")
        val stillAfterFirst = EdgeFinder.onTrigger(state, trigger(mapOf(AxisId.X to 0.0), mapOf(AxisId.X to still())))
        assertEquals("No axis was moving when the probe triggered.", stillAfterFirst.state.message?.text)
    }

    @Test
    fun one_offset_rule_for_the_single_edge_sampler_and_auto() {
        val profile = ProbeProfile(type = ProbeType.TouchProbe, xOffsetUm = 100.0, yOffsetUm = -40.0, stylusDiameterUm = 2 * MM)
        val probe = ProbeConfig(enabled = true, edgeFinderEnabled = true)
        val settings = EdgeFinderSettings.from(probe, profile, zEnabled = true, metricDisplay = true, instrumentOffsetXUm = 30.0)
        assertEquals(70.0, settings.xOffsetUm, "offset = profile offset − H")
        assertEquals(-40.0, settings.yOffsetUm)
        assertEquals(1 * MM, settings.stylusRadiusUm)
        val single = EdgeFinder.onTrigger(
            EdgeFinder.tapEdge(EdgeFinderState(settings = settings), EdgeSide.Left, 0.0, false).state,
            trigger(mapOf(AxisId.X to 5000.0), emptyMap()),
        ).state.edge(EdgeSide.Left).valueUm
        val auto = EdgeFinder.onTrigger(
            EdgeFinder.toggleAuto(EdgeFinderState(settings = settings), false).state,
            trigger(mapOf(AxisId.X to 5000.0), mapOf(AxisId.X to moving(1))),
        ).state.edge(EdgeSide.Left).valueUm
        assertEquals(5070.0, single)
        assertEquals(single, auto, "AUTO and the single-edge path give the same value")
    }

    @Test
    fun spread_limit_rule_has_one_implementation() {
        val cases = listOf(
            ProbeProfile(spreadLimitUm = 15.0) to 15.0,
            ProbeProfile(spreadLimitUm = null, calibrationResidualUm = 2.0) to 10.0,
            ProbeProfile(spreadLimitUm = null, calibrationResidualUm = 5.0) to 15.0,
            ProbeProfile(spreadLimitUm = null, calibrationResidualUm = null) to 20.0,
            null to 20.0,
        )
        for ((profile, expected) in cases) {
            assertEquals(expected, SpreadLimits.of(profile).valueUm, "SpreadLimits, $profile")
        }
        assertEquals(1, ProbeTechnique.sampleCount(ProbeProfile(sampleCount = 0)))
        assertEquals(10, ProbeTechnique.sampleCount(ProbeProfile(sampleCount = 12)))
        assertEquals(1500.0, ProbeTechnique.stylusDiameterUm(ProbeProfile(stylusDiameterUm = 2000.0, effectiveDiameterUm = 1500.0)))
    }

    @Test
    fun tip_calibration_spread_is_not_first_minus_last() {
        val touches = listOf(Touch(0.0, 0.0, 0.0, 0.0, 1), Touch(10.0, 0.0, 0.0, 0.0, 2), Touch(0.0, 0.0, 0.0, 0.0, 3))
        assertEquals(10.0, TouchStatistics.spreadUm(touches), 1e-9, "the spread is max − min, not |first − last|")
    }

    @Test
    fun no_probe_z_offset_compensation_setting_and_the_profile_has_a_z_offset() {
        val names = ProbeConfig::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { "offset" in it }, "the probe settings have no Z offset key: $names")
        assertEquals(12.0, ProbeProfile(zOffsetUm = 12.0).zOffsetUm, "the probe profile has ZOffset")
    }

    @Test
    fun z_absolute_is_A_plus_B() {
        val applied = assertIs<ZReferenceResult.Applied>(ZReference.apply(50 * MM, 10 * MM, zEnabled = true, connected = true))
        assertEquals(60 * MM, applied.zAbsoluteUm)
        assertEquals(50 * MM, applied.setterHeightUm, "store A (µm)")
        val empty = assertIs<ZReferenceResult.Applied>(ZReference.apply(50 * MM, null, zEnabled = true, connected = true))
        assertEquals(50 * MM, empty.zAbsoluteUm, "B is 0 when empty")
        assertEquals(50800.0, ZReference.toMicrometers(2.0, metric = false))
        assertEquals(2000.0, ZReference.toMicrometers(2.0, metric = true))
    }

    @Test
    fun setter_height_must_be_more_than_0() {
        for (a in listOf(0.0, -5 * MM, null)) {
            val failure = assertIs<ZReferenceResult.Failure>(ZReference.apply(a, 0.0, zEnabled = true, connected = true))
            assertEquals("Tool setter height must be a positive number", failure.message.text, "A = $a")
        }
        val setter = ProbeProfile(type = ProbeType.ToolHeightSetter, zOffsetUm = -3 * MM)
        val height = ZReference.setterHeightFromProfile(setter)
        assertIs<ZReferenceResult.Failure>(ZReference.apply(height, 0.0, zEnabled = true, connected = true, fromProfile = true))
        assertEquals(25 * MM, ZReference.setterHeightFromProfile(ProbeProfile(type = ProbeType.TouchProbe, zOffsetUm = 25 * MM)))
        assertNull(ZReference.setterHeightFromProfile(ProbeProfile(type = ProbeType.EdgeFinder2D, zOffsetUm = 25 * MM)))
    }

    @Test
    fun manual_and_automatic_mode_do_the_same_checks() {
        val manualZ = assertIs<ZReferenceResult.Failure>(ZReference.apply(50 * MM, 0.0, zEnabled = false, connected = true))
        assertEquals("Z readout must be enabled", manualZ.message.text)
        val manualLink = assertIs<ZReferenceResult.Failure>(ZReference.apply(50 * MM, 0.0, zEnabled = true, connected = false))
        assertEquals("The adapter must be connected", manualLink.message.text)
        val autoZ = assertIs<ZReferenceResult.Failure>(ZReference.applyAtTrigger(50 * MM, 0.0, false, true, 0.0))
        assertEquals("Z readout must be enabled", autoZ.message.text)
        val autoLink = assertIs<ZReferenceResult.Failure>(ZReference.applyAtTrigger(50 * MM, 0.0, true, false, 0.0))
        assertEquals("The adapter must be connected", autoLink.message.text)
        assertEquals(FunctionSound.Error, autoLink.sound)
    }

    @Test
    fun automatic_mode_uses_the_latched_z_and_needs_the_tool_setter_switch() {
        val result = assertIs<ZReferenceResult.Applied>(
            ZReference.applyAtTrigger(50 * MM, 10 * MM, zEnabled = true, connected = true, latchedAbsoluteZUm = 12345.0),
        )
        assertEquals(60 * MM, result.zAbsoluteUm)
        assertEquals(60 * MM - 12345.0, result.offsetDeltaUm!!, 1e-9, "Z reads A + B at the moment of the contact")
        assertEquals(FunctionSound.Short, result.sound)
        assertTrue(ZReference.autoVisible(ProbeConfig(enabled = true, toolSetterEnabled = true)))
        assertEquals(false, ZReference.autoVisible(ProbeConfig(enabled = true, toolSetterEnabled = false)))
        assertEquals("Set Z Reference", org.superdro.core.functions.zreference.ZReferenceTexts.TITLE.template)
    }
}
