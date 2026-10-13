package org.superdro.core.ui.settings.machine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MuteScope
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFont
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SidePanelMode
import org.superdro.core.model.SubDatum
import org.superdro.core.model.TachometerMode
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.model.TransportKind
import org.superdro.core.readout.settings.SettingsResult
import org.superdro.core.store.repository.MismatchKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.text.UiText

/**
 * The machine settings of a profile: the defaults, the validation rules
 * with their exact texts, the input conflict warnings, the change at once, and the link values.
 */
class MachineSettingsPresenterTest {

    private class Harness(private val scope: TestScope, appSettings: AppSettings = AppSettings()) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val settings = MutableStateFlow(appSettings)
        val messages = mutableListOf<UiText>()
        lateinit var profile: MachineProfile
        lateinit var presenter: MachineSettingsPresenter

        suspend fun start(machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill")): MachineSettingsPresenter {
            profile = store.machines.createProfile(machine).valueOrNull() ?: error("The profile was not stored.")
            presenter = MachineSettingsPresenter(
                profileId = profile.id,
                machines = store.machines,
                adapters = store.adapters,
                connections = connections,
                appSettings = settings,
                context = scope.testPresenterContext(),
                showMessage = { text -> messages += text },
            )
            scope.advanceUntilIdle()
            return presenter
        }

        suspend fun stored(): MachineProfile = store.machines.loadProfile(profile.id)!!

        val state: MachineSettingsUiState get() = presenter.uiState.value

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun aNewMillShowsTheOneDefaultOfEachRowOfTheTable() = runTest {
        val harness = Harness(this)
        harness.start()
        val state = harness.state
        assertTrue(state.loaded)
        val profile = assertNotNull(state.profile)
        assertEquals("Mill", state.textOf(SettingsTextField.Name))
        assertEquals(MachineType.VerticalMill, profile.machineType)
        assertFalse(profile.metricDefault)
        assertNull(profile.adapter)
        assertEquals(115_200, profile.usbBaudRate)
        assertEquals("8080", state.textOf(SettingsTextField.WifiPort))
        assertTrue(profile.twoWayCommunication)
        assertFalse(profile.adapterConfigurationCommands)
        assertFalse(profile.useSharedToolLibrary)
        // Display: format 3, DSEG7, sliders, short press zero off, activate new sub-datum on.
        assertEquals(ReadoutFormat.Format3, profile.display.readoutFormat)
        assertEquals(ReadoutFont.Dseg7, profile.display.readoutFont)
        assertTrue(profile.display.showSliders)
        assertFalse(profile.display.shortPressZero)
        assertTrue(profile.display.activateNewSubDatum)
        assertEquals(PatternAngleConvention.Standard, profile.patternAngleConvention)
        assertTrue(profile.use3dSubDatums)
        assertEquals(SidePanelMode.Off, profile.sidePanel)
        assertFalse(profile.display.cameraKeepOverlays)
        assertEquals("default", profile.display.cameraCrosshairColor)
        // Function buttons: on each, also head tilt and compound.
        FunctionSwitch.entries.forEach { button -> assertTrue(button.valueIn(profile.functionButtons), button.name) }
        // Linear axes: enabled, label, buttons 0 1 2, expanded true true true, collapsed true false false.
        listOf(AxisId.X to 'x', AxisId.Y to 'y', AxisId.Z to 'z').forEach { (axis, channel) ->
            val config = profile.linear(axis)
            assertTrue(config.enabled)
            assertEquals(axis.name, config.label)
            assertEquals(listOf(AxisButtonMode.SetZero, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits), config.buttons.map { it.mode })
            assertEquals(listOf(true, true, true), config.buttons.map { it.showExpanded })
            assertEquals(listOf(true, false, false), config.buttons.map { it.showCollapsed })
            assertEquals(channel, config.primary.channel)
            assertEquals(1, config.primary.sign)
            assertNull(config.secondary)
        }
        // Angular, caliper, tachometer.
        assertFalse(profile.angular.enabled)
        assertEquals("α", state.textOf(SettingsTextField.LabelAngular))
        assertEquals('w', profile.angular.primary.channel)
        assertFalse(profile.caliper.enabled)
        assertEquals('w', profile.caliper.input.channel)
        assertFalse(profile.tachometer.enabled)
        assertEquals("RPM", state.textOf(SettingsTextField.LabelTachometer))
        assertEquals(TachometerMode.Sensor, profile.tachometer.mode)
        assertEquals("105 160 250 585 898 1400", state.textOf(SettingsTextField.Presets))
        assertFalse(profile.tachometer.invert)
        // Probe: off, edge finder on, tool setter off.
        assertFalse(profile.probe.enabled)
        assertTrue(profile.probe.edgeFinderEnabled)
        assertFalse(profile.probe.toolSetterEnabled)
        harness.close()
    }

    @Test
    fun aLatheHasLegacyAnglesTheAuxiliaryDefaultsAndTheCaliperGroupButNoProbeGroup() = runTest {
        val harness = Harness(this)
        harness.start(MachineProfile.create(MachineType.Lathe))
        val state = harness.state
        val profile = state.profile!!
        assertEquals("Lathe", profile.name)
        assertEquals(PatternAngleConvention.Legacy, profile.patternAngleConvention)
        assertEquals(AuxiliaryType.Tailstock, profile.auxiliary.type)
        assertFalse(profile.auxiliary.vectoring)
        assertEquals(0, profile.auxiliary.angleReferenceDegrees)
        assertTrue(MachineSettingsGroup.Caliper in state.groups)
        assertFalse(MachineSettingsGroup.Probe in state.groups)
        assertFalse(MachineSettingsGroup.Caliper in MachineSettingsGroup.groupsFor(MachineType.VerticalMill))
        assertTrue(MachineSettingsGroup.Probe in MachineSettingsGroup.groupsFor(MachineType.HorizontalMill))
        // A lathe always uses LEGACY: the setting does not change it.
        harness.presenter.setPatternAngleConvention(PatternAngleConvention.Standard)
        advanceUntilIdle()
        assertEquals(PatternAngleConvention.Legacy, harness.stored().patternAngleConvention)
        harness.close()
    }

    @Test
    fun theCaliperSwitchOfALatheStaysOnAfterANewLoad() = runTest {
        val harness = Harness(this)
        harness.start(MachineProfile.create(MachineType.Lathe))
        harness.presenter.setAxisEnabled(AxisId.Caliper, true)
        advanceUntilIdle()
        harness.presenter.close()
        val again = MachineSettingsPresenter(harness.profile.id, harness.store.machines, harness.store.adapters, harness.connections, harness.settings, testPresenterContext())
        advanceUntilIdle()
        assertTrue(again.uiState.value.profile!!.caliper.enabled)
        again.close()
        harness.store.close()
    }

    @Test
    fun theLastOutputAxisCannotBeDisabled() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setAxisEnabled(AxisId.X, false)
        harness.presenter.setAxisEnabled(AxisId.Y, false)
        advanceUntilIdle()
        harness.presenter.setAxisEnabled(AxisId.Z, false)
        advanceUntilIdle()
        assertEquals(listOf("At least one output axis must be enabled"), harness.messages.map { it.english() })
        val stored = harness.stored()
        assertEquals(listOf(false, false, true), listOf(stored.x.enabled, stored.y.enabled, stored.z.enabled))
        // With the angular axis on, Z can go off.
        harness.presenter.setAxisEnabled(AxisId.Angular, true)
        advanceUntilIdle()
        harness.presenter.setAxisEnabled(AxisId.Z, false)
        advanceUntilIdle()
        assertFalse(harness.stored().z.enabled)
        assertEquals(1, harness.messages.size)
        harness.close()
    }

    @Test
    fun theSecondaryInputGetsWAndRefusesThePrefixOfThePrimary() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setSecondaryEnabled(AxisId.Z, true)
        advanceUntilIdle()
        assertEquals('w', harness.stored().z.secondary?.channel)
        harness.presenter.setPrimaryChannel(AxisId.Z, 'w')
        advanceUntilIdle()
        assertEquals(listOf("Primary and secondary inputs can't use the same prefix"), harness.messages.map { it.english() })
        assertEquals('z', harness.stored().z.primary.channel)
        harness.presenter.setSecondaryChannel(AxisId.Z, 'z')
        advanceUntilIdle()
        assertEquals("Primary and secondary inputs can't use the same prefix", harness.messages.last().english())
        assertEquals('w', harness.stored().z.secondary?.channel)
        // Off: no channel and no invert.
        harness.presenter.setSecondaryInvert(AxisId.Z, true)
        advanceUntilIdle()
        assertEquals(-1, harness.stored().z.secondary?.sign)
        harness.presenter.setSecondaryEnabled(AxisId.Z, false)
        advanceUntilIdle()
        assertNull(harness.stored().z.secondary)
        // A primary on w gives the default primary channel to a new secondary input.
        harness.presenter.setPrimaryChannel(AxisId.Y, 'w')
        advanceUntilIdle()
        harness.presenter.setSecondaryEnabled(AxisId.Y, true)
        advanceUntilIdle()
        assertEquals('y', harness.stored().y.secondary?.channel)
        harness.close()
    }

    @Test
    fun aLetterOnTwoAxesAndTheBlockedCompoundProjectionGiveWarnings() = runTest {
        val harness = Harness(this)
        harness.start()
        assertEquals(emptyList(), harness.state.conflicts)
        harness.presenter.setPrimaryChannel(AxisId.X, 'y')
        advanceUntilIdle()
        assertEquals(listOf("Input Y is used by X, Y. These axes show the same value."), harness.state.conflicts.map { it.english() })
        harness.close()

        val lathe = Harness(this)
        lathe.start(MachineProfile.create(MachineType.Lathe))
        lathe.presenter.setAxisEnabled(AxisId.Y, true)
        lathe.presenter.setAuxiliaryType(AuxiliaryType.CompoundSlide)
        lathe.presenter.setAuxiliaryVectoring(true)
        lathe.presenter.setPrimaryChannel(AxisId.Y, 'x')
        advanceUntilIdle()
        assertTrue(
            "The compound slide uses input X, which X or Z also uses. The compound projection stays off." in lathe.state.conflicts.map { it.english() },
            lathe.state.conflicts.map { it.english() }.toString(),
        )
        lathe.close()
    }

    @Test
    fun theSampleCountOfAnEdgeFinderIsAWholeNumberFrom1To10() {
        assertEquals(SettingsResult.Accepted(1), SampleCountField.check("1"))
        assertEquals(SettingsResult.Accepted(10), SampleCountField.check(" 10 "))
        listOf("0", "11", "2.5", "", "abc").forEach { text ->
            assertEquals("Number of edge finder samples must be a number between 1 and 10 (inclusive)", SampleCountField.message(text)?.english(), text)
        }
        assertNull(SampleCountField.message("5"))
    }

    @Test
    fun theTachometerModeShowsThePulsesOrThePresetsOrNeither() = runTest {
        val harness = Harness(this)
        harness.start()
        assertTrue(harness.state.tachometerFields.showPulses)
        assertFalse(harness.state.tachometerFields.showPresets)
        harness.presenter.setTachometerMode(TachometerMode.Preset)
        advanceUntilIdle()
        assertFalse(harness.state.tachometerFields.showPulses)
        assertTrue(harness.state.tachometerFields.showPresets)
        harness.presenter.setTachometerMode(TachometerMode.Direct)
        advanceUntilIdle()
        assertFalse(harness.state.tachometerFields.showPulses)
        assertFalse(harness.state.tachometerFields.showPresets)
        harness.close()
    }

    @Test
    fun thePresetsAreNumbersSeparatedBySpaces() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setPresets("100 200,5  0 1500")
        advanceUntilIdle()
        assertEquals(listOf(100.0, 200.5, 0.0, 1500.0), harness.stored().tachometer.presets)
        assertNull(harness.state.errors[SettingsTextField.Presets])
        harness.presenter.setPresets("100 fast")
        advanceUntilIdle()
        assertEquals("Type numbers of 0 or more, separated by spaces.", harness.state.errors[SettingsTextField.Presets]?.english())
        assertEquals(listOf(100.0, 200.5, 0.0, 1500.0), harness.stored().tachometer.presets)
        assertEquals("100 fast", harness.state.textOf(SettingsTextField.Presets))
        assertEquals("105 160 250 585 898 1400", PresetsText.format(listOf(105.0, 160.0, 250.0, 585.0, 898.0, 1400.0)))
        harness.close()
    }

    @Test
    fun aNewPulsesValueIsInTheEncoderProfileOfTAndSetsTheTachometerOffsetsTo0() = runTest {
        val harness = Harness(this)
        val adapter = harness.store.adapters.saveAdapterProfile(AdapterProfile(alias = "Adapter")).valueOrNull()!!
        val base = MachineProfile.create(MachineType.VerticalMill, "Mill")
        val workspace = base.workspaces.first().let { it.copy(axisStates = it.axisStates + (AxisId.Tachometer to AxisState(absoluteOffset = 120.0, incrementalOffset = 30.0))) }
        harness.start(base.copy(adapterProfileId = adapter.id, workspaces = listOf(workspace), activeWorkspaceId = workspace.id))
        assertEquals(1.0, harness.state.tachometerPulses)
        assertEquals("1", harness.state.textOf(SettingsTextField.Pulses))
        harness.presenter.setPulses("0")
        advanceUntilIdle()
        assertEquals("Type a number more than 0.", harness.state.errors[SettingsTextField.Pulses]?.english())
        assertEquals(1.0, harness.store.adapters.loadAdapterProfile(adapter.id)!!.encoders[Channels.T]?.resolution)
        harness.presenter.setPulses("4")
        advanceUntilIdle()
        assertEquals(4.0, harness.store.adapters.loadAdapterProfile(adapter.id)!!.encoders[Channels.T]?.resolution)
        assertEquals(4.0, harness.state.tachometerPulses)
        val tach = harness.store.machines.loadWorkspace(workspace.id)!!.axisStates.getValue(AxisId.Tachometer)
        assertEquals(0.0, tach.absoluteOffset)
        assertEquals(0.0, tach.incrementalOffset)
        harness.close()
    }

    @Test
    fun theProbeSwitchShowsTheEdgeFinderSettings() = runTest {
        val harness = Harness(this)
        harness.start()
        assertFalse(harness.state.edgeFinderVisible)
        harness.presenter.setProbeEnabled(true)
        advanceUntilIdle()
        assertTrue(harness.state.edgeFinderVisible)
        harness.presenter.setEdgeFinderEnabled(false)
        harness.presenter.setToolSetterEnabled(true)
        advanceUntilIdle()
        val probe = harness.stored().probe
        assertEquals(Triple(true, false, true), Triple(probe.enabled, probe.edgeFinderEnabled, probe.toolSetterEnabled))
        harness.close()
    }

    @Test
    fun theNameMustBeUnique() = runTest {
        val harness = Harness(this)
        harness.store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Lathe"))
        harness.start()
        harness.presenter.setName("lathe")
        advanceUntilIdle()
        assertEquals("A profile with this name already exists.", harness.state.errors[SettingsTextField.Name]?.english())
        assertEquals("Mill", harness.stored().name)
        harness.presenter.setName("Bridgeport")
        advanceUntilIdle()
        assertNull(harness.state.errors[SettingsTextField.Name])
        assertEquals("Bridgeport", harness.stored().name)
        harness.close()
    }

    @Test
    fun aBlankLabelIsNotWritten() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setLabel(AxisId.X, " ")
        advanceUntilIdle()
        assertEquals("Type a label.", harness.state.errors[SettingsTextField.LabelX]?.english())
        assertEquals("X", harness.stored().x.label)
        harness.presenter.setLabel(AxisId.X, "X1")
        advanceUntilIdle()
        assertEquals("X1", harness.stored().x.label)
        harness.close()
    }

    @Test
    fun theButtonsBelongToTheAxisAndNotToTheInputLetter() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setButtonMode(AxisId.X, 0, AxisButtonMode.Hold)
        harness.presenter.setButtonShowCollapsed(AxisId.X, 1, true)
        harness.presenter.setButtonShowExpanded(AxisId.X, 2, false)
        // "Divide circle" is only for the angular axis.
        harness.presenter.setButtonMode(AxisId.X, 1, AxisButtonMode.DivideCircle)
        harness.presenter.setPrimaryChannel(AxisId.X, 'u')
        advanceUntilIdle()
        val stored = harness.stored()
        assertEquals(listOf(AxisButtonMode.Hold, AxisButtonMode.ToggleIncremental, AxisButtonMode.ToggleUnits), stored.x.buttons.map { it.mode })
        assertEquals(false, stored.x.buttons[2].showExpanded)
        assertEquals(true, stored.x.buttons[1].showCollapsed)
        assertEquals(AxisButtonMode.SetZero, stored.z.buttons[0].mode)
        assertEquals('u', stored.x.primary.channel)
        assertTrue(AxisButtonMode.DivideCircle in buttonModesFor(AxisId.Angular))
        assertFalse(AxisButtonMode.DivideCircle in buttonModesFor(AxisId.X))
        harness.close()
    }

    @Test
    fun aChangeIsInTheStoreAtOnceAndTheScreenFollowsTheFlow() = runTest {
        val harness = Harness(this)
        harness.start()
        val presenter = harness.presenter
        harness.presenter.setInvert(AxisId.Y, true)
        harness.presenter.setPrimaryChannel(AxisId.Z, 'v')
        advanceUntilIdle()
        // The flow that DeviceSetupSource reads gives the new values (the session uses them at once).
        val flowValue = harness.store.machines.profile(harness.profile.id).first()!!
        assertEquals(-1, flowValue.y.primary.sign)
        assertEquals('v', flowValue.z.primary.channel)
        // The same presenter shows the change: no screen starts again.
        assertTrue(presenter === harness.presenter)
        assertEquals('v', harness.state.profile!!.z.primary.channel)
        // A change from a different place also shows at once.
        harness.store.machines.updateProfile(harness.profile.id) { it.copy(x = it.x.copy(label = "Q")) }
        advanceUntilIdle()
        assertEquals("Q", harness.state.textOf(SettingsTextField.LabelX))
        harness.close()
    }

    @Test
    fun theMuteSwitchOfAMachineFollowsTheMuteScope() = runTest {
        val harness = Harness(this)
        harness.start()
        assertEquals(MuteScope.App, harness.state.muteScope)
        harness.settings.value = AppSettings(muteScope = MuteScope.EachMachine)
        advanceUntilIdle()
        assertEquals(MuteScope.EachMachine, harness.state.muteScope)
        harness.presenter.setMuteSounds(true)
        advanceUntilIdle()
        assertTrue(harness.stored().muteSounds)
        harness.close()
    }

    @Test
    fun theDisplayAndTheFunctionSwitchesWriteTheirOwnKey() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setReadoutFormat(ReadoutFormat.Format5)
        harness.presenter.setReadoutFont(ReadoutFont.Cousine)
        harness.presenter.setShowSliders(false)
        harness.presenter.setShortPressZero(true)
        harness.presenter.setActivateNewSubDatum(false)
        harness.presenter.setPatternAngleConvention(PatternAngleConvention.Legacy)
        harness.presenter.setCameraKeepOverlays(true)
        harness.presenter.setCameraCrosshairColor("dark_green")
        harness.presenter.setFunctionButton(FunctionSwitch.EdgeFinder, false)
        harness.presenter.setSidePanel(SidePanelMode.Tools)
        harness.presenter.setUse3dSubDatums(false)
        harness.presenter.setUseSharedToolLibrary(true)
        harness.presenter.setMetricDefault(true)
        advanceUntilIdle()
        val stored = harness.stored()
        assertEquals(ReadoutFormat.Format5, stored.display.readoutFormat)
        assertEquals(ReadoutFont.Cousine, stored.display.readoutFont)
        assertFalse(stored.display.showSliders)
        assertTrue(stored.display.shortPressZero)
        assertFalse(stored.display.activateNewSubDatum)
        assertEquals(PatternAngleConvention.Legacy, stored.patternAngleConvention)
        assertTrue(stored.display.cameraKeepOverlays)
        assertEquals("dark_green", stored.display.cameraCrosshairColor)
        // The edge finder switch changes only the edge finder button, not the Z reference.
        assertFalse(stored.functionButtons.edgeFinder)
        assertTrue(stored.functionButtons.zReference)
        assertEquals(SidePanelMode.Tools, stored.sidePanel)
        assertFalse(stored.use3dSubDatums)
        assertTrue(stored.useSharedToolLibrary)
        assertTrue(stored.metricDefault)
        // The function switches of a lathe: no mill buttons (both mill types show them).
        assertFalse(FunctionSwitch.EdgeFinder.appliesTo(MachineType.Lathe))
        assertTrue(FunctionSwitch.EdgeFinder.appliesTo(MachineType.HorizontalMill))
        assertTrue(FunctionSwitch.CompoundAngle.appliesTo(MachineType.Lathe))
        assertFalse(FunctionSwitch.HeadTilt.appliesTo(MachineType.Lathe))
        harness.close()
    }

    @Test
    fun theWifiHostCanBeAnIpv4AnIpv6OrAHostNameAndThePortIs1To65535() = runTest {
        val harness = Harness(this)
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(adapter = AdapterAddress.Tcp("192.168.4.1", 8080)))
        assertEquals(TransportKind.Tcp, harness.state.linkType)
        assertEquals("192.168.4.1", harness.state.textOf(SettingsTextField.WifiHost))
        harness.presenter.setWifiHost("dro-adapter.local")
        advanceUntilIdle()
        assertEquals(AdapterAddress.Tcp("dro-adapter.local", 8080), harness.stored().adapter)
        harness.presenter.setWifiHost("fe80::1")
        advanceUntilIdle()
        assertEquals(AdapterAddress.Tcp("fe80::1", 8080), harness.stored().adapter)
        harness.presenter.setWifiPort("0")
        advanceUntilIdle()
        assertNotNull(harness.state.errors[SettingsTextField.WifiPort])
        assertEquals(8080, (harness.stored().adapter as AdapterAddress.Tcp).port)
        harness.presenter.setWifiPort("65535")
        advanceUntilIdle()
        assertNull(harness.state.errors[SettingsTextField.WifiPort])
        assertEquals(AdapterAddress.Tcp("fe80::1", 65535), harness.stored().adapter)
        harness.presenter.setWifiHost("bad host!")
        advanceUntilIdle()
        assertNotNull(harness.state.errors[SettingsTextField.WifiHost])
        assertEquals(AdapterAddress.Tcp("fe80::1", 65535), harness.stored().adapter)
        harness.close()
    }

    @Test
    fun aNewBluetoothAddressGoesToTheConnectedSessionAtOnce() = runTest {
        val harness = Harness(this)
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(adapter = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")))
        harness.connections.add(FakeDeviceSession(DeviceId(harness.profile.id), "Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), SessionState.Connected(0L)))
        harness.presenter.setBluetoothAddress("98-d3-31-f5-12-0a")
        advanceUntilIdle()
        assertEquals(AdapterAddress.BluetoothSpp("98:D3:31:F5:12:0A"), harness.stored().adapter)
        assertEquals(listOf("connect ${harness.profile.id}"), harness.connections.calls)
        harness.presenter.setBluetoothAddress("98:D3:31")
        advanceUntilIdle()
        assertEquals(
            "Type six pairs of hex digits with colons, for example 24:6F:28:A1:B2:C3.",
            harness.state.errors[SettingsTextField.BluetoothAddress]?.english(),
        )
        assertEquals(AdapterAddress.BluetoothSpp("98:D3:31:F5:12:0A"), harness.stored().adapter)
        harness.close()
    }

    @Test
    fun theBaudRateOfTheProfileAndOfTheUsbLinkStayEqual() = runTest {
        val harness = Harness(this)
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(adapter = AdapterAddress.UsbSerial(0x0403, 0x6001, serial = "A1")))
        assertEquals(listOf(9_600, 19_200, 38_400, 57_600, 115_200), AdapterAddress.UsbSerial.BAUD_RATES)
        harness.presenter.setUsbBaudRate(9_600)
        advanceUntilIdle()
        val stored = harness.stored()
        assertEquals(9_600, stored.usbBaudRate)
        assertEquals(9_600, (stored.adapter as AdapterAddress.UsbSerial).baudRate)
        // A rate that is not in the list changes nothing.
        harness.presenter.setUsbBaudRate(4_800)
        advanceUntilIdle()
        assertEquals(9_600, harness.stored().usbBaudRate)
        assertEquals("0403:6001 A1", usbText(stored.adapter as AdapterAddress.UsbSerial))
        harness.close()
    }

    @Test
    fun theTwoWayAndTheConfigurationCommandSwitchesWriteTheProfile() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setTwoWayCommunication(false)
        harness.presenter.setAdapterConfigurationCommands(true)
        advanceUntilIdle()
        val stored = harness.stored()
        assertFalse(stored.twoWayCommunication)
        assertTrue(stored.adapterConfigurationCommands)
        harness.close()
    }

    @Test
    fun aTypeChangeShowsTheDataThatDoesNotFitAndArchivesIt() = runTest {
        val harness = Harness(this)
        val mill = MachineProfile.create(MachineType.VerticalMill, "Mill")
        val workspace = mill.workspaces.first().copy(subDatums = listOf(SubDatum(name = "P1", x = 1000.0, y = 2000.0)))
        harness.start(mill.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id, tools = listOf(Tool(name = "End mill", kind = ToolKind.Mill))))
        harness.presenter.setMachineType(MachineType.Lathe)
        advanceUntilIdle()
        val question = assertNotNull(harness.state.typeChange)
        assertEquals(MachineType.Lathe, question.plan.to)
        assertEquals(mapOf(MismatchKind.ProfileTools to 1, MismatchKind.SubDatumYCoordinates to 1), question.plan.mismatches.associate { it.kind to it.count })
        assertEquals(listOf("Tools of the other machine kind: 1", "Sub-datums with a Y coordinate: 1"), question.mismatchTexts.map { it.english() })
        assertEquals(MachineType.VerticalMill, harness.stored().machineType)
        harness.presenter.confirmTypeChange()
        advanceUntilIdle()
        assertNull(harness.state.typeChange)
        assertEquals(MachineType.Lathe, harness.stored().machineType)
        assertEquals(listOf("The machine type of Mill is now Lathe."), harness.messages.map { it.english() })
        assertEquals(listOf(MachineSettingsGroup.Caliper in harness.state.groups), listOf(true))
        harness.close()
    }

    @Test
    fun aTypeChangeCanBeCancelled() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.setMachineType(MachineType.HorizontalMill)
        advanceUntilIdle()
        assertNotNull(harness.state.typeChange)
        harness.presenter.cancelTypeChange()
        advanceUntilIdle()
        assertNull(harness.state.typeChange)
        assertEquals(MachineType.VerticalMill, harness.stored().machineType)
        // The same type asks nothing.
        harness.presenter.setMachineType(MachineType.VerticalMill)
        advanceUntilIdle()
        assertNull(harness.state.typeChange)
        harness.close()
    }

    @Test
    fun theGroupStaysWhenItFitsTheMachineType() = runTest {
        val harness = Harness(this)
        harness.start()
        harness.presenter.selectGroup(MachineSettingsGroup.Probe)
        advanceUntilIdle()
        assertEquals(MachineSettingsGroup.Probe, harness.state.group)
        harness.store.machines.changeMachineType(harness.profile.id, MachineType.Lathe)
        advanceUntilIdle()
        assertEquals(MachineSettingsGroup.Machine, harness.state.group)
        harness.close()
    }
}
