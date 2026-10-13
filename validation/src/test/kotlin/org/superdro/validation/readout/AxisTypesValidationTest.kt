package org.superdro.validation.readout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.CompoundProjection
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.HeadTilt
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.TachometerMode
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.SurfaceSpeed
import org.superdro.core.readout.Tachometer
import org.superdro.core.readout.UnitConverter
import org.superdro.core.readout.encoder.ScaleFactor
import org.superdro.core.readout.input.CompoundProjectionWeights
import org.superdro.core.readout.input.HeadTiltWeights
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.operations.ActionButtons
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.readout.operations.Divisions
import org.superdro.core.readout.operations.OperationRequest
import org.superdro.core.readout.operations.TachometerActions
import org.superdro.core.readout.settings.AxisSettingsRules

/**
 * The default channels, the selectable channels, the angular
 * divisions, the tachometer, the surface speed, the caliper, the weighted sum, and the
 * projections.
 */
class AxisTypesValidationTest {

    private fun angularOn(profile: MachineProfile): MachineProfile = profile.copy(angular = profile.angular.copy(enabled = true))

    private fun tachometer(profile: MachineProfile, mode: TachometerMode, invert: Boolean = false): MachineProfile =
        profile.copy(tachometer = profile.tachometer.copy(enabled = true, mode = mode, invert = invert))

    // Output axes and their default channels.

    @Test
    fun default_primary_and_secondary_channels() {
        assertEquals('x', Channels.defaultPrimary(AxisId.X))
        assertEquals('y', Channels.defaultPrimary(AxisId.Y))
        assertEquals('z', Channels.defaultPrimary(AxisId.Z))
        assertEquals('w', Channels.defaultPrimary(AxisId.Angular))
        assertEquals('t', Channels.defaultPrimary(AxisId.Tachometer))
        assertEquals('w', Channels.defaultPrimary(AxisId.Caliper))
        assertEquals('w', Channels.defaultSecondary(AxisId.X))
        assertEquals('w', Channels.defaultSecondary(AxisId.Y))
        assertEquals('w', Channels.defaultSecondary(AxisId.Z))
        assertNull(Channels.defaultSecondary(AxisId.Angular))
        assertNull(Channels.defaultSecondary(AxisId.Tachometer))
        assertNull(Channels.defaultSecondary(AxisId.Caliper))
        assertEquals('p', ProbeConfig().channel)
    }

    @Test
    fun new_profile_uses_the_default_channels_and_labels() {
        val profile = mill()
        assertEquals('x', profile.x.primary.channel)
        assertEquals('y', profile.y.primary.channel)
        assertEquals('z', profile.z.primary.channel)
        assertEquals('w', profile.angular.primary.channel)
        assertEquals('w', profile.caliper.input.channel)
        assertEquals(listOf("X", "Y", "Z"), listOf(profile.x.label, profile.y.label, profile.z.label))
        assertEquals("α", profile.angular.label)
        assertEquals("RPM", profile.tachometer.label)
    }

    @Test
    fun selectable_channels_are_x_y_z_w_u_v_and_the_probe_ports_p_q() {
        assertEquals(listOf('x', 'y', 'z', 'w', 'u', 'v'), Channels.axisChannels)
        assertEquals(listOf('p', 'q'), Channels.probeChannels)
        "abcdefrs".forEach { letter ->
            assertFalse(Channels.isAxisChannel(letter), "letter $letter")
            assertTrue(Channels.isIgnored(letter), "letter $letter")
        }
    }

    @Test
    fun letters_A_to_F_R_S_carry_no_value() {
        val stage = stage(mill(), adapter())
        "aAbcdefFrRsS".forEach { letter -> stage.onAxisCount(letter, 1000L, 0L) }
        assertTrue(stage.snapshot.channels.isEmpty(), "channels ${stage.snapshot.channels.keys}")
    }

    @Test
    fun two_outputs_can_use_the_same_channel() {
        val base = mill()
        val profile = base.copy(y = base.y.copy(primary = InputConfig('x')))
        val stage = stage(profile, adapter())
        stage.onAxisCount('x', 1234L, 0L)
        assertEquals(1234.0, stage.r(AxisId.X))
        assertEquals(1234.0, stage.r(AxisId.Y))
    }

    @Test
    fun weighted_sum_uses_the_sign_of_the_axis_invert() {
        val base = mill()
        val profile = base.copy(x = base.x.copy(primary = InputConfig('x', sign = -1)))
        val stage = stage(profile, adapter())
        stage.onAxisCount('x', 1234L, 0L)
        assertEquals(-1234.0, stage.r(AxisId.X))
    }

    @Test
    fun secondary_input_adds_to_the_primary_input_and_R_has_the_later_time() {
        val base = mill()
        val profile = base.copy(x = base.x.copy(secondary = InputConfig('w')))
        val stage = stage(profile, adapter(scale('x', 5080.0)))
        stage.onAxisCount('x', 100L, 10L)
        stage.onAxisCount('w', 2000L, 25L)
        val value = stage.snapshot.axes.getValue(AxisId.X)
        // 100 × 5 µm + 2000 × 1 µm.
        assertEquals(2500.0, value.value)
        assertEquals(25L, value.timeMs)
    }

    // Angular axis.

    @Test
    fun angular_default_resolution_is_1000() {
        assertEquals(1000.0, EncoderProfile.DEFAULT_ANGULAR_RESOLUTION)
        val profile = EncoderProfile.defaultFor('w', EncoderKind.Angular)
        assertEquals(1000.0, profile.resolution)
        assertEquals(1296.0, ScaleFactor.of('w', profile))
    }

    @Test
    fun angular_ppu_7200_gives_180_arcsec_per_count() {
        assertEquals(180.0, ScaleFactor.angular(7200.0))
    }

    @Test
    fun divisions_need_N_of_1_or_more() {
        assertFailsWith<IllegalArgumentException> { UnitConverter.Divisions(0) }
        val profile = angularOn(mill())
        val start = frameOf(profile, AxisId.Angular to 0.0)
        // N is not set: the units button gives no divisions mode and asks for the divisions sheet.
        val toggle = ActionButtons.toggleUnits(start, AxisId.Angular)
        assertEquals(OperationRequest.OpenDivisionsSheet, toggle.request)
        assertFalse(toggle.workspace.axisStates.getValue(AxisId.Angular).divisionUnits)
        assertNull(Divisions.sheetValue(start))
        assertFalse(Divisions.set(start, 0).changed)
    }

    @Test
    fun divisions_sheet_sets_N_and_shows_round_360_over_q() {
        val profile = angularOn(mill())
        val start = frameOf(profile, AxisId.Angular to 0.0)
        val set = Divisions.set(start, 24).workspace
        val state = set.axisStates.getValue(AxisId.Angular)
        assertEquals(24, state.divisionsPerRev)
        assertTrue(state.divisionUnits)
        assertEquals(24, Divisions.sheetValue(start.with(set)))
    }

    @Test
    fun N_24_typed_5_gives_270000_arcsec_75_degrees() {
        val profile = angularOn(mill())
        val workspace = profile.workspace.axis(AxisId.Angular) { state -> state.copy(divisionUnits = true, divisionsPerRev = 24) }
        val start = frame(profile, workspace, AxisId.Angular to 0.0)
        val result = AxisOperations.keypadText(start, AxisId.Angular, "5", keypadEvaluate).getOrThrow()
        val reading = start.with(result.workspace).reading(AxisId.Angular)
        assertEquals(270000.0, reading.absolute, 1e-6)
        assertEquals("5.00", reading.text)
        val degrees = start.with(result.workspace.axis(AxisId.Angular) { state -> state.copy(divisionUnits = false) })
        assertEquals("75.00", degrees.reading(AxisId.Angular).text)
    }

    @Test
    fun rotation_with_the_angular_encoder_makes_the_angular_axis_read_absolute() {
        val profile = angularOn(mill())
        val workspace = profile.workspace
            .axis(AxisId.Angular) { state -> state.copy(relative = true, incrementalOffset = -3600.0) }
            .copy(partRotation = org.superdro.core.model.PartRotation(active = true, linkedToAngularEncoder = true))
        val locked = frame(profile, workspace, AxisId.Angular to 7200.0)
        assertTrue(locked.angularLocked)
        assertFalse(locked.reading(AxisId.Angular).incremental)
        assertEquals(7200.0, locked.reading(AxisId.Angular).shown)
        // The incremental zero stays stored.
        assertEquals(-3600.0, locked.state(AxisId.Angular).incrementalOffset)
        val free = frame(profile, workspace.copy(partRotation = org.superdro.core.model.PartRotation()), AxisId.Angular to 7200.0)
        assertEquals(3600.0, free.reading(AxisId.Angular).shown)
    }

    @Test
    fun readout_uses_only_lathe_or_not_lathe() {
        val diameter = org.superdro.core.model.AxisState(diameterMode = true)
        assertEquals(
            org.superdro.core.readout.PostProcessor.Identity,
            org.superdro.core.readout.PostProcessor.of(AxisId.X, diameter, org.superdro.core.model.MachineType.HorizontalMill),
        )
        assertFalse(org.superdro.core.model.MachineType.HorizontalMill.isLathe)
        assertFalse(org.superdro.core.model.MachineType.VerticalMill.isLathe)
        assertTrue(org.superdro.core.model.MachineType.Lathe.isLathe)
    }

    @Test
    fun default_T_profile_has_no_filter_and_no_backlash() {
        val t = EncoderProfile.defaultFor('t')
        assertFalse(t.filter)
        assertEquals(org.superdro.core.model.BacklashMode.None, t.backlashMode)
        assertEquals(1.0, t.resolution)
    }

    @Test
    fun angular_input_uses_the_angular_procedure() {
        assertTrue(org.superdro.core.readout.calibration.CalibrationStart.angular(angularEncoder('w', 1000.0)))
        assertFalse(org.superdro.core.readout.calibration.CalibrationStart.angular(scale('x')))
    }

    // Tachometer.

    @Test
    fun rpm_sensor_tach_ppu_4_count_4800_gives_1200() {
        assertEquals(1200.0, Tachometer.rpm(4800L, 4.0, invert = false))
        assertEquals(-1200.0, Tachometer.rpm(4800L, 4.0, invert = true))
        assertEquals("1200", Tachometer.text(1200.0))
        val profile = tachometer(mill(), TachometerMode.Sensor)
        val stage = stage(profile, adapter(EncoderProfile.defaultFor('t').copy(resolution = 4.0)))
        stage.onAxisCount('t', 4800L, 0L)
        assertEquals(1200.0, stage.r(AxisId.Tachometer))
        assertEquals("1200", ReadoutFrame.of(profile, stage.snapshot).reading(AxisId.Tachometer).text)
    }

    @Test
    fun tach_invert_gives_minus_1() {
        val profile = tachometer(mill(), TachometerMode.Sensor, invert = true)
        val stage = stage(profile, adapter(EncoderProfile.defaultFor('t').copy(resolution = 4.0)))
        stage.onAxisCount('t', 4800L, 0L)
        assertEquals(-1200.0, stage.r(AxisId.Tachometer))
    }

    @Test
    fun tap_on_the_rpm_row_for_each_mode() {
        val sensor = TachometerActions.tap(frameOf(tachometer(mill(), TachometerMode.Sensor))).request
        assertEquals(OperationRequest.ShowMessage(org.superdro.core.readout.ReadoutMessage(ReadoutText.CantSetRpm)), sensor)
        assertEquals("Can't set RPM", ReadoutText.CantSetRpm.english)
        val preset = TachometerActions.tap(frameOf(tachometer(mill(), TachometerMode.Preset))).request
        assertEquals(OperationRequest.OpenPresetSheet(listOf(0.0, 105.0, 160.0, 250.0, 585.0, 898.0, 1400.0)), preset)
        val direct = TachometerActions.tap(frameOf(tachometer(mill(), TachometerMode.Direct))).request
        assertEquals(OperationRequest.OpenKeypad(AxisId.Tachometer), direct)
    }

    @Test
    fun preset_and_direct_modes_have_no_input_and_rpm_is_A() {
        listOf(TachometerMode.Preset, TachometerMode.Direct).forEach { mode ->
            val profile = tachometer(mill(), mode)
            assertTrue(InputConfiguration.axisTerms(profile).getValue(AxisId.Tachometer).terms.isEmpty(), "mode $mode")
            val stage = InputStage(InputConfiguration.from(profile, adapter()))
            stage.onAxisCount('t', 4800L, 0L)
            assertEquals(0.0, stage.r(AxisId.Tachometer), "mode $mode")
            val start = ReadoutFrame.of(profile, stage.snapshot)
            val selected = TachometerActions.selectPreset(start, 585.0).workspace
            val reading = start.with(selected).reading(AxisId.Tachometer)
            assertEquals(585.0, reading.absolute, "mode $mode")
            assertEquals("585", reading.text, "mode $mode")
        }
    }

    @Test
    fun presets_are_numbers_separated_by_spaces() {
        assertEquals(listOf(105.0, 160.0, 250.0, 585.0, 898.0, 1400.0), TachometerActions.parsePresets("105 160 250 585 898 1400"))
        assertEquals(listOf(0.0, 105.0, 160.0), TachometerActions.presetList(listOf(105.0, 160.0)))
    }

    @Test
    fun change_of_tach_ppu_to_a_value_that_is_not_0_sets_the_offsets_to_0() {
        val workspace = mill().workspace.axis(AxisId.Tachometer) { state -> state.copy(absoluteOffset = 500.0, incrementalOffset = 20.0) }
        val changed = AxisSettingsRules.onTachometerPulsesChanged(workspace, old = 1.0, new = 4.0).axisStates.getValue(AxisId.Tachometer)
        assertEquals(0.0, changed.absoluteOffset)
        assertEquals(0.0, changed.incrementalOffset)
        val zero = AxisSettingsRules.onTachometerPulsesChanged(workspace, old = 1.0, new = 0.0).axisStates.getValue(AxisId.Tachometer)
        assertEquals(500.0, zero.absoluteOffset)
    }

    // Surface speed.

    @Test
    fun mill_inch_D_12700_1200_rpm_gives_157_08_SFM() {
        assertEquals("157.08", SurfaceSpeed.text(SurfaceSpeed.speed(1200.0, 12700.0, metric = false)))
        val base = tachometer(mill(), TachometerMode.Sensor)
        val workspace = base.workspace.copy(toolXUm = 12700.0).axis(AxisId.Tachometer) { state -> state.copy(surfaceSpeedMode = true) }
        assertEquals("157.08", frame(base, workspace, AxisId.Tachometer to 1200.0).reading(AxisId.Tachometer).text)
    }

    @Test
    fun mill_metric_D_10000_1000_rpm_gives_31_42_m_per_min() {
        assertEquals("31.42", SurfaceSpeed.text(SurfaceSpeed.speed(1000.0, 10000.0, metric = true)))
        val base = tachometer(mill(), TachometerMode.Sensor).copy(metricDefault = true)
        val workspace = base.workspace.copy(toolXUm = 10000.0).axis(AxisId.Tachometer) { state -> state.copy(surfaceSpeedMode = true) }
        assertEquals("31.42", frame(base, workspace, AxisId.Tachometer to 1000.0).reading(AxisId.Tachometer).text)
    }

    @Test
    fun lathe_uses_D_2_times_abs_X() {
        // X abs = 12700 µm (radius), 1000 RPM, inch: "261.80" SFM.
        val base = tachometer(lathe(), TachometerMode.Sensor)
        assertEquals(25400.0, SurfaceSpeed.diameterUm(base, base.workspace, 12700.0))
        assertEquals(25400.0, SurfaceSpeed.diameterUm(base, base.workspace, -12700.0))
        val workspace = base.workspace.axis(AxisId.Tachometer) { state -> state.copy(surfaceSpeedMode = true) }
        assertEquals("261.80", frame(base, workspace, AxisId.X to 12700.0, AxisId.Tachometer to 1000.0).reading(AxisId.Tachometer).text)
        assertEquals("261.80", frame(base, workspace, AxisId.X to -12700.0, AxisId.Tachometer to 1000.0).reading(AxisId.Tachometer).text)
    }

    // Caliper.

    @Test
    fun caliper_has_its_own_state() {
        val profile = lathe().let { base -> base.copy(caliper = base.caliper.copy(enabled = true)) }
        val start = frameOf(profile, AxisId.Z to 1000.0, AxisId.Caliper to 2000.0)
        val caliper = AxisOperations.setAbsolute(start, AxisId.Caliper, 0.0).workspace
        assertEquals(-2000.0, caliper.axisStates.getValue(AxisId.Caliper).absoluteOffset)
        assertEquals(0.0, caliper.axisStates.getValue(AxisId.Z).absoluteOffset)
        val z = AxisOperations.setAbsolute(start, AxisId.Z, 0.0).workspace
        assertEquals(-1000.0, z.axisStates.getValue(AxisId.Z).absoluteOffset)
        assertEquals(0.0, z.axisStates.getValue(AxisId.Caliper).absoluteOffset)
    }

    @Test
    fun caliper_enable_stays_on_a_mill() {
        val profile = mill().let { base -> base.copy(caliper = base.caliper.copy(enabled = true)) }
        assertTrue(InputConfiguration.from(profile, adapter()).axes.getValue(AxisId.Caliper).enabled)
        val stage = stage(profile, adapter())
        stage.onAxisCount('w', 300L, 0L)
        assertEquals(300.0, stage.r(AxisId.Caliper))
    }

    // Compound slide projection.

    private fun compound(angleDegrees: Double, applyX: Boolean = true, applyZ: Boolean = true): CompoundProjection =
        CompoundProjection(active = true, angleArcSec = angleDegrees * 3600.0, applyX = applyX, applyZ = applyZ)

    @Test
    fun reference_0_angle_30_gives_kX_0_5_and_kZ_0_86603() {
        assertEquals(0.5, CompoundProjectionWeights.xWeight(compound(30.0), 0.0)!!, 1e-5)
        assertEquals(0.86603, CompoundProjectionWeights.zWeight(compound(30.0), 0.0)!!, 1e-5)
    }

    @Test
    fun reference_90_angle_29_5_gives_kX_0_87036_and_kZ_0_49242() {
        assertEquals(0.87036, CompoundProjectionWeights.xWeight(compound(29.5), 90.0)!!, 1e-5)
        assertEquals(0.49242, CompoundProjectionWeights.zWeight(compound(29.5), 90.0)!!, 1e-5)
    }

    @Test
    fun reference_180_angle_30_gives_kX_0_5_and_kZ_minus_0_86603() {
        assertEquals(0.5, CompoundProjectionWeights.xWeight(compound(30.0), 180.0)!!, 1e-5)
        assertEquals(-0.86603, CompoundProjectionWeights.zWeight(compound(30.0), 180.0)!!, 1e-5)
    }

    @Test
    fun each_reference_angle_in_the_range_has_an_effect() {
        val projection = compound(30.0)
        val x0 = CompoundProjectionWeights.xWeight(projection, 0.0)!!
        val z0 = CompoundProjectionWeights.zWeight(projection, 0.0)!!
        listOf(45.0, -45.0, 135.0, 270.0, -90.0).forEach { reference ->
            val x = CompoundProjectionWeights.xWeight(projection, reference)!!
            val z = CompoundProjectionWeights.zWeight(projection, reference)!!
            assertTrue(abs(x - x0) > 1e-6 || abs(z - z0) > 1e-6, "reference $reference gives no effect")
        }
    }

    @Test
    fun apply_switches_and_the_active_flag() {
        assertNull(CompoundProjectionWeights.xWeight(compound(30.0, applyX = false), 0.0))
        assertNull(CompoundProjectionWeights.zWeight(compound(30.0, applyZ = false), 0.0))
        assertNull(CompoundProjectionWeights.xWeight(compound(30.0).copy(active = false), 0.0))
    }

    @Test
    fun compound_moves_10000_um_at_30_degrees_X_adds_5000_and_Z_adds_8660() {
        val base = lathe()
        val profile = base.copy(auxiliary = AuxiliaryConfig(type = AuxiliaryType.CompoundSlide, vectoring = true, angleReferenceDegrees = 0))
            .withWorkspace { workspace -> workspace.copy(compoundProjection = compound(30.0)) }
        val stage = stage(profile, adapter())
        stage.onAxisCount('x', 0L, 0L)
        stage.onAxisCount('z', 0L, 0L)
        stage.onAxisCount('y', 10000L, 0L)
        assertEquals(5000.0, stage.r(AxisId.X)!!, 1e-6)
        assertEquals(8660.254, stage.r(AxisId.Z)!!, 1e-3)
        assertTrue(stage.snapshot.axes.getValue(AxisId.X).projected)
    }

    // Head tilt projection.

    @Test
    fun tilt_10_nod_5_weights() {
        val tilt = HeadTilt(active = true, tiltArcSec = 10.0 * 3600.0, nodArcSec = 5.0 * 3600.0)
        assertEquals(-0.17365, HeadTiltWeights.xWeight(tilt)!!, 1e-5)
        assertEquals(-0.08716, HeadTiltWeights.yWeight(tilt)!!, 1e-5)
        assertEquals(0.98094, HeadTiltWeights.quillWeight(tilt), 1e-5)
        assertEquals(1.0, HeadTiltWeights.quillWeight(tilt.copy(active = false)))
        assertNull(HeadTiltWeights.xWeight(tilt.copy(active = false)))
    }

    @Test
    fun quill_minus_20_mm_at_tilt_10_nod_5_moves_X_Y_Z() {
        val base = mill()
        val profile = base.copy(z = base.z.copy(secondary = InputConfig('w')))
            .withWorkspace { workspace ->
                workspace.copy(headTilt = HeadTilt(active = true, tiltArcSec = 10.0 * 3600.0, nodArcSec = 5.0 * 3600.0))
            }
        val stage = stage(profile, adapter())
        listOf('x', 'y', 'z').forEach { letter -> stage.onAxisCount(letter, 0L, 0L) }
        stage.onAxisCount('w', -20000L, 0L)
        // X +3.473 mm, Y +1.743 mm, Z −19.619 mm.
        assertEquals(3473.0, stage.r(AxisId.X)!!, 0.5)
        assertEquals(1743.0, stage.r(AxisId.Y)!!, 0.5)
        assertEquals(-19619.0, stage.r(AxisId.Z)!!, 0.5)
    }

    @Test
    fun tilt_10_nod_0_quill_minus_10000_gives_X_plus_1736_and_Z_minus_9848() {
        val base = mill()
        val profile = base.copy(z = base.z.copy(secondary = InputConfig('w')))
            .withWorkspace { workspace -> workspace.copy(headTilt = HeadTilt(active = true, tiltArcSec = 10.0 * 3600.0)) }
        val stage = stage(profile, adapter())
        listOf('x', 'y', 'z').forEach { letter -> stage.onAxisCount(letter, 0L, 0L) }
        stage.onAxisCount('w', -10000L, 0L)
        assertEquals(1736.0, stage.r(AxisId.X)!!, 0.5)
        assertEquals(-9848.0, stage.r(AxisId.Z)!!, 0.5)
        assertNotEquals(0.0, stage.r(AxisId.X))
    }
}
