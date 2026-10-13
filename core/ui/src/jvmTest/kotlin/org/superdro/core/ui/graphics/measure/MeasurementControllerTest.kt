package org.superdro.core.ui.graphics.measure

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.measurement.MeasurementFailure
import org.superdro.core.functions.measurement.MeasurementVerb
import org.superdro.core.functions.measurement.ResultKey
import org.superdro.core.functions.measurement.SlotState
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PointSource
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.resources.Res
import org.superdro.core.resources.measure_failure_collinear
import org.superdro.core.resources.measure_note_frame_changed
import org.superdro.core.resources.measure_note_uncompensated
import org.superdro.core.resources.measure_probe_no_approach
import org.superdro.core.resources.measure_probe_position_unknown
import org.superdro.core.resources.measure_step_on_circle
import org.superdro.core.resources.measure_step_second_apart
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.sound.UiSound
import org.superdro.core.ui.text.UiText

/** The presenter tests of the measurement mode. */
class MeasurementControllerTest {

    private val scope = TestScope()
    private val harness = GraphicsHarness(scope)
    private lateinit var controller: MeasurementController

    @AfterTest
    fun close() {
        if (::controller.isInitialized) controller.close()
        harness.close()
    }

    private val metricMill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)

    private suspend fun start(
        machine: MachineProfile = metricMill,
        states: Map<AxisId, AxisState> = emptyMap(),
        instrument: ProbeProfile? = null,
        measurement: Boolean = true,
    ): MeasurementController {
        harness.start(machine, states)
        if (instrument != null) {
            val stored = harness.probes.saveProbeProfile(instrument.copy(profileId = harness.profile.id)).valueOrNull()!!
            harness.updateProfile { profile -> profile.copy(probeRoles = profile.probeRoles.copy(measurementInstrumentProfileId = stored.id)) }
        }
        if (measurement) harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
        harness.moveTo(0L, x = 0L, y = 0L, z = 0L)
        controller = MeasurementController(harness.deviceId, harness.readout, harness.deps)
        scope.advanceUntilIdle()
        return controller
    }

    private fun latch(x: Long, y: Long, time: Long) {
        harness.moveTo(time, x = x, y = y)
        controller.onSlotTap(controller.uiState.value.slots.first { slot -> slot.state == SlotState.Armed }.index)
        scope.advanceUntilIdle()
        scope.advanceUntilIdle()
    }

    /**
     * One probe touch at (px, py) µm: the axes come to the point from 300 µm in the direction of
     * the signs (dx, dy), and the probe closes 10 ms after the last motion.
     */
    private fun touch(t0: Long, px: Long, py: Long, dx: Int, dy: Int) {
        for (k in 0..3) {
            val back = (3 - k) * 100L
            harness.moveTo(t0 + k * 100L, x = px - dx * back, y = py - dy * back)
        }
        harness.session.probe(active = true, timeMs = t0 + 310L)
        harness.moveTo(t0 + 320L, x = px, y = py)
        harness.session.tick(t0 + 900L)
        harness.session.probe(active = false, timeMs = t0 + 950L)
        scope.advanceUntilIdle()
    }

    private fun value(key: ResultKey): String? = controller.uiState.value.results.first { row -> row.key == key }.valueText

    private val ball = ProbeProfile(type = ProbeType.TouchProbe, name = "Ball", port = 'p', stylusDiameterUm = 2000.0)

    @Test
    fun theModeSwitchWritesTheProfileAndSelectsTheOneValidInstrument() = scope.runTest {
        start(measurement = false)
        val stored = harness.probes.saveProbeProfile(ball.copy(profileId = harness.profile.id)).valueOrNull()!!
        advanceUntilIdle()
        assertFalse(controller.uiState.value.modeOn)
        assertTrue(controller.setMeasurementMode(true))
        advanceUntilIdle()
        val profile = harness.fullProfile()
        assertEquals(MachineMode.Measurement, profile.machineMode)
        assertEquals(stored.id, profile.probeRoles.measurementInstrumentProfileId)
        assertTrue(controller.uiState.value.modeOn)
        assertEquals("Ball", controller.uiState.value.instrumentName)
        assertTrue(controller.toggleMeasurementMode())
        advanceUntilIdle()
        assertEquals(MachineMode.Cutting, harness.fullProfile().machineMode)
        assertFalse(controller.uiState.value.modeOn)
    }

    @Test
    fun aLatheHasNoMeasurementMode() = scope.runTest {
        start(machine = MachineProfile.create(MachineType.Lathe, "Lathe"), measurement = false)
        assertFalse(controller.setMeasurementMode(true))
        advanceUntilIdle()
        assertEquals(MachineMode.Cutting, harness.fullProfile().machineMode)
        assertFalse(controller.uiState.value.available)
    }

    @Test
    fun twoValidInstrumentsGiveNoAutomaticSelection() = scope.runTest {
        start(measurement = false)
        harness.probes.saveProbeProfile(ball.copy(profileId = harness.profile.id)).valueOrNull()!!
        harness.probes.saveProbeProfile(ball.copy(id = "second", name = "Second", profileId = harness.profile.id)).valueOrNull()!!
        advanceUntilIdle()
        controller.setMeasurementMode(true)
        scope.advanceUntilIdle()
        assertNull(harness.fullProfile().probeRoles.measurementInstrumentProfileId)
    }

    @Test
    fun theInstrumentOffsetsAddToTheXAndYReadouts() = scope.runTest {
        start(instrument = ball.copy(xOffsetUm = 5000.0, yOffsetUm = -2000.0), measurement = false)
        harness.moveTo(100L, x = 10_000L, y = 20_000L)
        assertEquals(10_000.0, harness.readout.frame.value!!.reading(AxisId.X).absolute, 1e-9)
        harness.updateProfile { profile -> profile.copy(machineMode = MachineMode.Measurement) }
        val frame = harness.readout.frame.value!!
        assertEquals(15_000.0, frame.reading(AxisId.X).absolute, 1e-9)
        assertEquals(18_000.0, frame.reading(AxisId.Y).absolute, 1e-9)
        assertEquals(0.0, frame.reading(AxisId.Z).instrumentOffset, 1e-9)
    }

    @Test
    fun theMeasurementModeSuspendsTheToolOffsets() = scope.runTest {
        start(states = mapOf(AxisId.X to AxisState(toolOffset = 3000.0, toolOffsetActive = true)), measurement = false)
        harness.moveTo(100L, x = 10_000L)
        assertEquals(13_000.0, harness.readout.frame.value!!.reading(AxisId.X).absolute, 1e-9)
        controller.setMeasurementMode(true)
        scope.advanceUntilIdle()
        assertEquals(10_000.0, harness.readout.frame.value!!.reading(AxisId.X).absolute, 1e-9)
    }

    @Test
    fun threeLatchPointsGiveTheWorkedExampleCircle() = scope.runTest {
        start()
        controller.onVerbTap(MeasurementVerb.Circle)
        scope.advanceUntilIdle()
        assertEquals(MeasurementVerb.Circle, controller.uiState.value.verb)
        assertEquals(UiText.of(Res.string.measure_step_on_circle), controller.uiState.value.message)
        latch(0L, 0L, 100L)
        assertEquals(UiText.of(Res.string.measure_step_second_apart), controller.uiState.value.message)
        latch(40_000L, 0L, 200L)
        latch(0L, 30_000L, 300L)
        assertEquals("20.000, 15.000 mm", value(ResultKey.Center))
        assertEquals("Ø 50.000 mm", value(ResultKey.Diameter))
        assertNull(controller.uiState.value.message)
        assertEquals(listOf(UiSound.Short, UiSound.Short, UiSound.Long), harness.sounds.played)
        val points = controller.uiState.value.points
        assertEquals(3, points.size)
        assertEquals(40_000.0, points[1].xUm, 1e-9)
        assertEquals(20_000.0, controller.uiState.value.circle!!.centerXUm, 1e-6)
    }

    @Test
    fun probePointsWithAnOutwardApproachGiveTheBore() = scope.runTest {
        start(instrument = ball)
        controller.onVerbTap(MeasurementVerb.Circle)
        scope.advanceUntilIdle()
        touch(1000L, 0L, 0L, -1, -1)
        touch(3000L, 40_000L, 0L, 1, -1)
        touch(5000L, 0L, 30_000L, -1, 1)
        assertEquals(3, harness.session.sentEvents.size)
        assertEquals("20.000, 15.000 mm", value(ResultKey.Center))
        assertEquals("Ø 52.000 mm", value(ResultKey.Diameter))
        val first = controller.uiState.value
        assertTrue(first.slots.all { slot -> slot.state == SlotState.Filled })
        assertEquals(UiSound.Long, harness.sounds.played.last())
    }

    @Test
    fun probePointsWithAnInwardApproachGiveTheBoss() = scope.runTest {
        start(instrument = ball)
        controller.onVerbTap(MeasurementVerb.Circle)
        scope.advanceUntilIdle()
        touch(1000L, 0L, 0L, 1, 1)
        touch(3000L, 40_000L, 0L, -1, 1)
        touch(5000L, 0L, 30_000L, 1, -1)
        assertEquals("Ø 48.000 mm", value(ResultKey.Diameter))
    }

    @Test
    fun aTriggerWithNoMotionIsRefused() = scope.runTest {
        start(instrument = ball)
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        harness.moveTo(100L, x = 1000L, y = 1000L)
        harness.moveTo(200L, x = 1000L, y = 1000L)
        harness.session.probe(active = true, timeMs = 1500L)
        harness.session.tick(2100L)
        advanceUntilIdle()
        assertEquals(UiText.of(Res.string.measure_probe_no_approach), controller.uiState.value.message)
        assertEquals(SlotState.Armed, controller.uiState.value.slots.single().state)
        assertEquals(UiSound.Error, harness.sounds.played.last())
    }

    @Test
    fun aTriggerWithNoPositionIsRefused() = scope.runTest {
        harness.start(metricMill)
        val stored = harness.probes.saveProbeProfile(ball.copy(profileId = harness.profile.id)).valueOrNull()!!
        harness.updateProfile { profile ->
            profile.copy(machineMode = MachineMode.Measurement, probeRoles = profile.probeRoles.copy(measurementInstrumentProfileId = stored.id))
        }
        controller = MeasurementController(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        // Only X has samples: Y has no position at the trigger.
        harness.moveTo(100L, x = 0L, z = 0L)
        harness.moveTo(200L, x = 100L)
        harness.session.probe(active = true, timeMs = 210L)
        harness.session.tick(900L)
        advanceUntilIdle()
        assertEquals(UiText.of(Res.string.measure_probe_position_unknown), controller.uiState.value.message)
    }

    @Test
    fun theSlotsFillInOrderAndALongPressClearsOne() = scope.runTest {
        start()
        controller.onVerbTap(MeasurementVerb.AngleBetweenLines)
        scope.advanceUntilIdle()
        val slots = controller.uiState.value.slots
        assertEquals(listOf(0, 0, 1, 1), slots.map { slot -> slot.group })
        assertEquals(listOf(SlotState.Armed, SlotState.Blank, SlotState.Blank, SlotState.Blank), slots.map { slot -> slot.state })
        assertFalse(controller.uiState.value.canReset)
        controller.onSlotTap(1)
        scope.advanceUntilIdle()
        assertEquals(SlotState.Blank, controller.uiState.value.slots[1].state)
        latch(1000L, 0L, 100L)
        latch(2000L, 0L, 200L)
        assertEquals(SlotState.Armed, controller.uiState.value.slots[2].state)
        controller.onSlotLongPress(0)
        scope.advanceUntilIdle()
        assertEquals(listOf(SlotState.Armed, SlotState.Filled, SlotState.Blank, SlotState.Blank), controller.uiState.value.slots.map { slot -> slot.state })
        latch(500L, 0L, 300L)
        assertEquals(500.0, controller.uiState.value.points.first { point -> point.slotIndex == 0 }.xUm, 1e-9)
        controller.onReset()
        scope.advanceUntilIdle()
        assertTrue(controller.uiState.value.points.isEmpty())
        assertFalse(controller.uiState.value.canReset)
        controller.onVerbLongPress(MeasurementVerb.AngleBetweenLines)
        scope.advanceUntilIdle()
        assertEquals(MeasurementVerb.AngleBetweenLines, controller.uiState.value.verb)
        controller.onVerbTap(MeasurementVerb.AngleBetweenLines)
        scope.advanceUntilIdle()
        assertNull(controller.uiState.value.verb)
    }

    @Test
    fun aProbePointInPointGetsTheUncompensatedNote() = scope.runTest {
        start(instrument = ball)
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        touch(1000L, 5000L, 6000L, 1, 0)
        val state = controller.uiState.value
        assertEquals(SlotState.FilledSuspect, state.slots.single().state)
        assertEquals(UiText.of(Res.string.measure_note_uncompensated), state.message)
        assertEquals("5.000, 6.000 mm", value(ResultKey.Position))
        assertTrue(state.points.single().suspect)
    }

    @Test
    fun threePointsOnALineGiveTheCollinearMessage() = scope.runTest {
        start()
        controller.onVerbTap(MeasurementVerb.Circle)
        scope.advanceUntilIdle()
        latch(0L, 0L, 100L)
        latch(10_000L, 0L, 200L)
        latch(20_000L, 0L, 300L)
        assertEquals(UiText.of(Res.string.measure_failure_collinear), controller.uiState.value.message)
        assertNull(value(ResultKey.Diameter))
        assertEquals(MeasurementFailure.entries.size, MeasurementFailure.entries.map(MeasurementTexts::failure).toSet().size)
    }

    @Test
    fun theLogGetsTheSequenceOneThenTwo() = scope.runTest {
        start()
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        latch(12_000L, 3_000L, 100L)
        assertTrue(controller.uiState.value.logVisible)
        assertTrue(controller.uiState.value.canLog)
        controller.onNoteChange("Hole A")
        scope.advanceUntilIdle()
        controller.onLog("Point")
        scope.advanceUntilIdle()
        assertEquals(1, controller.uiState.value.loggedSequence)
        assertFalse(controller.uiState.value.canLog)
        assertEquals(1L, controller.uiState.value.logCount)
        controller.onNoteChange("Hole B")
        scope.advanceUntilIdle()
        assertNull(controller.uiState.value.loggedSequence)
        controller.onLog("Point")
        scope.advanceUntilIdle()
        assertEquals(2, controller.uiState.value.loggedSequence)
        val entries = harness.measurementLog.entries(harness.profile.activeWorkspaceId)
        val stored = entries.first()
        assertEquals(listOf(1, 2), stored.map { entry -> entry.sequence })
        assertEquals("Hole A", stored[0].note)
        assertEquals("point", stored[0].verb)
        assertTrue(stored[0].display.metric)
        assertEquals(3, stored[0].display.precision)
        assertEquals(PointSource.Latch, stored[0].points.single().source)
    }

    @Test
    fun theAddButtonsMakeAMeasuredSubDatumAndACircle() = scope.runTest {
        start()
        controller.onVerbTap(MeasurementVerb.Circle)
        scope.advanceUntilIdle()
        latch(0L, 0L, 100L)
        latch(40_000L, 0L, 200L)
        latch(0L, 30_000L, 300L)
        controller.onAdd(ResultKey.Center, "Fitted center")
        scope.advanceUntilIdle()
        controller.onAdd(ResultKey.Diameter, "")
        scope.advanceUntilIdle()
        val subDatum = harness.workspace().subDatums.single { point -> point.name == "Fitted center" }
        assertEquals(listOf("Measured"), subDatum.tags)
        assertEquals(20_000.0, subDatum.x!!, 1e-6)
        assertEquals(15_000.0, subDatum.y!!, 1e-6)
        val drawing = harness.drawings.loadDrawing(harness.profile.activeWorkspaceId)!!
        val layer = drawing.layers.single { item -> item.name == "Measured" }
        val circle = drawing.shapes.single()
        assertEquals(ShapeType.Circle, circle.type)
        assertEquals(layer.index, circle.layerIndex)
        assertEquals(25_000_000L, circle.radius)
        assertEquals(20_000_000L, circle.x1)
    }

    @Test
    fun anArmedThreadBlocksTheAddOfASubDatum() = scope.runTest {
        start()
        harness.updateWorkspace { workspace -> workspace.copy(armedThreadId = "thread") }
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        latch(1000L, 2000L, 100L)
        assertFalse(controller.uiState.value.results.single().addEnabled)
        controller.onAdd(ResultKey.Position, "Measured point")
        scope.advanceUntilIdle()
        assertTrue(harness.workspace().subDatums.isEmpty())
    }

    @Test
    fun aTapOnASubDatumIsAPointSource() = scope.runTest {
        start()
        val stored = harness.store.machines.addSubDatum(harness.profile.activeWorkspaceId, SubDatum(name = "P", x = 7000.0, y = 8000.0)).valueOrNull()!!
        advanceUntilIdle()
        assertFalse(controller.onSubDatumTap(stored.id))
        scope.advanceUntilIdle()
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        assertTrue(controller.onSubDatumTap(stored.id))
        scope.advanceUntilIdle()
        assertEquals("7.000, 8.000 mm", value(ResultKey.Position))
    }

    @Test
    fun aCameraInstrumentGivesACameraPointWithTheOffsets() = scope.runTest {
        start(instrument = ProbeProfile(type = ProbeType.Camera, name = "Scope", port = null, xOffsetUm = 1000.0, yOffsetUm = 500.0))
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        latch(4000L, 4000L, 100L)
        assertEquals("5.000, 4.500 mm", value(ResultKey.Position))
        controller.onLog("Point")
        scope.advanceUntilIdle()
        val entry = harness.measurementLog.entries(harness.profile.activeWorkspaceId).first().single()
        assertEquals(PointSource.Camera, entry.points.single().source)
        assertEquals(1000.0, entry.points.single().instrumentXOffsetUm, 1e-9)
    }

    @Test
    fun aModeChangeClosesTheOpenFunction() = scope.runTest {
        start()
        controller.onVerbTap(MeasurementVerb.Distance)
        scope.advanceUntilIdle()
        latch(0L, 0L, 100L)
        assertNotNull(controller.uiState.value.verb)
        controller.setMeasurementMode(false)
        scope.advanceUntilIdle()
        assertNull(controller.uiState.value.verb)
        controller.onVerbTap(MeasurementVerb.Distance)
        scope.advanceUntilIdle()
        assertNull(controller.uiState.value.verb)
    }

    @Test
    fun theLogButtonOpensTheLogOnlyWithEntries() = scope.runTest {
        start()
        assertNull(controller.onLogButton())
        controller.onVerbTap(MeasurementVerb.Point)
        scope.advanceUntilIdle()
        latch(0L, 0L, 100L)
        controller.onLog("Point")
        scope.advanceUntilIdle()
        assertEquals(org.superdro.core.ui.navigation.MeasurementLogRoute, controller.onLogButton())
    }

    @Test
    fun aRotationChangeDuringASetGivesTheFrameChangedNote() = scope.runTest {
        start(machine = metricMill.copy(angular = org.superdro.core.model.AngularAxisConfig(enabled = true)))
        harness.updateWorkspace { workspace ->
            workspace.copy(partRotation = org.superdro.core.model.PartRotation(active = true, linkedToAngularEncoder = true))
        }
        harness.session.move('w', 0L, 50L)
        scope.advanceUntilIdle()
        controller.onVerbTap(MeasurementVerb.Distance)
        scope.advanceUntilIdle()
        val before = harness.readout.frame.value!!.partRotation!!.phiArcSec
        latch(0L, 0L, 100L)
        harness.session.move('w', 3600L, 150L)
        scope.advanceUntilIdle()
        val after = harness.readout.frame.value!!.partRotation!!.phiArcSec
        latch(10_000L, 0L, 200L)
        val change = kotlin.math.abs(after - before) / 3600.0
        assertTrue(change > 2.0 / 3600.0)
        assertEquals(
            UiText.of(Res.string.measure_note_frame_changed, MeasurementTexts.angleChangeText(change)),
            controller.uiState.value.message,
        )
        assertEquals(SlotState.FilledSuspect, controller.uiState.value.slots[1].state)
    }
}
