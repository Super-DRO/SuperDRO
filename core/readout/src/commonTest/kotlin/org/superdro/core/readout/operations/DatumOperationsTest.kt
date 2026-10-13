package org.superdro.core.readout.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.ReadoutFrame

/** The sub-datum operations, the machine datums, and the caliper copy. */
class DatumOperationsTest {

    private fun frame(
        profile: MachineProfile,
        inputs: Map<AxisId, Double?>,
        states: Map<AxisId, AxisState> = emptyMap(),
        change: (Workspace) -> Workspace = { it },
    ): ReadoutFrame {
        val base = Fixtures.workspace(profile)
        return ReadoutFrame(profile, change(base.copy(axisStates = base.axisStates + states)), inputs)
    }

    private fun OperationResult.frame(from: ReadoutFrame): ReadoutFrame = ReadoutFrame(from.profile, workspace, from.inputs, from.instrument, hold)

    @Test
    fun theActivationSetsTheIncrementalModeOnTheAxesOfThePoint() {
        val point = SubDatum(id = "s1", x = 25400.0, y = 12700.0)
        val start = frame(Fixtures.mill(), mapOf(AxisId.X to 30000.0, AxisId.Y to 20000.0, AxisId.Z to 100.0)) { it.copy(subDatums = listOf(point)) }
        val result = SubDatumOperations.activate(start, "s1")
        assertEquals("s1", result.workspace.activeSubDatumId)
        val active = result.frame(start)
        assertEquals("0.1811", active.reading(AxisId.X).text)
        assertEquals(7300.0, active.reading(AxisId.Y).shown)
        assertFalse(active.reading(AxisId.Z).incremental, "the point has no Z")
        assertNull(SubDatumOperations.deactivate(active).workspace.activeSubDatumId)
        assertNull(SubDatumOperations.activate(start, "nothing").undo)
    }

    @Test
    fun aClearedSubDatumGivesTheOldIncrementalOffsetBack() {
        val point = SubDatum(id = "s1", x = 25400.0)
        val start = frame(Fixtures.mill(), mapOf(AxisId.X to 30000.0), mapOf(AxisId.X to AxisState(relative = true, incrementalOffset = -1000.0))) {
            it.copy(subDatums = listOf(point), activeSubDatumId = "s1")
        }
        assertEquals(4600.0, start.reading(AxisId.X).shown)
        val cleared = SubDatumOperations.deactivate(start).frame(start)
        assertEquals(29000.0, cleared.reading(AxisId.X).shown)
    }

    @Test
    fun setTheOriginAtASubDatumMovesTheAbsoluteOriginToThePoint() {
        val point = SubDatum(id = "s1", x = 25400.0, y = 12700.0, z = 1000.0)
        val mill = Fixtures.mill().let { profile -> profile.copy(z = profile.z.copy(enabled = false)) }
        val start = frame(mill, mapOf(AxisId.X to 30000.0, AxisId.Y to 20000.0, AxisId.Z to 5000.0), mapOf(AxisId.X to AxisState(relative = true))) {
            it.copy(subDatums = listOf(point))
        }
        val result = SubDatumOperations.setOriginAt(start, "s1")
        val after = result.frame(start)
        assertEquals(4600.0, after.reading(AxisId.X).absolute)
        assertEquals(7300.0, after.reading(AxisId.Y).absolute)
        assertFalse(after.reading(AxisId.X).incremental)
        assertEquals(5000.0, after.reading(AxisId.Z).absolute, "a disabled axis does not change")
    }

    @Test
    fun theMachineDatumExample() {
        val mill = Fixtures.mill(metric = true)
        val start = frame(mill, mapOf(AxisId.X to 160000.0, AxisId.Y to 80000.0, AxisId.Z to 0.0))
        val datum = MachineDatum(name = "Vise", x = 152400.0, y = 76200.0, z = -12700.0)
        val plan = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, datum))
        assertFalse(plan.noOp)
        assertEquals(mapOf(AxisId.X to -152400.0, AxisId.Y to -76200.0, AxisId.Z to 12700.0), plan.shifts)
        val after = plan.result.frame(start)
        assertEquals("7.600", after.reading(AxisId.X).text)
        assertEquals("3.800", after.reading(AxisId.Y).text)
        assertEquals("12.700", after.reading(AxisId.Z).text)
        assertTrue(plan.result.undo != null)
        // The second apply has nothing to do.
        assertTrue(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(after, datum)).noOp)
    }

    @Test
    fun theNoOpLimitsAreHalfAMicrometerAndHalfAnArcSecond() {
        val profile = Fixtures.mill().let { mill -> mill.copy(angular = mill.angular.copy(enabled = true)) }
        val datum = MachineDatum(x = 1000.0, a = 3600.0)
        val near = frame(profile, mapOf(AxisId.X to 0.0, AxisId.Angular to 0.0), mapOf(AxisId.X to AxisState(absoluteOffset = -1000.4), AxisId.Angular to AxisState(absoluteOffset = -3600.4)))
        assertTrue(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(near, datum)).noOp)
        val far = frame(profile, mapOf(AxisId.X to 0.0, AxisId.Angular to 0.0), mapOf(AxisId.X to AxisState(absoluteOffset = -1000.4), AxisId.Angular to AxisState(absoluteOffset = -3600.6)))
        assertFalse(assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(far, datum)).noOp)
        val noAngular = frame(Fixtures.mill(), mapOf(AxisId.X to 0.0), mapOf(AxisId.X to AxisState(absoluteOffset = -1000.0)))
        assertEquals(setOf(AxisId.X), assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(noAngular, datum)).shifts.keys, "A only when the angular axis is on")
    }

    @Test
    fun anAxisWithNoPositionGivesPositionNotAvailable() {
        val start = frame(Fixtures.mill(), mapOf(AxisId.X to 0.0, AxisId.Y to null))
        assertEquals(MachineDatumPlan.PositionNotAvailable(setOf(AxisId.Y)), MachineDatums.plan(start, MachineDatum(x = 1.0, y = 2.0)))
    }

    @Test
    fun theCaptureUsesTheMachinePositionWithNoToolOffset() {
        val profile = Fixtures.mill().let { mill -> mill.copy(angular = mill.angular.copy(enabled = true)) }
        val start = frame(
            profile,
            mapOf(AxisId.X to 100.0, AxisId.Y to 200.0, AxisId.Z to 300.0, AxisId.Angular to 3600.0),
            mapOf(AxisId.X to AxisState(toolOffset = 5000.0, toolOffsetActive = true, absoluteOffset = 7.0)),
        )
        val datum = MachineDatums.capture(start, "Datum 1")!!
        assertEquals(listOf(100.0, 200.0, 300.0, 3600.0), listOf(datum.x, datum.y, datum.z, datum.a))
        assertNull(MachineDatums.capture(frame(Fixtures.mill(), mapOf(AxisId.X to null)), "Datum 2"))
        val lathe = MachineDatums.capture(frame(Fixtures.lathe(), mapOf(AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0)), "Datum 3")!!
        assertNull(lathe.y, "no Y on a lathe")
    }

    @Test
    fun anAxisThatIsOffDoesNotCountInTheApply() {
        val mill = Fixtures.mill().let { profile -> profile.copy(z = profile.z.copy(enabled = false)) }
        // Z has no position, but Z is off: the datum Z does not apply and does not stop the apply.
        val start = frame(mill, mapOf(AxisId.X to 5.0, AxisId.Y to 0.0, AxisId.Z to null))
        val plan = assertIs<MachineDatumPlan.Apply>(MachineDatums.plan(start, MachineDatum(x = 1.0, z = 2.0)))
        assertEquals(setOf(AxisId.X), plan.shifts.keys)
        assertEquals(4.0, plan.result.frame(start).reading(AxisId.X).absolute)
    }

    @Test
    fun theCaptureReadsOnlyXWhenTheOtherAxesAreOff() {
        val xOnly = Fixtures.mill().let { profile ->
            profile.copy(y = profile.y.copy(enabled = false), z = profile.z.copy(enabled = false))
        }
        val datum = MachineDatums.capture(frame(xOnly, mapOf(AxisId.X to 1.0, AxisId.Y to 2.0, AxisId.Z to 3.0)), "Datum 1")!!
        assertEquals(listOf(1.0, null, null, null), listOf(datum.x, datum.y, datum.z, datum.a))
    }

    @Test
    fun machineDatumsSortInNaturalOrderAndTheQuickCaptureNameCounts() {
        val names = listOf("Datum 10", "datum 2", "Vise jaw", "Datum 1", "Chuck", "Datum 02", "Datum 1a")
        val sorted = MachineDatums.sortByName(names.map { name -> MachineDatum(name = name) }).map { datum -> datum.name }
        assertEquals(listOf("Chuck", "Datum 1", "Datum 1a", "datum 2", "Datum 02", "Datum 10", "Vise jaw"), sorted)
        assertEquals("Datum 1", MachineDatums.nextQuickCaptureName(emptyList()))
        assertEquals("Datum 3", MachineDatums.nextQuickCaptureName(listOf(MachineDatum(name = "DATUM 1"), MachineDatum(name = "datum 2"))))
    }

    @Test
    fun theCaliperCopyToXInRadiusModeAndInDiameterMode() {
        val lathe = Fixtures.lathe(metric = true).let { profile -> profile.copy(caliper = profile.caliper.copy(enabled = true)) }
        val radius = frame(lathe, mapOf(AxisId.X to 1000.0, AxisId.Caliper to 25000.0), mapOf(AxisId.X to AxisState(diameterMode = false)))
        assertEquals("25.000", radius.reading(AxisId.Caliper).text)
        val copied = CaliperOperations.copyToX(radius).frame(radius)
        assertEquals("12.500", copied.reading(AxisId.X).text)
        assertEquals(0.0, copied.state(AxisId.Caliper).absoluteOffset, "the caliper has its own state")

        val diameter = frame(lathe, mapOf(AxisId.X to 1000.0, AxisId.Caliper to 25000.0), mapOf(AxisId.X to AxisState(diameterMode = true)))
        assertEquals("25.000", CaliperOperations.copyToX(diameter).frame(diameter).reading(AxisId.X).text)

        val incremental = frame(lathe, mapOf(AxisId.X to 1000.0, AxisId.Caliper to 25000.0), mapOf(AxisId.X to AxisState(diameterMode = false, relative = true)))
        val result = CaliperOperations.copyToX(incremental)
        assertEquals(12500.0 - 1000.0, result.workspace.axisStates.getValue(AxisId.X).incrementalOffset)
        assertNull(CaliperOperations.copyToX(frame(lathe, mapOf(AxisId.X to 1000.0))).undo, "no caliper value")
    }

    @Test
    fun theCaliperZeroDoesNotChangeZ() {
        val lathe = Fixtures.lathe().let { profile -> profile.copy(caliper = profile.caliper.copy(enabled = true)) }
        val start = frame(lathe, mapOf(AxisId.Z to 500.0, AxisId.Caliper to 700.0))
        val result = ZeroButtons.longPress(start, AxisId.Caliper)
        assertEquals(-700.0, result.workspace.axisStates.getValue(AxisId.Caliper).absoluteOffset)
        assertEquals(0.0, result.workspace.axisStates.getValue(AxisId.Z).absoluteOffset)
    }
}
