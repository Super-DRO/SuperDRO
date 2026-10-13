package org.superdro.validation.readout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.InputConfig
import org.superdro.core.model.LinearAxisConfig
import org.superdro.core.model.MachineType
import org.superdro.core.model.MuteScope
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.ThemeMode
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.settings.AxisSettingsRules
import org.superdro.core.readout.settings.InputConflict
import org.superdro.core.readout.settings.InputConflicts
import org.superdro.core.readout.settings.SettingsResult

/**
 * The validation rules of the axis settings, the input
 * conflict check, and the default table. A new encoder profile has 25400 counts per inch.
 */
class SettingsValidationTest {

    private fun refusedText(result: SettingsResult<*>): String = assertIs<SettingsResult.Refused>(result).message.english

    // The validation rules.

    @Test
    fun primary_and_secondary_with_the_same_prefix_are_refused() {
        val config = LinearAxisConfig.forAxis(AxisId.X).copy(secondary = InputConfig('w'))
        assertEquals("Primary and secondary inputs can't use the same prefix", refusedText(AxisSettingsRules.setPrimaryChannel(config, 'w')))
        assertEquals("Primary and secondary inputs can't use the same prefix", refusedText(AxisSettingsRules.setSecondaryChannel(config, 'x')))
        assertEquals("Primary and secondary inputs can't use the same prefix", ReadoutText.SamePrefix.english)
        val accepted = assertIs<SettingsResult.Accepted<LinearAxisConfig>>(AxisSettingsRules.setPrimaryChannel(config, 'u'))
        assertEquals('u', accepted.value.primary.channel)
    }

    @Test
    fun the_last_output_axis_cannot_be_disabled() {
        val base = mill()
        val profile = base.copy(y = base.y.copy(enabled = false), z = base.z.copy(enabled = false))
        assertFalse(profile.angular.enabled)
        assertEquals("At least one output axis must be enabled", refusedText(AxisSettingsRules.setAxisEnabled(profile, AxisId.X, false)))
        val withAngular = profile.copy(angular = profile.angular.copy(enabled = true))
        val accepted = assertIs<SettingsResult.Accepted<*>>(AxisSettingsRules.setAxisEnabled(withAngular, AxisId.X, false))
        assertFalse((accepted.value as org.superdro.core.model.MachineProfile).x.enabled)
    }

    @Test
    fun secondary_input_on_selects_w_or_the_default_primary_channel() {
        listOf(AxisId.X to 'x', AxisId.Y to 'y', AxisId.Z to 'z').forEach { (axis, primary) ->
            val config = LinearAxisConfig.forAxis(axis)
            assertEquals('w', AxisSettingsRules.setSecondaryEnabled(axis, config, true).secondary?.channel, "axis $axis")
            val onW = config.copy(primary = InputConfig('w'))
            assertEquals(primary, AxisSettingsRules.setSecondaryEnabled(axis, onW, true).secondary?.channel, "axis $axis")
        }
    }

    @Test
    fun secondary_input_off_gives_no_channel_and_no_invert() {
        val config = LinearAxisConfig.forAxis(AxisId.X).copy(secondary = InputConfig('w', sign = -1))
        assertNull(AxisSettingsRules.setSecondaryEnabled(AxisId.X, config, false).secondary)
    }

    @Test
    fun edge_finder_sample_count_must_be_1_to_10() {
        val text = "Number of edge finder samples must be a number between 1 and 10 (inclusive)"
        assertEquals(text, refusedText(AxisSettingsRules.setEdgeFinderSampleCount(0)))
        assertEquals(text, refusedText(AxisSettingsRules.setEdgeFinderSampleCount(11)))
        assertEquals(text, refusedText(AxisSettingsRules.setEdgeFinderSampleCount(null)))
        assertEquals(1, assertIs<SettingsResult.Accepted<Int>>(AxisSettingsRules.setEdgeFinderSampleCount(1)).value)
        assertEquals(10, assertIs<SettingsResult.Accepted<Int>>(AxisSettingsRules.setEdgeFinderSampleCount(10)).value)
    }

    @Test
    fun tachometer_mode_shows_and_hides_the_fields() {
        val sensor = AxisSettingsRules.tachometerFields(TachometerMode.Sensor)
        assertTrue(sensor.showPulses)
        assertFalse(sensor.showPresets)
        val preset = AxisSettingsRules.tachometerFields(TachometerMode.Preset)
        assertFalse(preset.showPulses)
        assertTrue(preset.showPresets)
        val direct = AxisSettingsRules.tachometerFields(TachometerMode.Direct)
        assertFalse(direct.showPulses)
        assertFalse(direct.showPresets)
    }

    @Test
    fun probe_enabled_shows_the_edge_finder_settings() {
        assertTrue(AxisSettingsRules.edgeFinderSettingsVisible(true))
        assertFalse(AxisSettingsRules.edgeFinderSettingsVisible(false))
    }

    // The input conflict check.

    @Test
    fun letter_on_two_axes_gives_a_warning() {
        val base = mill()
        val profile = base.copy(y = base.y.copy(primary = InputConfig('x')))
        val conflicts = InputConflicts.check(profile)
        assertTrue(conflicts.any { conflict -> conflict is InputConflict.SharedWithinType && conflict.input == 'x' }, "$conflicts")
        assertTrue(InputConflicts.check(mill()).isEmpty())
    }

    @Test
    fun compound_projection_blocked_when_the_Y_letter_is_on_X_or_Z() {
        val base = lathe()
        val profile = base.copy(
            auxiliary = base.auxiliary.copy(type = AuxiliaryType.CompoundSlide, vectoring = true),
            x = base.x.copy(secondary = InputConfig('y')),
        )
        assertTrue(InputConflicts.check(profile).any { conflict -> conflict is InputConflict.CompoundProjectionBlocked })
    }

    // The default table.

    @Test
    fun machine_defaults() {
        val profile = mill()
        assertEquals("Mill", profile.name)
        assertEquals("Lathe", lathe().name)
        assertEquals(MachineType.VerticalMill, org.superdro.core.model.MachineProfile().machineType)
        assertFalse(profile.metricDefault)
        assertEquals(115200, profile.usbBaudRate)
        assertEquals(listOf(9600, 19200, 38400, 57600, 115200), AdapterAddress.UsbSerial.BAUD_RATES)
        assertEquals(8080, AdapterAddress.Tcp().port)
        assertTrue(profile.twoWayCommunication)
        assertFalse(profile.connectAtStart)
        assertFalse(profile.adapterConfigurationCommands)
        assertFalse(profile.useSharedToolLibrary)
    }

    @Test
    fun display_defaults() {
        val profile = mill()
        assertEquals(ReadoutFormat.Format3, profile.display.readoutFormat)
        assertEquals(ReadoutFont.Dseg7, profile.display.readoutFont)
        assertTrue(profile.display.showSliders)
        assertFalse(profile.display.shortPressZero)
        assertTrue(profile.display.activateNewSubDatum)
        assertEquals(PatternAngleConvention.Standard, profile.patternAngleConvention)
        assertEquals(PatternAngleConvention.Legacy, lathe().patternAngleConvention)
        assertEquals(AuxiliaryType.Tailstock, profile.auxiliary.type)
        assertFalse(profile.auxiliary.vectoring)
        assertEquals(0, profile.auxiliary.angleReferenceDegrees)
        assertTrue(profile.functionButtons.headTilt)
        assertTrue(profile.functionButtons.compoundAngle)
        assertTrue(profile.use3dSubDatums)
        assertEquals(SidePanelMode.Off, profile.sidePanel)
        val buttons = profile.functionButtons
        listOf(
            buttons.edgeFinder, buttons.zReference, buttons.radiusCompensation, buttons.heightCompensation,
            buttons.holeCircle, buttons.arbitraryHoleCircle, buttons.holeGrid, buttons.equidistantArray,
            buttons.dimensionMeasure, buttons.angleMeasure, buttons.arcCut,
        ).forEachIndexed { index, value -> assertTrue(value, "function button $index") }
    }

    @Test
    fun linear_axis_defaults() {
        val profile = mill()
        listOf(profile.x to "X", profile.y to "Y", profile.z to "Z").forEach { (axis, label) ->
            assertTrue(axis.enabled, label)
            assertEquals(label, axis.label)
            assertEquals(label.lowercase().single(), axis.primary.channel)
            assertEquals(1, axis.primary.sign, label)
            assertNull(axis.secondary, label)
            assertEquals(listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits), axis.buttons.map { it.mode })
            assertEquals(listOf(true, true, true), axis.buttons.map { it.showExpanded })
            assertEquals(listOf(true, false, false), axis.buttons.map { it.showCollapsed })
        }
    }

    @Test
    fun new_encoder_profile_has_25400_counts_per_inch_and_no_hysteresis_no_invert_no_filter() {
        val profile = EncoderProfile.defaultFor('x')
        assertEquals(25400.0, profile.resolution)
        assertEquals(0L, profile.backlashCounts)
        assertFalse(profile.reversed)
        assertFalse(profile.filter)
    }

    @Test
    fun setup_reminder_shows_for_no_profile_or_not_calibrated() {
        assertTrue(EncoderProfile.needsSetupReminder(null))
        assertTrue(EncoderProfile.needsSetupReminder(EncoderProfile.defaultFor('x')))
        val userSet = EncoderProfile.defaultFor('x').copy(
            calibration = org.superdro.core.model.Calibration(status = org.superdro.core.model.CalibrationStatus.UserSet),
        )
        assertFalse(EncoderProfile.needsSetupReminder(userSet))
    }

    @Test
    fun angular_caliper_and_tachometer_defaults() {
        val profile = mill()
        assertFalse(profile.angular.enabled)
        assertEquals("α", profile.angular.label)
        assertEquals('w', profile.angular.primary.channel)
        assertEquals(listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits), profile.angular.buttons.map { it.mode })
        assertEquals(1000.0, EncoderProfile.defaultFor('w', org.superdro.core.model.EncoderKind.Angular).resolution)
        assertFalse(profile.caliper.enabled)
        assertEquals('w', profile.caliper.input.channel)
        assertFalse(profile.tachometer.enabled)
        assertEquals("RPM", profile.tachometer.label)
        assertEquals(TachometerMode.Sensor, profile.tachometer.mode)
        assertEquals(listOf(105.0, 160.0, 250.0, 585.0, 898.0, 1400.0), profile.tachometer.presets)
        assertEquals(1.0, EncoderProfile.defaultFor('t').resolution)
        assertFalse(profile.tachometer.invert)
    }

    @Test
    fun probe_defaults() {
        val profile = mill()
        assertFalse(profile.probe.enabled)
        assertEquals('p', profile.probe.channel)
        assertEquals(ProbeContactType.Auto, AdapterProfile().probeContactType)
        assertEquals(200L, AdapterProfile().probeDebounceMs)
        assertTrue(profile.probe.edgeFinderEnabled)
        val probe = ProbeProfile()
        assertEquals(0.0, probe.xOffsetUm)
        assertEquals(0.0, probe.yOffsetUm)
        assertEquals(1, probe.sampleCount)
        assertEquals(20.0, probe.spreadLimitUm)
        assertFalse(profile.probe.toolSetterEnabled)
    }

    @Test
    fun app_defaults() {
        val app = AppSettings()
        assertEquals(ThemeMode.System, app.themeMode)
        assertEquals(GraphicalViewPlacement.BelowReadout, app.graphicalViewInPortrait)
        assertFalse(app.largeButtons)
        assertFalse(app.muteAllSounds)
        assertEquals(MuteScope.App, app.muteScope)
        assertTrue(app.fullScreen)
        assertTrue(app.wakeLockWhileLinked)
        assertTrue(app.showHints)
        assertTrue(app.showSetupReminder)
        assertFalse(app.sendGetSigOnce)
    }
}
