package org.superdro.validation.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.BindInterface
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputFormat
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.DistanceUnit
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MuteScope
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.Plane
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.SubDatum
import org.superdro.core.model.TabState
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.ThemeMode
import org.superdro.core.model.TimestampFormat
import org.superdro.core.model.Units
import org.superdro.core.model.Workspace
import org.superdro.core.model.newId

/**
 * The entities and their defaults, and the default of each row of the settings table. The expected
 * values come from the entity table, the settings table, and the encoder profile defaults (the
 * default resolution).
 */
class EntityDefaultsTest {

    private val mill = MachineProfile.create(MachineType.VerticalMill)
    private val lathe = MachineProfile.create(MachineType.Lathe)

    @Test
    fun new_mill_profile_has_the_defaults() {
        assertEquals("Mill", mill.name)
        assertEquals(MachineType.VerticalMill, mill.machineType)
        assertFalse(mill.metricDefault)
        assertNull(mill.adapter)
        assertNull(mill.adapterProfileId)
        assertEquals(MachineMode.Cutting, mill.machineMode)
        assertEquals(PatternAngleConvention.Standard, mill.patternAngleConvention)
        assertEquals(AuxiliaryType.Tailstock, mill.auxiliary.type)
        assertFalse(mill.auxiliary.vectoring)
        assertEquals(0, mill.auxiliary.angleReferenceDegrees)
        assertNull(mill.probeRoles.edgeFinderProfileId)
        assertNull(mill.probeRoles.zReferenceProfileId)
        assertNull(mill.probeRoles.measurementInstrumentProfileId)
        assertTrue(mill.machineDatums.isEmpty())
        assertFalse(mill.dataOutput.enabled)
        assertNull(mill.cameraId)
        assertTrue(mill.twoWayCommunication)
        assertFalse(mill.connectAtStart)
        assertFalse(mill.adapterConfigurationCommands)
        assertFalse(mill.useSharedToolLibrary)
        assertTrue(mill.use3dSubDatums)
        assertEquals(SidePanelMode.Off, mill.sidePanel)
        assertEquals(0.0, mill.stylusDiameterUm)
        assertNull(mill.stylusMetric)
        assertEquals(0.0, mill.zSetterHeightUm)
        assertNull(mill.zSetterMetric)
        assertEquals(1, mill.workspaces.size)
        assertEquals("Default Workspace", mill.workspaces.single().name)
        assertEquals(mill.workspaces.single().id, mill.activeWorkspaceId)
        assertTrue(mill.tools.isEmpty())
        assertTrue(StoreFixtures.UUID_FORM.matches(mill.id))
    }

    @Test
    fun new_lathe_profile_is_named_lathe_and_uses_the_legacy_angle_convention() {
        assertEquals("Lathe", lathe.name)
        assertEquals(MachineType.Lathe, lathe.machineType)
        assertEquals(PatternAngleConvention.Legacy, lathe.patternAngleConvention)
        val horizontal = MachineProfile.create(MachineType.HorizontalMill)
        assertEquals("Mill", horizontal.name)
        assertEquals(PatternAngleConvention.Standard, horizontal.patternAngleConvention)
    }

    @Test
    fun display_settings_defaults() {
        val display = mill.display
        assertEquals(3, display.readoutFormat.code)
        assertEquals(ReadoutFont.Dseg7, display.readoutFont)
        assertTrue(display.showSliders)
        assertFalse(display.shortPressZero)
        // One default for "Activate new sub-datum": the XML default true.
        assertTrue(display.activateNewSubDatum)
    }

    @Test
    fun function_buttons_and_the_head_tilt_and_compound_buttons_are_on() {
        val buttons = mill.functionButtons
        listOf(
            buttons.edgeFinder, buttons.zReference, buttons.radiusCompensation, buttons.heightCompensation,
            buttons.holeCircle, buttons.arbitraryHoleCircle, buttons.holeGrid, buttons.equidistantArray,
            buttons.dimensionMeasure, buttons.angleMeasure, buttons.arcCut, buttons.headTilt, buttons.compoundAngle,
        ).forEach { value -> assertTrue(value) }
    }

    @Test
    fun linear_axis_defaults() {
        listOf(Triple(mill.x, "X", 'x'), Triple(mill.y, "Y", 'y'), Triple(mill.z, "Z", 'z')).forEach { (axis, label, channel) ->
            assertTrue(axis.enabled)
            assertEquals(label, axis.label)
            assertEquals(channel, axis.primary.channel)
            // Invert false: the sign is +1. The weight is 1.0.
            assertEquals(1, axis.primary.sign)
            assertEquals(1.0, axis.primary.weight)
            // Secondary input false: no secondary input.
            assertNull(axis.secondary)
            assertEquals(
                listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits),
                axis.buttons.map { button -> button.mode },
            )
            assertEquals(listOf(0, 1, 2), axis.buttons.map { button -> button.mode.ordinal })
            assertEquals(listOf(true, true, true), axis.buttons.map { button -> button.showExpanded })
            assertEquals(listOf(true, false, false), axis.buttons.map { button -> button.showCollapsed })
        }
    }

    @Test
    fun angular_caliper_and_tachometer_defaults() {
        assertFalse(mill.angular.enabled)
        assertEquals("α", mill.angular.label)
        assertEquals('w', mill.angular.primary.channel)
        assertEquals(1, mill.angular.primary.sign)
        assertEquals(listOf(0, 1, 2), mill.angular.buttons.map { button -> button.mode.ordinal })
        assertFalse(mill.caliper.enabled)
        assertEquals('w', mill.caliper.input.channel)
        assertEquals(1, mill.caliper.input.sign)
        assertFalse(mill.tachometer.enabled)
        assertEquals("RPM", mill.tachometer.label)
        assertEquals(TachometerMode.Sensor, mill.tachometer.mode)
        assertEquals(listOf(105.0, 160.0, 250.0, 585.0, 898.0, 1400.0), mill.tachometer.presets)
        assertFalse(mill.tachometer.invert)
    }

    @Test
    fun probe_defaults_in_the_profile_the_probe_profile_and_the_adapter_profile() {
        assertFalse(mill.probe.enabled)
        assertEquals('p', mill.probe.channel)
        assertTrue(mill.probe.edgeFinderEnabled)
        assertFalse(mill.probe.toolSetterEnabled)
        val probe = ProbeProfile()
        assertEquals(0.0, probe.xOffsetUm)
        assertEquals(0.0, probe.yOffsetUm)
        assertEquals(1, probe.sampleCount)
        assertEquals(20.0, probe.spreadLimitUm)
        val adapter = AdapterProfile()
        // Probe debounce 0.2 s; probe invert false.
        assertEquals(200L, adapter.probeDebounceMs)
        assertEquals(ProbeContactType.Auto, adapter.probeContactType)
    }

    @Test
    fun link_defaults_baud_rate_115200_and_wifi_port_8080() {
        assertEquals(115_200, mill.usbBaudRate)
        assertEquals(115_200, AdapterAddress.UsbSerial().baudRate)
        assertEquals(0, AdapterAddress.UsbSerial().portIndex)
        assertEquals(8080, AdapterAddress.Tcp().port)
        assertEquals(listOf(9_600, 19_200, 38_400, 57_600, 115_200), AdapterAddress.UsbSerial.BAUD_RATES)
        assertFalse(mill.muteSounds)
    }

    @Test
    fun app_settings_defaults() {
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

    @Test
    fun encoder_profile_defaults() {
        Channels.forEach { input ->
            val profile = EncoderProfile.defaultFor(input)
            assertEquals(EncoderType.Scale, profile.type, "input $input")
            assertEquals(EncoderKind.Linear, profile.kind)
            // 25400 counts per inch, 1 µm for each count.
            assertEquals(25_400.0, profile.resolution)
            // Hysteresis 0, filter false, invert false.
            assertEquals(BacklashMode.None, profile.backlashMode)
            assertEquals(0L, profile.backlashCounts)
            assertFalse(profile.filter)
            assertFalse(profile.reversed)
            assertEquals(CalibrationStatus.NotCalibrated, profile.calibration.status)
        }
        val angular = EncoderProfile.defaultFor('w', EncoderKind.Angular)
        assertEquals(EncoderKind.Angular, angular.kind)
        assertEquals(1000.0, angular.resolution)
        val tachometer = EncoderProfile.defaultFor('t')
        assertEquals(EncoderType.RotaryEncoder, tachometer.type)
        assertEquals(EncoderKind.Angular, tachometer.kind)
        assertEquals(1.0, tachometer.resolution)
        assertEquals(setOf('x', 'y', 'z', 'w', 'u', 'v', 't'), AdapterProfile().encoders.keys)
    }

    @Test
    fun no_value_and_the_setup_reminder() {
        assertFalse(EncoderProfile.givesValue(null))
        assertFalse(EncoderProfile.givesValue(EncoderProfile.defaultFor('x').copy(kind = EncoderKind.Disabled)))
        assertFalse(EncoderProfile.givesValue(EncoderProfile.defaultFor('x').copy(resolution = 0.0)))
        assertTrue(EncoderProfile.givesValue(EncoderProfile.defaultFor('x')))
        assertTrue(EncoderProfile.needsSetupReminder(null))
        assertTrue(EncoderProfile.needsSetupReminder(EncoderProfile.defaultFor('x')))
        val typed = EncoderProfile.defaultFor('x').copy(calibration = EncoderProfile.defaultFor('x').calibration.copy(status = CalibrationStatus.UserSet))
        assertFalse(EncoderProfile.needsSetupReminder(typed))
    }

    @Test
    fun axis_state_defaults() {
        val state = AxisState()
        assertEquals(0.0, state.absoluteOffset)
        assertEquals(0.0, state.incrementalOffset)
        assertFalse(state.relative)
        assertEquals(0.0, state.toolOffset)
        assertFalse(state.toolOffsetActive)
        assertNull(state.metric)
        assertFalse(state.divisionUnits)
        assertNull(state.divisionsPerRev)
        assertFalse(state.surfaceSpeedMode)
        // The diameter mode is true on a lathe X only.
        val latheStates = lathe.workspaces.single().axisStates
        assertTrue(latheStates.getValue(AxisId.X).diameterMode)
        AxisId.entries.filter { it != AxisId.X }.forEach { axis -> assertFalse(latheStates.getValue(axis).diameterMode) }
        mill.workspaces.single().axisStates.values.forEach { axis -> assertFalse(axis.diameterMode) }
    }

    @Test
    fun workspace_defaults() {
        val workspace = mill.workspaces.single()
        assertEquals(AxisId.entries.toSet(), workspace.axisStates.keys)
        assertNull(workspace.activeSubDatumId)
        assertNull(workspace.activeToolId)
        assertEquals(0.0, workspace.toolXUm)
        assertEquals(0.0, workspace.toolZUm)
        assertEquals(0, workspace.toothCount)
        assertNull(workspace.proximity)
        assertNull(workspace.referenceImage)
        assertEquals(0, workspace.lastOrdinal)
        assertTrue(workspace.subDatums.isEmpty())
        assertTrue(workspace.guides.isEmpty())
        assertEquals(Plane.XY, workspace.plane)
        assertEquals(Plane.ZX, lathe.workspaces.single().plane)
        assertEquals("Default Workspace", Workspace.DEFAULT_NAME)
    }

    @Test
    fun tab_state_defaults() {
        val tabs = TabState()
        assertTrue(tabs.remembered.isEmpty())
        assertNull(tabs.selectedProfileId)
    }

    @Test
    fun a_sub_datum_has_the_angular_coordinate_A() {
        val point = SubDatum(name = "P", x = 1.0, a = 3600.0)
        assertEquals(3600.0, point.a)
        assertNull(SubDatum().a)
        // The name of a machine datum has 1 to 15 characters; an adapter alias 40 or less.
        assertEquals(15, MachineDatum.MAX_NAME_LENGTH)
        assertEquals(40, AdapterProfile.MAX_ALIAS_LENGTH)
    }

    @Test
    fun data_output_configuration_defaults() {
        val config = DataOutputConfig()
        assertFalse(config.enabled)
        assertEquals(BindInterface.WiFi, config.bindInterface)
        assertEquals(5800, config.port)
        assertEquals(DataOutputSecurity.Psk, config.security)
        assertEquals(DataOutputTransport.Tcp, config.transport)
        assertTrue(config.mqttTls)
        assertEquals(500L, config.intervalMs)
        assertEquals(20, config.maxRateHz)
        assertEquals(DataOutputActivation.OnDemand, config.activation)
        assertEquals(DataOutputCadence.OnChangeAndInterval, config.cadence)
        assertEquals(DataOutputFormat.Delimited, config.format)
        assertEquals(";", config.delimiter.text)
        assertFalse(config.labels)
        assertEquals(TimestampFormat.Iso8601, config.timestampFormat)
        assertEquals(DistanceUnit.Millimeter, config.distanceUnit)
        assertEquals(4, config.precision)
        assertEquals(25, config.fields.size)
        assertEquals(
            listOf("x_absolute", "y_absolute", "z_absolute"),
            config.fields.filter { field -> field.enabled }.map { field -> field.id.wireId },
        )
        // The 25 fields in their fixed order; the default label is the ID.
        assertEquals(
            listOf(
                "x_absolute", "x_incremental", "x_feed_rate", "y_absolute", "y_incremental", "y_feed_rate",
                "z_absolute", "z_incremental", "z_feed_rate", "angular_absolute", "angular_incremental",
                "angular_feed_rate", "spindle_rpm", "surface_speed", "probe_1", "probe_2", "probe_1_trigger_x",
                "probe_1_trigger_y", "probe_1_trigger_z", "probe_1_trigger_dir_x", "probe_1_trigger_dir_y",
                "probe_1_trigger_dir_z", "probe_1_trigger_time", "probe_1_trigger_seq", "probe_1_trigger_feed",
            ),
            config.fields.map { field -> field.id.wireId },
        )
        config.fields.forEach { field -> assertEquals(field.id.wireId, field.label) }
    }

    @Test
    fun length_settings_use_micrometers_with_the_factors_1000_and_25400() {
        assertEquals(1000.0, Units.UM_PER_MM)
        assertEquals(25_400.0, Units.UM_PER_INCH)
        assertEquals(1_296_000.0, Units.ARCSEC_PER_REVOLUTION)
        assertEquals(1000L, Units.NM_PER_UM)
    }

    @Test
    fun the_channel_values_of_the_settings_table() {
        // Primary channel `x y z w u v`; probe channel `p q`; the secondary channel `w`.
        assertEquals(listOf('x', 'y', 'z', 'w', 'u', 'v'), org.superdro.core.model.Channels.axisChannels)
        assertEquals(listOf('p', 'q'), org.superdro.core.model.Channels.probeChannels)
        listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis -> assertEquals('w', org.superdro.core.model.Channels.defaultSecondary(axis)) }
        assertNull(org.superdro.core.model.Channels.defaultSecondary(AxisId.Angular))
        assertEquals('t', org.superdro.core.model.Channels.TACHOMETER)
        "abcdefrs".forEach { letter -> assertFalse(org.superdro.core.model.Channels.isAxisChannel(letter) || org.superdro.core.model.Channels.isProbeChannel(letter), "$letter") }
    }

    @Test
    fun ids_are_uuid_texts() {
        repeat(5) { assertTrue(StoreFixtures.UUID_FORM.matches(newId())) }
    }

    private companion object {
        val Channels = listOf('x', 'y', 'z', 'w', 'u', 'v')
    }
}
