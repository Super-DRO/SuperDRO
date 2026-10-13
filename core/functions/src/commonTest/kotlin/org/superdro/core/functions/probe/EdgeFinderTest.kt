package org.superdro.core.functions.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.patterns.AxisPoint
import org.superdro.core.model.AxisId

/**
 * The edge finder "Indicate Workpiece".
 */
class EdgeFinderTest {

    private val probe = EdgeFinderSettings(
        stylusDiameterUm = 4000.0,
        sampleCount = 3,
        spreadLimitUm = 20.0,
        xOffsetUm = 100.0,
        yOffsetUm = -50.0,
        zOffsetUm = 7.0,
        probeOn = true,
        zCapture = true,
    )

    private val trigger = 10_000L

    /** An input history: a motion of 10 µm in the 200 ms before the trigger, or no motion. */
    private fun motion(sign: Int): AxisHistory = AxisHistory(
        listOf(
            PositionSample(trigger - 200, 0.0),
            PositionSample(trigger - 100, sign * 5.0),
            PositionSample(trigger, sign * 10.0),
        ),
    )

    private fun autoTrigger(
        x: Int = 0,
        y: Int = 0,
        z: Int = 0,
        latched: Map<AxisId, Double> = mapOf(AxisId.X to 1000.0, AxisId.Y to 2000.0, AxisId.Z to 3000.0),
    ) = ProbeTrigger(
        timeMs = trigger,
        latchedUm = latched,
        histories = mapOf(AxisId.X to motion(x), AxisId.Y to motion(y), AxisId.Z to motion(z)),
    )

    private fun latched(axis: AxisId, value: Double) = ProbeTrigger(trigger, mapOf(axis to value))

    @Test
    fun theSingleEdgeSamplerAddsTheOffsetAndChecksTheSpreadWithMaxMinusMin() {
        var step = EdgeFinder.tapEdge(EdgeFinderState(probe), EdgeSide.Left, currentAbsUm = 0.0, probeClosed = false)
        assertEquals(EdgeStatus.Scanning, step.state.edge(EdgeSide.Left).status)
        step = EdgeFinder.onTrigger(step.state, latched(AxisId.X, 1000.0))
        assertEquals(listOf(FunctionSound.Short), step.sounds)
        step = EdgeFinder.onTrigger(step.state, latched(AxisId.X, 1050.0))
        assertEquals(listOf(FunctionSound.Short), step.sounds)
        step = EdgeFinder.onTrigger(step.state, latched(AxisId.X, 1000.0))
        assertEquals(listOf(FunctionSound.Long), step.sounds)
        val left = step.state.edge(EdgeSide.Left)
        assertEquals(EdgeStatus.Located, left.status)
        assertEquals((1100.0 + 1150.0 + 1100.0) / 3.0, left.valueUm)
        // The spread is 50 µm, more than the 20 µm limit.
        assertEquals("Warning: maximum spread between the samples is 0.050 mm", step.state.message?.text)
        assertNull(step.state.sampler)
        assertEquals(OriginOption.SideA, step.state.xOption)
    }

    @Test
    fun aSpreadInTheLimitGivesNoWarningAndTheYOffsetIsAdded() {
        var state = EdgeFinder.tapEdge(EdgeFinderState(probe.copy(sampleCount = 2)), EdgeSide.Far, 0.0, false).state
        state = EdgeFinder.onTrigger(state, latched(AxisId.Y, 5000.0)).state
        val step = EdgeFinder.onTrigger(state, latched(AxisId.Y, 5020.0))
        assertNull(step.state.message)
        assertEquals(4960.0, step.state.edge(EdgeSide.Far).valueUm)
        assertEquals(OriginOption.SideB, step.state.yOption)
    }

    @Test
    fun aTriggerWithNoPositionIsIgnoredAndTheSamplerContinues() {
        var state = EdgeFinder.tapEdge(EdgeFinderState(probe), EdgeSide.Right, 0.0, false).state
        val step = EdgeFinder.onTrigger(state, ProbeTrigger(trigger, emptyMap()))
        assertEquals("The axis position at the trigger is unknown. Try again.", step.state.message?.text)
        assertEquals(listOf(FunctionSound.Error), step.sounds)
        assertEquals(EdgeStatus.Scanning, step.state.edge(EdgeSide.Right).status)
        state = EdgeFinder.onTrigger(step.state, latched(AxisId.X, 1.0)).state
        assertEquals(1, state.sampler?.samples?.count)
        assertNull(state.message)
    }

    @Test
    fun aManualCaptureAddsNoOffset() {
        val manual = EdgeFinderState(probe.copy(probeOn = false))
        val tap = EdgeFinder.tapEdge(manual, EdgeSide.Left, currentAbsUm = 5000.0, probeClosed = false).state
        assertEquals(Edge(EdgeStatus.Located, 5000.0), tap.edge(EdgeSide.Left))
        val longPress = EdgeFinder.longPressEdge(EdgeFinderState(probe), EdgeSide.Near, currentAbsUm = 7000.0).state
        assertEquals(Edge(EdgeStatus.Located, 7000.0), longPress.edge(EdgeSide.Near))
    }

    @Test
    fun aTapWithTheProbeClosedGivesTheTriggeredMessage() {
        val step = EdgeFinder.tapEdge(EdgeFinderState(probe), EdgeSide.Left, 0.0, probeClosed = true)
        assertEquals(
            "Probe is in triggered state. Please move the probe away from the workpiece.",
            step.state.message?.text,
        )
        assertEquals(EdgeStatus.Unknown, step.state.edge(EdgeSide.Left).status)
        assertNull(step.state.sampler)
    }

    @Test
    fun theTapAndTheLongPressRulesOfTheEdgeStates() {
        var state = EdgeFinder.tapEdge(EdgeFinderState(probe), EdgeSide.Left, 0.0, false).state
        // A tap on a different edge sets the SCANNING edge to UNKNOWN.
        state = EdgeFinder.tapEdge(state, EdgeSide.Near, 0.0, false).state
        assertEquals(EdgeStatus.Unknown, state.edge(EdgeSide.Left).status)
        assertEquals(EdgeStatus.Scanning, state.edge(EdgeSide.Near).status)
        assertEquals(SamplerTarget.EdgeTarget(EdgeSide.Near), state.sampler?.target)
        // A long press on a SCANNING edge does nothing. A tap stops it.
        assertEquals(state, EdgeFinder.longPressEdge(state, EdgeSide.Near, 1.0).state)
        state = EdgeFinder.tapEdge(state, EdgeSide.Near, 0.0, false).state
        assertEquals(EdgeStatus.Unknown, state.edge(EdgeSide.Near).status)
        assertNull(state.sampler)
        // A tap on a located edge gives the tip. A long press clears it.
        state = EdgeFinder.longPressEdge(state, EdgeSide.Far, 9000.0).state
        val tip = EdgeFinder.tapEdge(state, EdgeSide.Far, 0.0, false).state
        assertEquals("Long press this button to clear current value", tip.message?.text)
        assertEquals(9000.0, tip.edge(EdgeSide.Far).valueUm)
        val cleared = EdgeFinder.longPressEdge(tip, EdgeSide.Far, 0.0).state
        assertEquals(Edge(), cleared.edge(EdgeSide.Far))
        assertNull(cleared.yOption)
    }

    private fun withEdges(vararg edges: Pair<EdgeSide, Double>, settings: EdgeFinderSettings = probe): EdgeFinderState =
        edges.fold(EdgeFinderState(settings)) { state, (side, value) -> EdgeFinder.longPressEdge(state, side, value).state }

    @Test
    fun setOriginInAbsoluteAndIncrementalMode() {
        var state = withEdges(EdgeSide.Left to 10000.0, EdgeSide.Right to 50000.0, EdgeSide.Near to 5000.0)
        state = EdgeFinder.setFeature(state, AxisId.Y, FeatureType.Internal)
        assertEquals(OriginOption.Center, state.xOption)
        assertEquals(OriginOption.SideA, state.yOption)
        assertEquals(30000.0, state.origin(AxisId.X))
        assertEquals(3000.0, state.origin(AxisId.Y))
        val current = mapOf(AxisId.X to 32000.0, AxisId.Y to 3000.0, AxisId.Z to 1.0)
        val absolute = EdgeFinder.setOrigin(state, current)
        assertEquals(
            listOf(AxisCommand(AxisId.X, 2000.0, CoordinateMode.Absolute), AxisCommand(AxisId.Y, 0.0, CoordinateMode.Absolute)),
            absolute.commands,
        )
        assertTrue(absolute.close)
        state = EdgeFinder.selectOption(state, AxisId.X, OriginOption.SideA).copy(mode = CoordinateMode.Incremental)
        assertEquals(12000.0, state.origin(AxisId.X))
        val incremental = EdgeFinder.setOrigin(state, current)
        // The value is P − O. The readout applies its X converter one time.
        assertEquals(AxisCommand(AxisId.X, 20000.0, CoordinateMode.Incremental), incremental.commands.first())
        // An option that is not enabled is ignored.
        assertEquals(state, EdgeFinder.selectOption(state, AxisId.Y, OriginOption.Center))
    }

    @Test
    fun setOriginStopsTheSamplerAndAuto() {
        var state = withEdges(EdgeSide.Left to 10000.0)
        state = EdgeFinder.toggleAuto(state, probeClosed = false).state
        assertTrue(state.autoOn)
        val result = EdgeFinder.setOrigin(state, mapOf(AxisId.X to 10000.0))
        assertFalse(result.state.autoOn)
        assertNull(result.state.sampler)
        assertEquals(listOf(AxisCommand(AxisId.X, -2000.0, CoordinateMode.Absolute)), result.commands)
    }

    @Test
    fun addSubDatumUsesTheOriginsOrTheCurrentPositions() {
        val state = withEdges(EdgeSide.Left to 10000.0, EdgeSide.Right to 50000.0)
        assertTrue(state.canApply)
        assertFalse(EdgeFinderState(probe).canApply)
        val current = mapOf(AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0)
        val tap = EdgeFinder.addSubDatum(state, current, zEnabled = true, use3dSubDatums = true, keepEdges = false)
        assertEquals(AxisPoint(30000.0, 2.0, 3.0), tap.point)
        assertEquals(Edge(), tap.state.edge(EdgeSide.Left))
        val longPress = EdgeFinder.addSubDatum(state, current, zEnabled = true, use3dSubDatums = false, keepEdges = true)
        assertEquals(AxisPoint(30000.0, 2.0, null), longPress.point)
        assertEquals(10000.0, longPress.state.edge(EdgeSide.Left).valueUm)
    }

    @Test
    fun theZCaptureSetsTheZOriginWithTheProfileZOffset() {
        var state = EdgeFinder.toggleZCapture(EdgeFinderState(probe.copy(sampleCount = 1)), probeClosed = false).state
        assertEquals(SamplerTarget.ZTarget, state.sampler?.target)
        val step = EdgeFinder.onTrigger(state, latched(AxisId.Z, -5000.0))
        state = step.state
        assertEquals(listOf(FunctionSound.Long), step.sounds)
        assertEquals(-5000.0, state.zValueUm)
        assertEquals(-4993.0, state.zOriginUm)
        assertTrue(state.canApply)
        val origin = EdgeFinder.setOrigin(state, mapOf(AxisId.Z to -4000.0))
        assertEquals(listOf(AxisCommand(AxisId.Z, 993.0, CoordinateMode.Absolute)), origin.commands)
        val point = EdgeFinder.addSubDatum(state, mapOf(AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0), true, false, false).point
        assertEquals(AxisPoint(1.0, 2.0, -4993.0), point)
    }

    @Test
    fun theAutoMessagesInTheirSequence() {
        var state = EdgeFinder.toggleAuto(EdgeFinderState(probe.copy(sampleCount = 2)), probeClosed = false).state
        val none = EdgeFinder.onTrigger(state, autoTrigger())
        assertEquals("No axis was moving when the probe triggered.", none.state.message?.text)
        assertEquals(listOf(FunctionSound.Error), none.sounds)
        assertTrue(none.state.autoOn)
        val two = EdgeFinder.onTrigger(state, autoTrigger(x = 1, z = -1))
        assertEquals("Please move one axis at a time", two.state.message?.text)
        val onlyZ = EdgeFinder.onTrigger(state, autoTrigger(z = -1))
        assertEquals("Z axis probing is not supported yet", onlyZ.state.message?.text)
        val noPosition = EdgeFinder.onTrigger(state, autoTrigger(x = 1, latched = mapOf(AxisId.Y to 0.0)))
        assertEquals("The axis position at the trigger is unknown. Try again.", noPosition.state.message?.text)
        // A first valid sample locks the approach X+, and the target edge shows SCANNING.
        val first = EdgeFinder.onTrigger(state, autoTrigger(x = 1))
        state = first.state
        assertEquals(listOf(FunctionSound.Short), first.sounds)
        assertNull(state.message)
        assertEquals(Approach.XPositive, state.auto?.locked)
        assertEquals(EdgeStatus.Scanning, state.edge(EdgeSide.Left).status)
        val change = EdgeFinder.onTrigger(state, autoTrigger(x = -1))
        assertEquals("Change in direction detected during probe sampling", change.state.message?.text)
        assertEquals(1, change.state.auto?.samples?.count)
        // "No motion" comes before the locked direction.
        assertEquals("No axis was moving when the probe triggered.", EdgeFinder.onTrigger(state, autoTrigger()).state.message?.text)
        val done = EdgeFinder.onTrigger(change.state, autoTrigger(x = 1, latched = mapOf(AxisId.X to 1040.0)))
        assertEquals(listOf(FunctionSound.Long), done.sounds)
        assertEquals(Edge(EdgeStatus.Located, 1120.0), done.state.edge(EdgeSide.Left))
        assertEquals("Warning: maximum spread between the samples is 0.040 mm", done.state.message?.text)
        assertEquals(AutoState(), done.state.auto)
    }

    @Test
    fun theDirectionToEdgeTable() {
        assertEquals(EdgeSide.Left, Approach.XPositive.edge(FeatureType.External))
        assertEquals(EdgeSide.Right, Approach.XPositive.edge(FeatureType.Internal))
        assertEquals(EdgeSide.Right, Approach.XNegative.edge(FeatureType.External))
        assertEquals(EdgeSide.Left, Approach.XNegative.edge(FeatureType.Internal))
        assertEquals(EdgeSide.Near, Approach.YPositive.edge(FeatureType.External))
        assertEquals(EdgeSide.Far, Approach.YPositive.edge(FeatureType.Internal))
        assertEquals(EdgeSide.Far, Approach.YNegative.edge(FeatureType.External))
        assertEquals(EdgeSide.Near, Approach.YNegative.edge(FeatureType.Internal))
        assertNull(Approach.ZPositive.edge(FeatureType.External))
    }

    @Test
    fun autoUsesTheFeatureTypeAndTheSameOffsetRuleAsTheSingleEdgePath() {
        var state = EdgeFinderState(probe.copy(sampleCount = 1))
        state = EdgeFinder.setFeature(state, AxisId.X, FeatureType.Internal)
        state = EdgeFinder.toggleAuto(state, false).state
        val step = EdgeFinder.onTrigger(state, autoTrigger(x = -1, latched = mapOf(AxisId.X to 1000.0)))
        assertEquals(Edge(EdgeStatus.Located, 1100.0), step.state.edge(EdgeSide.Left))
        val near = EdgeFinder.onTrigger(step.state, autoTrigger(y = 1, latched = mapOf(AxisId.Y to 2000.0)))
        assertEquals(Edge(EdgeStatus.Located, 1950.0), near.state.edge(EdgeSide.Near))
        // The single-edge path gives the same value for the same latch.
        var single = EdgeFinder.tapEdge(EdgeFinderState(probe.copy(sampleCount = 1)), EdgeSide.Near, 0.0, false).state
        single = EdgeFinder.onTrigger(single, latched(AxisId.Y, 2000.0)).state
        assertEquals(1950.0, single.edge(EdgeSide.Near).valueUm)
        // The feature type does not change while AUTO runs.
        assertEquals(near.state, EdgeFinder.setFeature(near.state, AxisId.X, FeatureType.External))
    }

    @Test
    fun autoStopsWhenTheFourthEdgeIsLocated() {
        var state = withEdges(EdgeSide.Left to 1.0, EdgeSide.Near to 2.0, EdgeSide.Far to 3.0, settings = probe.copy(sampleCount = 1))
        state = EdgeFinder.toggleAuto(state, false).state
        val step = EdgeFinder.onTrigger(state, autoTrigger(x = -1, latched = mapOf(AxisId.X to 4000.0)))
        assertEquals(EdgeStatus.Located, step.state.edge(EdgeSide.Right).status)
        assertFalse(step.state.autoOn)
    }

    @Test
    fun aStopClearsAutoAndANewStartUsesBothAxes() {
        var state = EdgeFinder.toggleAuto(EdgeFinderState(probe.copy(sampleCount = 2)), false).state
        state = EdgeFinder.onTrigger(state, autoTrigger(x = 1)).state
        assertEquals(1, state.auto?.samples?.count)
        state = EdgeFinder.toggleAuto(state, false).state
        assertNull(state.auto)
        state = EdgeFinder.toggleAuto(state, false).state
        assertEquals(AutoState(), state.auto)
        val y = EdgeFinder.onTrigger(state, autoTrigger(y = -1))
        assertEquals(Approach.YNegative, y.state.auto?.locked)
        assertEquals(EdgeStatus.Scanning, y.state.edge(EdgeSide.Far).status)
        assertEquals(EdgeStatus.Unknown, y.state.edge(EdgeSide.Left).status)
    }

    @Test
    fun aTapOnAnEdgeAndCancelStopAuto() {
        val auto = EdgeFinder.toggleAuto(EdgeFinderState(probe), false).state
        assertFalse(EdgeFinder.tapEdge(auto, EdgeSide.Left, 0.0, false).state.autoOn)
        val cancel = EdgeFinder.cancel(auto)
        assertFalse(cancel.state.autoOn)
        assertTrue(cancel.close)
        val closed = EdgeFinder.toggleAuto(EdgeFinderState(probe), probeClosed = true)
        assertFalse(closed.state.autoOn)
        assertEquals("Probe is in triggered state. Please move the probe away from the workpiece.", closed.state.message?.text)
        assertFalse(EdgeFinder.toggleAuto(EdgeFinderState(probe.copy(probeOn = false)), false).state.autoOn)
        // With no sampler and no AUTO, a trigger changes nothing.
        assertEquals(EdgeFinderStep(EdgeFinderState(probe)), EdgeFinder.onTrigger(EdgeFinderState(probe), latched(AxisId.X, 1.0)))
    }
}
