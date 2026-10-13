package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The defaults: one test for each row of the defaults table that `core:model` holds. The test
 * name gives the row.
 *
 * The rows of the resolution, the hysteresis, and the digital filter are in the encoder profile
 * of the adapter input. A new encoder profile has 25400 counts per inch.
 */
class SettingsDefaultsTest {

    private val mill = MachineProfile.create(MachineType.VerticalMill)
    private val lathe = MachineProfile.create(MachineType.Lathe)
    private val app = AppSettings()

    @Test
    fun profileNameIsMillOrLathe() {
        assertEquals("Mill", mill.name)
        assertEquals("Lathe", lathe.name)
        assertEquals("Mill", MachineProfile().name)
    }

    @Test
    fun machineTypeIsVerticalMill() {
        assertEquals(MachineType.VerticalMill, MachineProfile().machineType)
        assertEquals(MachineType.VerticalMill, MachineProfile.create().machineType)
    }

    @Test
    fun metricDefaultIsFalse() {
        assertFalse(mill.metricDefault)
    }

    @Test
    fun adapterLinkIsNone() {
        assertNull(mill.adapter)
    }

    @Test
    fun usbBaudRateIs115200() {
        assertEquals(115_200, AdapterAddress.UsbSerial().baudRate)
        assertEquals(listOf(9_600, 19_200, 38_400, 57_600, 115_200), AdapterAddress.UsbSerial.BAUD_RATES)
    }

    @Test
    fun wifiHostIsNone() {
        assertEquals("", AdapterAddress.Tcp().host)
    }

    @Test
    fun wifiPortIs8080() {
        assertEquals(8080, AdapterAddress.Tcp(host = "10.0.10.5").port)
    }

    @Test
    fun twoWayCommunicationIsTrue() {
        assertTrue(mill.twoWayCommunication)
    }

    @Test
    fun connectAtStartIsFalse() {
        assertFalse(mill.connectAtStart)
    }

    @Test
    fun adapterConfigurationCommandsIsFalse() {
        assertFalse(mill.adapterConfigurationCommands)
    }

    @Test
    fun useSharedToolLibraryIsFalse() {
        assertFalse(mill.useSharedToolLibrary)
    }

    @Test
    fun readoutFormatIs3() {
        assertEquals(ReadoutFormat.Format3, mill.display.readoutFormat)
        assertEquals(3, mill.display.readoutFormat.code)
        assertEquals(4, mill.display.readoutFormat.inchDecimals)
        assertEquals(3, mill.display.readoutFormat.mmDecimals)
        assertEquals(ReadoutFormat.Format2, ReadoutFormat.fromTouchDroCode(0))
        assertEquals(ReadoutFormat.Format3, ReadoutFormat.fromTouchDroCode(1))
        assertEquals(ReadoutFormat.Format5, ReadoutFormat.fromTouchDroCode(4))
    }

    @Test
    fun readoutFontIsDseg7() {
        assertEquals(ReadoutFont.Dseg7, mill.display.readoutFont)
        assertEquals(
            listOf("Dseg7", "PlatformMonospace", "JetBrainsMono", "System", "RobotoMono", "DroidSansMono", "Cousine"),
            ReadoutFont.entries.map { font -> font.name },
        )
    }

    @Test
    fun graphicalViewInPortraitIsBelowTheReadout() {
        assertEquals(GraphicalViewPlacement.BelowReadout, app.graphicalViewInPortrait)
    }

    @Test
    fun showPositionIndicatorsIsTrue() {
        assertTrue(mill.display.showSliders)
    }

    @Test
    fun shortPressZeroIsFalse() {
        assertFalse(mill.display.shortPressZero)
    }

    @Test
    fun activateNewSubDatumIsTrue() {
        assertTrue(mill.display.activateNewSubDatum)
    }

    @Test
    fun patternAngleConventionIsStandardOnAMillAndLegacyOnALathe() {
        assertEquals(PatternAngleConvention.Standard, mill.patternAngleConvention)
        assertEquals(PatternAngleConvention.Standard, MachineProfile.create(MachineType.HorizontalMill).patternAngleConvention)
        assertEquals(PatternAngleConvention.Legacy, lathe.patternAngleConvention)
    }

    @Test
    fun auxiliaryReadoutTypeIsTailstockWithNoVectoringAndAngle0() {
        assertEquals(AuxiliaryConfig(AuxiliaryType.Tailstock, vectoring = false, angleReferenceDegrees = 0), lathe.auxiliary)
    }

    @Test
    fun headTiltAndCompoundButtonsAreTrue() {
        assertTrue(mill.functionButtons.headTilt)
        assertTrue(mill.functionButtons.compoundAngle)
    }

    @Test
    fun threeDimensionalSubDatumsIsTrue() {
        assertTrue(mill.use3dSubDatums)
    }

    @Test
    fun sidePanelIsOff() {
        assertEquals(SidePanelMode.Off, mill.sidePanel)
    }

    @Test
    fun functionButtonsAreTrue() {
        val buttons = mill.functionButtons
        assertTrue(
            listOf(
                buttons.edgeFinder, buttons.zReference, buttons.radiusCompensation, buttons.heightCompensation,
                buttons.holeCircle, buttons.arbitraryHoleCircle, buttons.holeGrid, buttons.equidistantArray,
                buttons.dimensionMeasure, buttons.angleMeasure, buttons.arcCut,
            ).all { value -> value }
        )
    }

    @Test
    fun axisEnabledIsTrueForXYAndZ() {
        assertTrue(mill.x.enabled)
        assertTrue(mill.y.enabled)
        assertTrue(mill.z.enabled)
    }

    @Test
    fun axisLabelIsXYZ() {
        assertEquals(listOf("X", "Y", "Z"), listOf(mill.x.label, mill.y.label, mill.z.label))
    }

    @Test
    fun actionButtonsAreModes0And1And2() {
        val modes = mill.x.buttons.map { button -> button.mode }
        assertEquals(listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits), modes)
        assertEquals(listOf(0, 1, 2), modes.map { mode -> mode.ordinal })
    }

    @Test
    fun showWhenExpandedIsTrueTrueTrue() {
        assertEquals(listOf(true, true, true), mill.y.buttons.map { button -> button.showExpanded })
    }

    @Test
    fun showWhenCollapsedIsTrueFalseFalse() {
        assertEquals(listOf(true, false, false), mill.z.buttons.map { button -> button.showCollapsed })
    }

    @Test
    fun primaryChannelIsXYZ() {
        assertEquals(listOf('x', 'y', 'z'), listOf(mill.x.primary.channel, mill.y.primary.channel, mill.z.primary.channel))
    }

    @Test
    fun resolutionIs25400CountsPerInchForANewEncoderProfile() {
        listOf('x', 'y', 'z').forEach { channel ->
            val profile = EncoderProfile.defaultFor(channel)
            assertEquals(EncoderKind.Linear, profile.kind)
            assertEquals(25400.0, profile.resolution)
        }
        assertEquals(25400.0, AdapterProfile().encoders.getValue(mill.x.primary.channel).resolution)
    }

    @Test
    fun anInputWithNoEncoderProfileGivesNoValue() {
        assertFalse(EncoderProfile.givesValue(null))
        assertFalse(EncoderProfile.givesValue(EncoderProfile.defaultFor('x').copy(resolution = 0.0)))
        assertFalse(EncoderProfile.givesValue(EncoderProfile.defaultFor('x').copy(kind = EncoderKind.Disabled)))
        assertTrue(EncoderProfile.givesValue(EncoderProfile.defaultFor('x')))
    }

    @Test
    fun theSetupReminderShowsForNoProfileOrANotCalibratedProfile() {
        assertTrue(EncoderProfile.needsSetupReminder(null))
        assertTrue(EncoderProfile.needsSetupReminder(EncoderProfile.defaultFor('x')))
        val userSet = EncoderProfile.defaultFor('x').copy(calibration = Calibration(CalibrationStatus.UserSet))
        val calibrated = EncoderProfile.defaultFor('x').copy(calibration = Calibration(CalibrationStatus.Calibrated))
        assertFalse(EncoderProfile.needsSetupReminder(userSet))
        assertFalse(EncoderProfile.needsSetupReminder(calibrated))
    }

    @Test
    fun hysteresisIs0() {
        val profile = EncoderProfile.defaultFor('x')
        assertEquals(BacklashMode.None, profile.backlashMode)
        assertEquals(0L, profile.backlashCounts)
    }

    @Test
    fun digitalFilterIsFalse() {
        assertFalse(EncoderProfile.defaultFor('y').filter)
    }

    @Test
    fun invertIsFalse() {
        assertEquals(1, mill.x.primary.sign)
        assertEquals(1.0, mill.x.primary.weight)
    }

    @Test
    fun secondaryInputIsFalse() {
        assertNull(mill.x.secondary)
        assertNull(mill.y.secondary)
        assertNull(mill.z.secondary)
    }

    @Test
    fun secondaryChannelIsW() {
        assertEquals('w', Channels.DEFAULT_SECONDARY)
        assertEquals('w', Channels.defaultSecondary(AxisId.X))
        assertNull(Channels.defaultSecondary(AxisId.Angular))
    }

    @Test
    fun secondaryResolutionIs25400CountsPerInchWithNoHysteresisInvertOrFilter() {
        val profile = EncoderProfile.defaultFor(Channels.DEFAULT_SECONDARY)
        assertEquals(25400.0, profile.resolution)
        assertEquals(0L, profile.backlashCounts)
        assertFalse(profile.reversed)
        assertFalse(profile.filter)
    }

    @Test
    fun angularEnabledIsFalseAndTheLabelIsAlpha() {
        assertFalse(mill.angular.enabled)
        assertEquals("α", mill.angular.label)
    }

    @Test
    fun angularChannelIsWAndTheResolutionIs1000CountsPerRevolution() {
        assertEquals('w', mill.angular.primary.channel)
        val profile = EncoderProfile.defaultFor(mill.angular.primary.channel, EncoderKind.Angular)
        assertEquals(EncoderKind.Angular, profile.kind)
        assertEquals(1000.0, profile.resolution)
    }

    @Test
    fun angularInvertIsFalseAndTheButtonsAre0And1And2() {
        assertEquals(1, mill.angular.primary.sign)
        assertEquals(listOf(0, 1, 2), mill.angular.buttons.map { button -> button.mode.ordinal })
    }

    @Test
    fun caliperIsOffOnChannelWWithNoInvert() {
        assertFalse(mill.caliper.enabled)
        assertEquals('w', mill.caliper.input.channel)
        assertEquals(1, mill.caliper.input.sign)
    }

    @Test
    fun caliperResolutionIs25400CountsPerInchWithNoFilter() {
        val profile = EncoderProfile.defaultFor(mill.caliper.input.channel)
        assertEquals(25400.0, profile.resolution)
        assertFalse(profile.filter)
    }

    @Test
    fun tachometerEnabledIsFalseAndTheLabelIsRpm() {
        assertFalse(mill.tachometer.enabled)
        assertEquals("RPM", mill.tachometer.label)
    }

    @Test
    fun tachometerModeIsTheRpmSensor() {
        assertEquals(TachometerMode.Sensor, mill.tachometer.mode)
    }

    @Test
    fun spindleSpeedPresetsAre105To1400() {
        assertEquals(listOf(105.0, 160.0, 250.0, 585.0, 898.0, 1400.0), mill.tachometer.presets)
    }

    @Test
    fun tachometerPulsesPerRevolutionIs1() {
        // The pulses for each revolution are the resolution of the encoder profile of `t`.
        assertEquals(1.0, EncoderProfile.defaultFor('t').resolution)
        val old = SuperDroJson.decodeFromString(TachometerConfig.serializer(), """{"enabled":true,"ppu":4.0}""")
        assertEquals(TachometerConfig(enabled = true), old)
    }

    @Test
    fun tachometerInvertIsFalse() {
        assertFalse(mill.tachometer.invert)
    }

    @Test
    fun probeIsOffOnChannelPWithNoInvert() {
        assertFalse(mill.probe.enabled)
        assertEquals('p', mill.probe.channel)
        assertEquals(ProbeContactType.Auto, AdapterProfile().probeContactType)
    }

    @Test
    fun probeDebounceIs200Ms() {
        assertEquals(200L, AdapterProfile().probeDebounceMs)
    }

    @Test
    fun probeEdgeFinderIsTrue() {
        assertTrue(mill.probe.edgeFinderEnabled)
    }

    @Test
    fun probeXAndYOffsetsAre0() {
        val probe = ProbeProfile()
        assertEquals(0.0, probe.xOffsetUm)
        assertEquals(0.0, probe.yOffsetUm)
    }

    @Test
    fun edgeFinderSampleCountIs1() {
        assertEquals(1, ProbeProfile().sampleCount)
        assertEquals(1..10, ProbeProfile.MIN_SAMPLE_COUNT..ProbeProfile.MAX_SAMPLE_COUNT)
    }

    @Test
    fun maximumSpreadIs20Um() {
        assertEquals(20.0, ProbeProfile().spreadLimitUm)
    }

    @Test
    fun toolSetterWithTheProbeIsFalse() {
        assertFalse(mill.probe.toolSetterEnabled)
    }

    @Test
    fun themeModeIsSystem() {
        assertEquals(ThemeMode.System, app.themeMode)
        assertEquals(listOf(ThemeMode.System, ThemeMode.Dark), ThemeMode.entries)
    }

    @Test
    fun largeButtonsIsFalse() {
        assertFalse(app.largeButtons)
    }

    @Test
    fun muteAllSoundsIsFalse() {
        assertFalse(app.muteAllSounds)
        assertFalse(mill.muteSounds)
    }

    @Test
    fun muteScopeIsOneSwitchForTheApp() {
        assertEquals(MuteScope.App, app.muteScope)
    }

    @Test
    fun fullScreenIsTrue() {
        assertTrue(app.fullScreen)
    }

    @Test
    fun partialWakeLockIsTrue() {
        assertTrue(app.wakeLockWhileLinked)
    }

    @Test
    fun showHintsIsTrue() {
        assertTrue(app.showHints)
        assertEquals(emptySet(), app.shownHints)
    }

    @Test
    fun showTheSetupReminderIsTrue() {
        assertTrue(app.showSetupReminder)
    }

    @Test
    fun sendGetSigOnceIsFalse() {
        assertFalse(app.sendGetSigOnce)
    }
}
