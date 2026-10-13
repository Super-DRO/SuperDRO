package org.superdro.core.readout.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.CompoundProjection
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.HeadTilt
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TachometerMode
import org.superdro.core.readout.Fixtures
import org.superdro.core.readout.assertNear

/** The input stage. */
class InputStageTest {

    private fun linearInputs(vararg letters: Char): Map<Char, EncoderProfile> = letters.associateWith { letter -> Fixtures.linear(letter) }

    private fun stage(profile: MachineProfile, encoders: Map<Char, EncoderProfile>): InputStage =
        InputStage(InputConfiguration(encoders, InputConfiguration.axisTerms(profile)))

    @Test
    fun theLettersAToFRAndSCarryNoValue() {
        val stage = stage(Fixtures.mill(), linearInputs('x', 'y', 'z'))
        listOf('a', 'B', 'c', 'd', 'E', 'F', 'f', 'r', 'S', '.').forEach { letter -> stage.onAxisCount(letter, 5L, 0L) }
        assertEquals(emptyMap(), stage.snapshot.channels)
    }

    @Test
    fun theSecondaryInputAddsToThePrimaryInputWithTheSign() {
        val mill = Fixtures.mill()
        val profile = mill.copy(z = mill.z.copy(secondary = InputConfig('w', sign = -1)))
        val stage = stage(profile.copy(machineType = org.superdro.core.model.MachineType.HorizontalMill), linearInputs('x', 'y', 'z', 'w'))
        stage.onAxisCount('z', 5000L, 0L)
        stage.onAxisCount('w', 1500L, 10L)
        val z = stage.snapshot.axes.getValue(AxisId.Z)
        assertEquals(3500.0, z.value)
        assertEquals(10L, z.timeMs, "the time of R is the larger time of the inputs")
        assertFalse(z.projected)
    }

    @Test
    fun twoOutputsCanUseTheSameChannel() {
        val mill = Fixtures.mill()
        val stage = stage(mill.copy(y = mill.y.copy(primary = InputConfig('x'))), linearInputs('x'))
        stage.onAxisCount('x', 700L, 0L)
        assertEquals(700.0, stage.snapshot.axisValue(AxisId.X))
        assertEquals(700.0, stage.snapshot.axisValue(AxisId.Y))
    }

    @Test
    fun theAxisStateShowsErrorNoInputOrNoData() {
        val encoders = mapOf('x' to EncoderProfile(kind = EncoderKind.Disabled), 'y' to EncoderProfile(resolution = 0.0))
        val stage = stage(Fixtures.mill(), encoders)
        stage.onAxisCount('x', 10L, 0L)
        stage.onAxisCount('y', 10L, 0L)
        stage.onAxisCount('z', 10L, 0L)
        val axes = stage.snapshot.axes
        assertEquals(AxisValueStatus.NoInput, axes.getValue(AxisId.X).status)
        assertEquals(AxisValueStatus.Error, axes.getValue(AxisId.Y).status)
        assertEquals(AxisValueStatus.Error, axes.getValue(AxisId.Z).status, "z has no encoder profile")
        assertNull(axes.getValue(AxisId.X).value)
        assertEquals(AxisValueStatus.NoData, axes.getValue(AxisId.Angular).status)
    }

    @Test
    fun theSnapshotHoldsTheRawCountTheValueAndTheTimeOfEachChannel() {
        val stage = stage(Fixtures.mill(), mapOf('x' to Fixtures.linear('x', resolution = 5080.0)))
        stage.onAxisCount('X', 12345L, 77L)
        val x = stage.snapshot.channels.getValue('x')
        assertEquals(ChannelValue('x', 12345L, 12345L, 61725.0, 77L, ChannelStatus.Ok), x)
    }

    @Test
    fun theLastValuesStayInTheSnapshotAfterALinkStart() {
        val stage = stage(Fixtures.mill(), linearInputs('x'))
        stage.onAxisCount('x', 500L, 1000L)
        stage.onLinkStart()
        assertEquals(500.0, stage.snapshot.axisValue(AxisId.X), "the last value, not 0")
        stage.onAxisCount('x', 700L, 10L)
        assertEquals(700.0, stage.snapshot.axisValue(AxisId.X), "a new link can start with an earlier time")
    }

    @Test
    fun theCompoundSlideProjectionAddsToXAndZ() {
        val stage = compoundStage(referenceDegrees = 0, angleDegrees = 30.0)
        stage.onAxisCount('x', 0L, 0L)
        stage.onAxisCount('z', 0L, 0L)
        stage.onAxisCount('y', 10000L, 0L)
        val snapshot = stage.snapshot
        assertNear(5000.0, snapshot.axisValue(AxisId.X)!!, 1e-6)
        assertNear(8660.254, snapshot.axisValue(AxisId.Z)!!, 1e-3)
        assertEquals(10000.0, snapshot.axisValue(AxisId.Y))
        assertTrue(snapshot.axes.getValue(AxisId.X).projected)
        assertTrue(snapshot.axes.getValue(AxisId.Z).projected)
    }

    @Test
    fun theCompoundReferenceTable() {
        assertWeights(referenceDegrees = 90, angleDegrees = 29.5, kx = 0.87036, kz = 0.49242)
        assertWeights(referenceDegrees = 180, angleDegrees = 30.0, kx = 0.5, kz = -0.86603)
        // Each reference of −360 to 360 has its effect.
        assertWeights(referenceDegrees = 45, angleDegrees = 15.0, kx = 0.5, kz = 0.86603)
        assertWeights(referenceDegrees = -90, angleDegrees = 0.0, kx = -1.0, kz = 0.0)
        assertWeights(referenceDegrees = 360, angleDegrees = 30.0, kx = 0.5, kz = 0.86603)
    }

    @Test
    fun theCompoundProjectionIsBlockedWhenXOrZUsesTheCompoundInput() {
        val lathe = compoundLathe(0, 30.0)
        val blocked = lathe.copy(x = lathe.x.copy(secondary = InputConfig('y')))
        val terms = InputConfiguration.axisTerms(blocked)
        assertFalse(terms.getValue(AxisId.X).terms.any { term -> term.role == TermRole.Compound })
        assertFalse(terms.getValue(AxisId.Z).terms.any { term -> term.role == TermRole.Compound })
    }

    @Test
    fun theCompoundTermsNeedTheActiveProjectionAndTheApplySwitches() {
        val lathe = compoundLathe(0, 30.0)
        val workspace = lathe.activeWorkspace!!
        val off = InputConfiguration.axisTerms(lathe, workspace.copy(compoundProjection = workspace.compoundProjection.copy(active = false)))
        assertEquals(1, off.getValue(AxisId.X).terms.size)
        val onlyZ = InputConfiguration.axisTerms(lathe, workspace.copy(compoundProjection = workspace.compoundProjection.copy(applyX = false)))
        assertEquals(1, onlyZ.getValue(AxisId.X).terms.size)
        assertEquals(2, onlyZ.getValue(AxisId.Z).terms.size)
        val withoutVectoring = lathe.copy(auxiliary = lathe.auxiliary.copy(vectoring = false))
        assertEquals(1, InputConfiguration.axisTerms(withoutVectoring).getValue(AxisId.X).terms.size)
    }

    @Test
    fun theHeadTiltProjectionAddsTheQuillToXYAndZ() {
        val stage = headTiltStage(tiltDegrees = 10.0, nodDegrees = 0.0)
        stage.onAxisCount('x', 0L, 0L)
        stage.onAxisCount('y', 0L, 0L)
        stage.onAxisCount('z', 0L, 0L)
        stage.onAxisCount('w', -10000L, 0L)
        assertNear(1736.48, stage.snapshot.axisValue(AxisId.X)!!, 0.01)
        assertNear(-9848.08, stage.snapshot.axisValue(AxisId.Z)!!, 0.01)

        val second = headTiltStage(tiltDegrees = 10.0, nodDegrees = 5.0)
        listOf('x', 'y', 'z').forEach { letter -> second.onAxisCount(letter, 0L, 0L) }
        second.onAxisCount('w', -20000L, 0L)
        assertNear(3472.96, second.snapshot.axisValue(AxisId.X)!!, 0.01)
        assertNear(1743.11, second.snapshot.axisValue(AxisId.Y)!!, 0.01)
        assertNear(-19618.87, second.snapshot.axisValue(AxisId.Z)!!, 0.01)
    }

    @Test
    fun theQuillAddsWithWeightOneWhenTheHeadTiltIsOffOrBlocked() {
        val off = headTiltProfile(10.0, 0.0).let { profile ->
            InputConfiguration.axisTerms(profile, profile.activeWorkspace!!.copy(headTilt = HeadTilt()))
        }
        assertEquals(1.0, off.getValue(AxisId.Z).terms.single { term -> term.role == TermRole.Quill }.weight)
        assertEquals(1, off.getValue(AxisId.X).terms.size)
        val profile = headTiltProfile(10.0, 0.0)
        val blocked = profile.copy(x = profile.x.copy(primary = InputConfig('w')))
        val terms = InputConfiguration.axisTerms(blocked)
        assertEquals(1.0, terms.getValue(AxisId.Z).terms.single { term -> term.role == TermRole.Quill }.weight)
        assertFalse(terms.getValue(AxisId.Y).terms.any { term -> term.role == TermRole.HeadTilt })
        assertTrue(HeadTiltWeights.tooFar(60.0 * 3600, 50.0 * 3600), "0.750 + 0.587 > 1")
        assertFalse(HeadTiltWeights.tooFar(10.0 * 3600, 5.0 * 3600))
    }

    @Test
    fun theTachometerSensorGivesTheRpm() {
        val mill = Fixtures.mill()
        val profile = mill.copy(tachometer = mill.tachometer.copy(enabled = true))
        val stage = stage(profile, mapOf('t' to EncoderProfile.defaultFor('t').copy(resolution = 4.0)))
        stage.onAxisCount('T', 4800L, 0L)
        assertEquals(1200.0, stage.snapshot.axisValue(AxisId.Tachometer))
        val inverted = stage(profile.copy(tachometer = profile.tachometer.copy(invert = true)), mapOf('t' to EncoderProfile.defaultFor('t').copy(resolution = 4.0)))
        inverted.onAxisCount('t', 4800L, 0L)
        assertEquals(-1200.0, inverted.snapshot.axisValue(AxisId.Tachometer))
    }

    @Test
    fun inThePresetAndDirectModesTheTachometerHasNoInput() {
        val mill = Fixtures.mill()
        val preset = mill.copy(tachometer = mill.tachometer.copy(mode = TachometerMode.Preset))
        val stage = stage(preset, mapOf('t' to EncoderProfile.defaultFor('t')))
        stage.onAxisCount('t', 4800L, 0L)
        assertEquals(0.0, stage.snapshot.axisValue(AxisId.Tachometer))
        assertEquals(AxisValueStatus.Ok, stage.snapshot.axes.getValue(AxisId.Tachometer).status)
    }

    @Test
    fun theConfigurationFromTheProfileAndTheAdapter() {
        val adapter = Fixtures.adapter(Fixtures.linear('X'), debounceMs = 300L)
        val configuration = InputConfiguration.from(Fixtures.mill(), adapter)
        assertEquals(setOf('x'), configuration.encoders.keys)
        assertEquals(300L, configuration.probe.debounceMs)
        assertEquals(1500L, configuration.probe.watchdogPeriodMs)
        assertEquals(listOf(AxisTerm('x')), configuration.axes.getValue(AxisId.X).terms)
        assertEquals(InputConfiguration(), InputConfiguration.from(Fixtures.mill(), null).copy(axes = emptyMap()))
    }

    private fun assertWeights(referenceDegrees: Int, angleDegrees: Double, kx: Double, kz: Double) {
        val terms = InputConfiguration.axisTerms(compoundLathe(referenceDegrees, angleDegrees))
        val x = terms.getValue(AxisId.X).terms.single { term -> term.role == TermRole.Compound }
        val z = terms.getValue(AxisId.Z).terms.single { term -> term.role == TermRole.Compound }
        assertNear(kx, x.weight, 1e-5, "kX for the reference $referenceDegrees")
        assertNear(kz, z.weight, 1e-5, "kZ for the reference $referenceDegrees")
    }

    private fun compoundLathe(referenceDegrees: Int, angleDegrees: Double): MachineProfile {
        val lathe = Fixtures.lathe()
        val workspace = lathe.activeWorkspace!!.copy(
            compoundProjection = CompoundProjection(active = true, angleArcSec = angleDegrees * 3600.0),
        )
        return lathe.copy(
            auxiliary = AuxiliaryConfig(AuxiliaryType.CompoundSlide, vectoring = true, angleReferenceDegrees = referenceDegrees),
            workspaces = listOf(workspace),
            activeWorkspaceId = workspace.id,
        )
    }

    private fun compoundStage(referenceDegrees: Int, angleDegrees: Double): InputStage =
        stage(compoundLathe(referenceDegrees, angleDegrees), linearInputs('x', 'y', 'z'))

    private fun headTiltProfile(tiltDegrees: Double, nodDegrees: Double): MachineProfile {
        val mill = Fixtures.mill()
        val workspace = mill.activeWorkspace!!.copy(
            headTilt = HeadTilt(active = true, tiltArcSec = tiltDegrees * 3600.0, nodArcSec = nodDegrees * 3600.0),
        )
        return mill.copy(z = mill.z.copy(secondary = InputConfig('w')), workspaces = listOf(workspace), activeWorkspaceId = workspace.id)
    }

    private fun headTiltStage(tiltDegrees: Double, nodDegrees: Double): InputStage =
        stage(headTiltProfile(tiltDegrees, nodDegrees), linearInputs('x', 'y', 'z', 'w'))
}
