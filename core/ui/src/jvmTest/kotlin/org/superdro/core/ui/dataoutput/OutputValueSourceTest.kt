package org.superdro.core.ui.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.dataoutput.RecordFormatter
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Channels
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFieldId
import org.superdro.core.model.DistanceUnit
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Workspace
import org.superdro.core.readout.input.AxisValue
import org.superdro.core.readout.input.AxisValueStatus
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.ProbeEvent

/**
 * The values of the data output come from the readout model of the display: the
 * shown values with the lathe diameter, the incremental offsets, the sub-datum offsets, the feed
 * rates, the spindle values, the probe ports, and the trigger values.
 */
class OutputValueSourceTest {

    private fun inputs(values: Map<AxisId, Double>, probes: Map<Char, Boolean> = emptyMap()) =
        InputSnapshot(axes = values.mapValues { (axis, value) -> AxisValue(axis, value, 0L, AxisValueStatus.Ok) }, probes = probes)

    private fun frame(profile: MachineProfile, values: Map<AxisId, Double>, workspace: Workspace = profile.activeWorkspace!!, probes: Map<Char, Boolean> = emptyMap()) =
        inputs(values, probes).let { snapshot -> DisplayFrames.of(profile, workspace, snapshot) to snapshot }

    private fun fields(vararg ids: DataOutputFieldId): DataOutputConfig =
        DataOutputConfig(fields = DataOutputConfig.defaultFields().map { field -> field.copy(enabled = field.id in ids) })

    @Test
    fun theDefaultRecordOfTheWorkedExampleComesFromTheReadoutModel() {
        val mill = MachineProfile.create(MachineType.VerticalMill)
        val (frame, snapshot) = frame(mill, mapOf(AxisId.X to 12700.0, AxisId.Y to -3175.0, AxisId.Z to 0.0))
        val values = OutputValueSource.values(frame, snapshot, emptyMap(), null, updatedAtEpochMs = 1791727402517L)
        assertEquals(12700.0, values.xAbsolute)
        assertEquals(-3175.0, values.yAbsolute)
        assertEquals(0.0, values.zAbsolute)
        val record = RecordFormatter.create(DataOutputConfig()).record(values, values.updatedAtEpochMs!!, 0L)
        assertEquals("2026-10-11T14:03:22.517Z;12.7000;-3.1750;0.0000\n", record)
        assertEquals(48, record.encodeToByteArray().size)
    }

    @Test
    fun aLatheInDiameterModeSendsTheShownDiameter() {
        val lathe = MachineProfile.create(MachineType.Lathe)
        val (frame, snapshot) = frame(lathe, mapOf(AxisId.X to 6350.0, AxisId.Z to -1000.0))
        val values = OutputValueSource.values(frame, snapshot, emptyMap(), null, null)
        assertEquals(12700.0, values.xAbsolute, "The X value of a lathe in diameter mode is 2 × the radius, as on the display.")
        assertEquals(-1000.0, values.zAbsolute)
    }

    @Test
    fun theIncrementalFieldsUseTheIncrementalOffsetAndTheSubDatumOffset() {
        val mill = MachineProfile.create(MachineType.VerticalMill)
        val base = mill.activeWorkspace!!
        val x = (base.axisStates[AxisId.X] ?: AxisState.forAxis(AxisId.X, MachineType.VerticalMill)).copy(incrementalOffset = -2000.0, absoluteOffset = 500.0)
        val workspace = base.copy(axisStates = base.axisStates + (AxisId.X to x))
        val (frame, snapshot) = frame(mill, mapOf(AxisId.X to 10000.0, AxisId.Y to 0.0), workspace)
        val values = OutputValueSource.values(frame, snapshot, emptyMap(), null, null)
        assertEquals(10500.0, values.xAbsolute, "abs = R + A")
        assertEquals(8500.0, values.xIncremental, "inc = abs + I")

        val point = SubDatum(id = "p1", name = "Point - 1", x = 3000.0, y = 1000.0)
        val withPoint = workspace.copy(subDatums = listOf(point), activeSubDatumId = point.id)
        val (pointFrame, pointSnapshot) = frame(mill, mapOf(AxisId.X to 10000.0, AxisId.Y to 0.0), withPoint)
        val pointValues = OutputValueSource.values(pointFrame, pointSnapshot, emptyMap(), null, null)
        assertEquals(7500.0, pointValues.xIncremental, "The active sub-datum offset S = −c replaces I.")
        assertEquals(-1000.0, pointValues.yIncremental)
    }

    @Test
    fun anAxisThatIsOffOrHasNoValueGivesNoValue() {
        val mill = MachineProfile.create(MachineType.VerticalMill).let { profile -> profile.copy(z = profile.z.copy(enabled = false)) }
        val (frame, snapshot) = frame(mill, mapOf(AxisId.X to 1.0))
        val values = OutputValueSource.values(frame, snapshot, mapOf(AxisId.X to 300000.0), null, null)
        assertNull(values.zAbsolute)
        assertNull(values.zFeedRate)
        assertNull(values.yAbsolute, "Y has no value.")
        assertNull(values.yFeedRate)
        assertEquals(300000.0, values.xFeedRate)
        val record = RecordFormatter.create(DataOutputConfig()).record(values, 0L, 0L)
        assertEquals("1970-01-01T00:00:00.000Z;0.0010;;\n", record, "A field without a value gives an empty text (Delimited).")
    }

    @Test
    fun theSpindleFieldsGiveTheRpmAndTheSurfaceSpeedOfTheWorkedExample() {
        val mill = MachineProfile.create(MachineType.VerticalMill).let { profile ->
            profile.copy(tachometer = profile.tachometer.copy(enabled = true, mode = TachometerMode.Sensor))
        }
        val workspace = mill.activeWorkspace!!.copy(toolXUm = 12700.0)
        val (frame, snapshot) = frame(mill, mapOf(AxisId.Tachometer to 1000.0), workspace)
        val values = OutputValueSource.values(frame, snapshot, emptyMap(), null, null)
        assertEquals(1000.0, values.spindleRpm)
        val inch = fields(DataOutputFieldId.SpindleRpm, DataOutputFieldId.SurfaceSpeed).copy(distanceUnit = DistanceUnit.Inch)
        assertEquals(listOf("1000", "130.8997"), RecordFormatter.create(inch).valueTexts(values))
        val mm = fields(DataOutputFieldId.SurfaceSpeed)
        assertEquals(listOf("39.8982"), RecordFormatter.create(mm).valueTexts(values))

        val lathe = MachineProfile.create(MachineType.Lathe).let { profile ->
            profile.copy(tachometer = profile.tachometer.copy(enabled = true, mode = TachometerMode.Sensor))
        }
        val (latheFrame, latheSnapshot) = frame(lathe, mapOf(AxisId.X to 6350.0, AxisId.Tachometer to 1000.0))
        val latheValues = OutputValueSource.values(latheFrame, latheSnapshot, emptyMap(), null, null)
        assertEquals(listOf("39.8982"), RecordFormatter.create(mm).valueTexts(latheValues), "A lathe uses D = 2 × |X|.")
    }

    @Test
    fun probe1AndProbe2AreTheProbePortsPAndQ() {
        val mill = MachineProfile.create(MachineType.VerticalMill).let { profile -> profile.copy(probe = profile.probe.copy(enabled = true)) }
        val (frame, snapshot) = frame(mill, mapOf(AxisId.X to 0.0), probes = mapOf(Channels.P to true, Channels.Q to false))
        val values = OutputValueSource.values(frame, snapshot, emptyMap(), null, null)
        assertEquals(1.0, values.probe1)
        assertEquals(0.0, values.probe2, "Probe 2 is the second probe port, not a disabled row.")

        val (q, qSnapshot) = frame(mill, mapOf(AxisId.X to 0.0), probes = mapOf(Channels.Q to true))
        val qValues = OutputValueSource.values(q, qSnapshot, emptyMap(), null, null)
        assertEquals(0.0, qValues.probe1)
        assertEquals(1.0, qValues.probe2)

        val noProbe = MachineProfile.create(MachineType.VerticalMill)
        val (off, offSnapshot) = frame(noProbe, mapOf(AxisId.X to 0.0))
        assertNull(OutputValueSource.values(off, offSnapshot, emptyMap(), null, null).probe1)
    }

    @Test
    fun theTriggerFieldsComeFromTheProbeEventWithTheOffsetsOfTheDisplay() {
        val mill = MachineProfile.create(MachineType.VerticalMill)
        val base = mill.activeWorkspace!!
        val x = (base.axisStates[AxisId.X] ?: AxisState.forAxis(AxisId.X, MachineType.VerticalMill)).copy(absoluteOffset = 1000.0)
        val workspace = base.copy(axisStates = base.axisStates + (AxisId.X to x))
        val (frame, _) = frame(mill, mapOf(AxisId.X to 0.0), workspace)
        val event = ProbeEvent(
            sequence = 1L,
            port = Channels.P,
            timeMs = 5000L,
            inputPositions = emptyMap(),
            axisPositions = mapOf(AxisId.X to 11700.0, AxisId.Y to -3175.0, AxisId.Z to 0.0),
            incompleteAxes = emptySet(),
            axisVelocities = mapOf(AxisId.X to 2540.0, AxisId.Y to 0.0, AxisId.Z to 0.0),
        )
        val trigger = OutputValueSource.trigger(frame, event, mapOf(AxisId.X to 1, AxisId.Y to 0, AxisId.Z to 0), sequence = 1L, timeEpochMs = 1791727402517L)
        assertEquals(12700.0, trigger.xUm, "The trigger X has the absolute offset of the display.")
        assertEquals(-3175.0, trigger.yUm)
        assertEquals(152400.0, trigger.feedUmPerMinute, "One axis moves: its speed over 1000 ms × 60.")
        val values = OutputValueSource.values(frame, InputSnapshot.EMPTY, emptyMap(), trigger, null)
        val config = fields(
            DataOutputFieldId.Probe1TriggerX,
            DataOutputFieldId.Probe1TriggerDirX,
            DataOutputFieldId.Probe1TriggerDirY,
            DataOutputFieldId.Probe1TriggerTime,
            DataOutputFieldId.Probe1TriggerSeq,
            DataOutputFieldId.Probe1TriggerFeed,
        )
        assertEquals(listOf("12.7000", "1", "0", "1791727402517", "1", "152.4000"), RecordFormatter.create(config).valueTexts(values))
        assertEquals(listOf("6.0000"), RecordFormatter.create(fields(DataOutputFieldId.Probe1TriggerFeed).copy(distanceUnit = DistanceUnit.Inch)).valueTexts(values))

        val twoAxes = OutputValueSource.trigger(frame, event, mapOf(AxisId.X to 1, AxisId.Y to -1, AxisId.Z to 0), sequence = 2L, timeEpochMs = 0L)
        assertNull(twoAxes.feedUmPerMinute, "Two axes move: no approach feed.")
    }
}
