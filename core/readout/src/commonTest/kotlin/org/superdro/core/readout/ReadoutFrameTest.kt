package org.superdro.core.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisAlignment
import org.superdro.core.model.AxisAlignmentSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineMode
import org.superdro.core.model.PartRotation
import org.superdro.core.model.Plane
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.SubDatum
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputStage

/** The readout model with the measurement mode effects. */
class ReadoutFrameTest {

    private fun frame(
        profile: org.superdro.core.model.MachineProfile = Fixtures.mill(),
        inputs: Map<AxisId, Double?>,
        states: Map<AxisId, AxisState> = emptyMap(),
        instrument: ProbeProfile? = null,
        change: (org.superdro.core.model.Workspace) -> org.superdro.core.model.Workspace = { it },
    ): ReadoutFrame {
        val base = Fixtures.workspace(profile)
        val workspace = change(base.copy(axisStates = base.axisStates + states))
        return ReadoutFrame(profile, workspace, inputs, instrument)
    }

    @Test
    fun theAbsolutePositionIsTheSumAndTheOffsets() {
        val frame = frame(inputs = mapOf(AxisId.X to 63500.0), states = mapOf(AxisId.X to AxisState(absoluteOffset = -12700.0)))
        val x = frame.reading(AxisId.X)
        assertEquals(50800.0, x.absolute)
        assertEquals("2.0000", x.text)
        assertEquals(DisplayUnit.Inch, x.unit)
    }

    @Test
    fun theCountsOfAScaleOf5UmInInchAndInMm() {
        val stage = InputStage(InputConfiguration(mapOf('x' to Fixtures.linear('x', resolution = 5080.0)), InputConfiguration.axisTerms(Fixtures.mill())))
        stage.onAxisCount('x', 12345L, 0L)
        val inch = ReadoutFrame.of(Fixtures.mill(), stage.snapshot)
        assertEquals(61725.0, inch.reading(AxisId.X).r)
        assertEquals("2.4301", inch.reading(AxisId.X).text)
        val mm = ReadoutFrame.of(Fixtures.mill(metric = true), stage.snapshot)
        assertEquals("61.725", mm.reading(AxisId.X).text)
        assertEquals(DisplayUnit.Millimeter, mm.reading(AxisId.X).unit)
    }

    @Test
    fun theLatheDiameterModeShowsTwoTimesTheRadius() {
        val frame = frame(Fixtures.lathe(), inputs = mapOf(AxisId.X to 12700.0))
        assertEquals(25400.0, frame.reading(AxisId.X).shown)
        assertEquals("1.0000", frame.reading(AxisId.X).text)
        assertEquals(12700.0, frame.reading(AxisId.X).absolute)
    }

    @Test
    fun theAngularExamples() {
        val profile = Fixtures.mill().let { mill -> mill.copy(angular = mill.angular.copy(enabled = true)) }
        val stage = InputStage(InputConfiguration(mapOf('w' to Fixtures.angular('w', 1440.0)), InputConfiguration.axisTerms(profile)))
        stage.onAxisCount('w', 250L, 0L)
        val degrees = ReadoutFrame.of(profile, stage.snapshot)
        assertEquals(225000.0, degrees.reading(AxisId.Angular).r)
        assertEquals("62.50", degrees.reading(AxisId.Angular).text)
        val divisionState = mapOf(AxisId.Angular to AxisState(divisionUnits = true, divisionsPerRev = 24))
        assertEquals("4.17", frame(profile, mapOf(AxisId.Angular to 225000.0), divisionState).reading(AxisId.Angular).text)
        stage.onAxisCount('w', -100L, 10L)
        assertEquals(-90000.0, stage.snapshot.axisValue(AxisId.Angular))
        assertEquals("335.00", ReadoutFrame.of(profile, stage.snapshot).reading(AxisId.Angular).text)
        assertEquals("22.33", frame(profile, mapOf(AxisId.Angular to -90000.0), divisionState).reading(AxisId.Angular).text)
    }

    @Test
    fun theDivisionsModeNeedsNOfOneOrMore() {
        val frame = frame(inputs = mapOf(AxisId.Angular to 225000.0), states = mapOf(AxisId.Angular to AxisState(divisionUnits = true, divisionsPerRev = 0)))
        assertEquals(UnitConverter.Degrees, frame.converter(AxisId.Angular))
        assertEquals("62.50", frame.reading(AxisId.Angular).text)
    }

    @Test
    fun anAxisWithNoValueShowsTheDashPattern() {
        val frame = frame(inputs = mapOf(AxisId.X to null))
        assertFalse(frame.reading(AxisId.X).hasValue)
        assertEquals("---.----", frame.reading(AxisId.X).text)
    }

    @Test
    fun theIncrementalModeAddsTheIncrementalOffset() {
        val frame = frame(inputs = mapOf(AxisId.X to 41000.0), states = mapOf(AxisId.X to AxisState(incrementalOffset = -40000.0, relative = true)))
        assertEquals(1000.0, frame.reading(AxisId.X).shown)
        assertEquals("0.0394", frame.reading(AxisId.X).text)
        assertTrue(frame.reading(AxisId.X).incremental)
    }

    @Test
    fun theToolOffsetAddsBeforeTheAbsoluteOffset() {
        val frame = frame(inputs = mapOf(AxisId.Z to 1000.0), states = mapOf(AxisId.Z to AxisState(toolOffset = -12500.0, toolOffsetActive = true, absoluteOffset = 100.0)))
        assertEquals(1000.0 - 12500.0 + 100.0, frame.reading(AxisId.Z).absolute)
        assertTrue(frame.reading(AxisId.Z).modified, "the warning state")
        assertEquals(1000.0, frame.reading(AxisId.Z).machinePosition, "the machine position has no tool offset")
    }

    @Test
    fun theActiveSubDatumShowsThePositionRelativeToThePoint() {
        val point = SubDatum(id = "s1", x = 25400.0, y = 0.0)
        val frame = frame(inputs = mapOf(AxisId.X to 30000.0, AxisId.Y to 0.0), states = mapOf(AxisId.X to AxisState(relative = true, incrementalOffset = 999.0))) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1")
        }
        assertEquals(4600.0, frame.reading(AxisId.X).shown)
        assertEquals("0.1811", frame.reading(AxisId.X).text)
        assertEquals(-25400.0, frame.reading(AxisId.X).subDatumOffset)
    }

    @Test
    fun theZCoordinateNeeds3dSubDatumsOrALathe() {
        val point = SubDatum(id = "s1", x = 0.0, y = 0.0, z = 5000.0)
        val mill2d = Fixtures.mill().copy(use3dSubDatums = false)
        val frame2d = frame(mill2d, inputs = mapOf(AxisId.Z to 6000.0), states = mapOf(AxisId.Z to AxisState(relative = true))) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1")
        }
        assertNull(frame2d.subDatumOffsets[AxisId.Z])
        val frame3d = frame(Fixtures.mill(), inputs = mapOf(AxisId.Z to 6000.0), states = mapOf(AxisId.Z to AxisState(relative = true))) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1")
        }
        assertEquals(1000.0, frame3d.reading(AxisId.Z).shown)
    }

    @Test
    fun theTachometerShowsTheRpmAndTheSurfaceSpeed() {
        val mill = Fixtures.mill().copy(tachometer = Fixtures.mill().tachometer.copy(enabled = true))
        val rpm = frame(mill, inputs = mapOf(AxisId.Tachometer to 1200.0))
        assertEquals("1200", rpm.reading(AxisId.Tachometer).text)
        val sfm = frame(mill, inputs = mapOf(AxisId.Tachometer to 1200.0), states = mapOf(AxisId.Tachometer to AxisState(surfaceSpeedMode = true))) {
            it.copy(toolXUm = 12700.0)
        }
        assertEquals("157.08", sfm.reading(AxisId.Tachometer).text)
        assertEquals(DisplayUnit.SurfaceFeetPerMinute, sfm.reading(AxisId.Tachometer).unit)
        val metric = mill.copy(metricDefault = true)
        val mpm = frame(metric, inputs = mapOf(AxisId.Tachometer to 1000.0), states = mapOf(AxisId.Tachometer to AxisState(surfaceSpeedMode = true))) {
            it.copy(toolXUm = 10000.0)
        }
        assertEquals("31.42", mpm.reading(AxisId.Tachometer).text)
        assertEquals(DisplayUnit.MetersPerMinute, mpm.reading(AxisId.Tachometer).unit)
        // "Surface speed": a lathe uses D = 2 × |X abs|.
        val lathe = Fixtures.lathe()
        val latheFrame = frame(lathe, inputs = mapOf(AxisId.Tachometer to 1000.0, AxisId.X to -12700.0), states = mapOf(AxisId.Tachometer to AxisState(surfaceSpeedMode = true)))
        assertEquals("261.80", latheFrame.reading(AxisId.Tachometer).text)
    }

    @Test
    fun theTachometerUsesNoToolOffset() {
        val frame = frame(inputs = mapOf(AxisId.Tachometer to 0.0), states = mapOf(AxisId.Tachometer to AxisState(absoluteOffset = 585.0, toolOffset = 100.0, toolOffsetActive = true)))
        assertEquals(585.0, frame.reading(AxisId.Tachometer).shown)
    }

    @Test
    fun theMeasurementModeSuspendsTheToolOffsetsOfXYAndZ() {
        val states = AxisId.entries.associateWith { AxisState(toolOffset = 1000.0, toolOffsetActive = true) }
        val inputs = AxisId.entries.associateWith { 0.0 }
        val cutting = frame(Fixtures.mill(), inputs, states)
        assertEquals(1000.0, cutting.reading(AxisId.X).absolute)
        val measurement = frame(Fixtures.mill().copy(machineMode = MachineMode.Measurement), inputs, states)
        assertTrue(measurement.measurementActive)
        assertEquals(0.0, measurement.reading(AxisId.X).absolute)
        assertEquals(0.0, measurement.reading(AxisId.Y).absolute)
        assertEquals(0.0, measurement.reading(AxisId.Z).absolute)
        assertEquals(1000.0, measurement.reading(AxisId.Angular).absolute)
        val lathe = frame(Fixtures.lathe().copy(machineMode = MachineMode.Measurement), inputs, states)
        assertFalse(lathe.measurementActive, "not on a lathe")
        assertEquals(1000.0, lathe.reading(AxisId.Z).absolute)
    }

    @Test
    fun theInstrumentOffsetsAddToXAndY() {
        val instrument = ProbeProfile(type = ProbeType.TouchProbe, port = 'p', xOffsetUm = 500.0, yOffsetUm = -300.0)
        val inputs = mapOf(AxisId.X to 1000.0, AxisId.Y to 1000.0, AxisId.Z to 1000.0)
        val measurement = frame(Fixtures.mill().copy(machineMode = MachineMode.Measurement), inputs, instrument = instrument)
        assertEquals(1500.0, measurement.reading(AxisId.X).absolute)
        assertEquals(700.0, measurement.reading(AxisId.Y).absolute)
        assertEquals(1000.0, measurement.reading(AxisId.Z).absolute)
        assertEquals(1000.0, measurement.reading(AxisId.X).machinePosition, "the machine position has no instrument offset")
        assertEquals(1000.0, frame(Fixtures.mill(), inputs, instrument = instrument).reading(AxisId.X).absolute, "only in the measurement mode")
        val setter = instrument.copy(type = ProbeType.ToolHeightSetter)
        assertFalse(ReadoutFrame.validInstrument(setter))
        assertFalse(ReadoutFrame.validInstrument(instrument.copy(port = null)))
        assertTrue(ReadoutFrame.validInstrument(instrument.copy(type = ProbeType.Camera, port = null)))
    }

    @Test
    fun theAxisAlignmentTurnsTheXAndYReadouts() {
        val profile = Fixtures.mill().copy(machineMode = MachineMode.Measurement)
        val frame = frame(profile, mapOf(AxisId.X to 100000.0, AxisId.Y to 0.0)) {
            it.copy(axisAlignment = AxisAlignment(active = true, angleArcSec = 2.0 * 3600.0))
        }
        assertNear(99939.0, frame.reading(AxisId.X).absolute, 0.5)
        assertNear(-3490.0, frame.reading(AxisId.Y).absolute, 0.5)
        assertTrue(frame.reading(AxisId.X).modified)
        val otherPlane = frame(profile, mapOf(AxisId.X to 100000.0, AxisId.Y to 0.0)) {
            it.copy(plane = Plane.XZ, axisAlignment = AxisAlignment(active = true, angleArcSec = 7200.0))
        }
        assertEquals(100000.0, otherPlane.reading(AxisId.X).absolute, "only in the XY plane")
        val cutting = frame(Fixtures.mill(), mapOf(AxisId.X to 100000.0, AxisId.Y to 0.0)) {
            it.copy(axisAlignment = AxisAlignment(active = true, angleArcSec = 7200.0))
        }
        assertEquals(100000.0, cutting.reading(AxisId.X).absolute, "only in the measurement mode")
    }

    @Test
    fun theAlignmentFromTheAngularEncoderLocksTheAngularAxis() {
        val profile = Fixtures.mill().copy(machineMode = MachineMode.Measurement).let { mill -> mill.copy(angular = mill.angular.copy(enabled = true)) }
        val frame = frame(
            profile,
            mapOf(AxisId.X to 100000.0, AxisId.Y to 0.0, AxisId.Angular to 10.0 * 3600.0),
            mapOf(AxisId.Angular to AxisState(relative = true, incrementalOffset = 3600.0)),
        ) {
            it.copy(axisAlignment = AxisAlignment(active = true, source = AxisAlignmentSource.AngularEncoder, encoderOffsetArcSec = 4.0 * 3600.0))
        }
        // θ = −(10° − 4°) = −6°.
        assertEquals(-6.0 * 3600.0, frame.alignment!!.angleArcSec)
        assertTrue(frame.angularLocked)
        val angular = frame.reading(AxisId.Angular)
        assertFalse(angular.incremental, "the angular axis reads absolute")
        assertTrue(angular.locked)
        assertEquals("10.00", angular.text)
    }

    @Test
    fun theRotatePartTransformTurnsTheShownSubDatums() {
        val transform = PartRotationTransform.of(PartRotation(active = true, angleArcSec = 90.0 * 3600.0), 0.0)!!
        val shown = transform.toDisplay(10.0, 0.0)
        assertNear(0.0, shown.x)
        assertNear(-10.0, shown.y)
        val back = transform.toStored(shown.x, shown.y)
        assertNear(10.0, back.x)
        assertNear(0.0, back.y)
        assertNull(PartRotationTransform.of(PartRotation(active = false, angleArcSec = 10.0), 0.0))

        val point = SubDatum(id = "s1", x = 10000.0, y = 0.0)
        val states = mapOf(AxisId.X to AxisState(relative = true), AxisId.Y to AxisState(relative = true))
        val frame = frame(Fixtures.mill(), mapOf(AxisId.X to 0.0, AxisId.Y to -10000.0), states) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1", partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0))
        }
        assertNear(0.0, frame.reading(AxisId.X).shown, 1e-6)
        assertNear(0.0, frame.reading(AxisId.Y).shown, 1e-6)
        assertEquals(-10000.0, frame.reading(AxisId.Y).r, "the readouts stay in machine coordinates")
        val linked = frame(Fixtures.mill(), mapOf(AxisId.Angular to 0.0)) {
            it.copy(partRotation = PartRotation(active = true, linkedToAngularEncoder = true))
        }
        assertTrue(linked.angularLocked)
    }

    @Test
    fun aProjectedAxisHasTheWarningState() {
        val frame = ReadoutFrame(Fixtures.mill(), Fixtures.workspace(Fixtures.mill()), mapOf(AxisId.X to 0.0), projectedAxes = setOf(AxisId.X))
        assertTrue(frame.reading(AxisId.X).modified)
        assertFalse(frame.reading(AxisId.Y).modified)
    }

    @Test
    fun theAbsolutePositionOfLatchedSums() {
        val frame = frame(inputs = emptyMap(), states = mapOf(AxisId.X to AxisState(absoluteOffset = -100.0)))
        assertEquals(mapOf(AxisId.X to 400.0, AxisId.Y to 7.0), frame.absoluteFor(mapOf(AxisId.X to 500.0, AxisId.Y to 7.0)))
    }
}
