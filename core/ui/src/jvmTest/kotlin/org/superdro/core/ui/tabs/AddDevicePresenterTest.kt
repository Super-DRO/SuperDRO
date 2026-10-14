package org.superdro.core.ui.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.PairedListState
import org.superdro.core.connection.PermissionKind
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.adddevice.AddDevicePresenter
import org.superdro.core.ui.tabs.adddevice.AddDeviceStep
import org.superdro.core.ui.tabs.adddevice.ProfileSource
import org.superdro.core.ui.tabs.adddevice.SetupInput
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.model.SuperDroJson
import kotlinx.coroutines.test.advanceUntilIdle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.tabs.adddevice.ADD_DEVICE_PAIR_NEW_TAG
import org.superdro.core.ui.tabs.adddevice.ADD_DEVICE_RFCOMM_HINT_TAG
import org.superdro.core.ui.tabs.adddevice.AddDeviceSheet
import org.superdro.core.ui.theme.AppTheme

/** The add-device sheet with the real stores and a fake connection manager. */
class AddDevicePresenterTest {

    private class Harness(
        scope: TestScope,
        settings: AppSettings = AppSettings(),
        debug: DebugAdapter = NoDebugAdapter,
        sharedSettings: AppSettingsStore? = null,
        rfcommBindHint: Boolean = false,
    ) {
        val store = UiStoreFixture(scope)
        val settingsStore: AppSettingsStore = sharedSettings ?: store.appSettings
        val firstConnect = FirstConnectRequests(settingsStore)
        val connections = FakeConnections()
        val requests = RecordingRequests()
        val bluetooth = FakeDiscovery(
            TransportKind.BluetoothSpp,
            listOf(DiscoveredAdapter(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05", "24:6F:28:A1:B2:C3")),
        )
        val usb = FakeDiscovery(
            TransportKind.UsbSerial,
            listOf(DiscoveredAdapter(AdapterAddress.UsbSerial(0x0403, 0x6001), "FT232R", "0403:6001", needsPermission = true)),
        )
        val capabilities = FakeCapabilities()
        val presenter = AddDevicePresenter(
            connections = connections,
            tabStore = store.tabs,
            machines = store.machines,
            adapters = store.adapters,
            appSettings = settingsFlow(settings),
            discoveries = listOf(bluetooth, usb),
            capabilities = capabilities,
            debugAdapter = debug,
            requests = requests,
            firstConnect = firstConnect,
            context = scope.testPresenterContext(),
            rfcommBindHint = rfcommBindHint,
        )

        val state get() = presenter.uiState.value

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun theLinkStepListsThePairedAndTheAttachedAdapters() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertEquals(listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial), harness.state.sections.map { it.kind })
        assertEquals(listOf("HC-05"), harness.state.sections[0].options.map { it.name })
        assertEquals(listOf("FT232R"), harness.state.sections[1].options.map { it.name })
        assertTrue(harness.state.wifiShown)
        assertNull(harness.state.demo)
        harness.presenter.pairNewAdapter()
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.OpenBluetoothSettings), harness.requests.sent)
        harness.close()
    }

    @Test
    fun theRfcommHintShowsOnlyWhenThePlatformAsksForIt() = runTest {
        val desktop = Harness(this, rfcommBindHint = true)
        runCurrent()
        assertTrue(desktop.state.rfcommBindHint)
        desktop.close()
        val android = Harness(this)
        runCurrent()
        assertTrue(!android.state.rfcommBindHint)
        android.close()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theLinkStepShowsTheRfcommHintOnTheLinuxDesktop() = runComposeUiTest {
        val scope = TestScope()
        val harness = Harness(scope, rfcommBindHint = true)
        scope.testScheduler.advanceUntilIdle()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) {
                    val state by harness.presenter.uiState.collectAsState()
                    AddDeviceSheet(state, harness.presenter, onDismiss = {})
                }
            }
        }
        onNodeWithTag(ADD_DEVICE_RFCOMM_HINT_TAG).assertTextContains("sudo rfcomm bind 0", substring = true)
        harness.close()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theWifiFieldsAndTheButtonHaveAGapAndDoNotOverlap() = runComposeUiTest {
        val scope = TestScope()
        val harness = Harness(scope)
        scope.testScheduler.advanceUntilIdle()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) {
                    val state by harness.presenter.uiState.collectAsState()
                    AddDeviceSheet(state, harness.presenter, onDismiss = {})
                }
            }
        }
        val host = onNodeWithTag("wifi-host", useUnmergedTree = true).performScrollTo().getUnclippedBoundsInRoot()
        val port = onNodeWithTag("wifi-port", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val button = onNodeWithTag("wifi-use", useUnmergedTree = true).performScrollTo().getUnclippedBoundsInRoot()
        // The host and the port share one row with a gap; the button starts at least 8 dp below the fields.
        assertTrue(port.left - host.right >= 8.dp, "gap between the host and the port: ${port.left - host.right}")
        assertTrue(button.top - maxOf(host.bottom, port.bottom) >= 8.dp, "gap above the button: ${button.top - port.bottom}")
        harness.close()
    }

    @Test
    fun theAppSettingsHideALinkType() = runTest {
        val harness = Harness(this, AppSettings(hiddenLinkTypes = setOf(TransportKind.BluetoothSpp, TransportKind.Tcp)))
        runCurrent()
        assertEquals(listOf(TransportKind.UsbSerial), harness.state.sections.map { it.kind })
        assertEquals(false, harness.state.wifiShown)
        harness.close()
    }

    @Test
    fun theDemoShowsOnlyInADebugBuild() = runTest {
        val harness = Harness(this, debug = FakeDemoAdapter())
        runCurrent()
        assertEquals(listOf("Demo mill", "Demo lathe"), harness.state.demo?.options?.map { it.name })
        harness.presenter.selectLink(harness.state.demo!!.options.first())
        assertEquals(AddDeviceStep.Profile, harness.state.step)
        assertEquals("Demo mill", harness.state.name)
        harness.close()
    }

    @Test
    fun theSheetAsksForTheMissingPermissions() = runTest {
        val harness = Harness(this)
        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.PermissionMissing))
        runCurrent()
        assertEquals(PairedListState.PermissionMissing, harness.state.sections[0].paired)
        harness.presenter.grantBluetooth()
        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        runCurrent()
        assertEquals(PairedListState.RadioOff, harness.state.sections[0].paired)
        harness.presenter.enableBluetooth()
        harness.presenter.selectLink(harness.state.sections[1].options.single())
        assertEquals(
            listOf(
                ConnectionRequest.RequestPermissions(setOf(PermissionKind.BluetoothConnect)),
                ConnectionRequest.EnableBluetooth,
                ConnectionRequest.RequestUsbPermission(AdapterAddress.UsbSerial(0x0403, 0x6001)),
            ),
            harness.requests.sent,
        )
        harness.close()
    }

    @Test
    fun theBluetoothSectionHasTheStateOfThePairedList() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertEquals(PairedListState.Paired, harness.state.sections[0].paired)
        assertEquals(listOf("HC-05"), harness.state.sections[0].options.map { it.name })
        assertNull(harness.state.sections[1].paired)

        // Android gives an empty list when the radio is off. This is not "no adapter is paired".
        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        harness.bluetooth.list.value = emptyList()
        runCurrent()
        assertEquals(PairedListState.RadioOff, harness.state.sections[0].paired)
        assertTrue(harness.state.sections[0].options.isEmpty())

        // Android gives an empty list without the permission. This is not "no adapter is paired".
        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.PermissionMissing))
        runCurrent()
        assertEquals(PairedListState.PermissionMissing, harness.state.sections[0].paired)
        assertTrue(harness.state.sections[0].options.isEmpty())

        // The radio is on and the app has the permission: the empty list is really empty.
        harness.capabilities.state.value = CapabilityState()
        runCurrent()
        assertEquals(PairedListState.NoPaired, harness.state.sections[0].paired)

        harness.bluetooth.list.value = listOf(DiscoveredAdapter(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05"))
        runCurrent()
        assertEquals(PairedListState.Paired, harness.state.sections[0].paired)
        harness.close()
    }

    @Test
    fun aRadioThatIsOffHidesThePairedAdaptersThatTheListStillHas() = runTest {
        val harness = Harness(this)
        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        runCurrent()
        assertEquals(PairedListState.RadioOff, harness.state.sections[0].paired)
        assertTrue(harness.state.sections[0].options.isEmpty())
        harness.close()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSheetShowsOnlyTheNoticeWhenBluetoothIsOffOrNotPermitted() = runComposeUiTest {
        val scope = TestScope()
        val harness = Harness(scope)
        harness.bluetooth.list.value = emptyList()
        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.RadioOff))
        scope.testScheduler.advanceUntilIdle()
        setContent {
            AppTheme(systemInDarkTheme = false) {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp)) {
                    val state by harness.presenter.uiState.collectAsState()
                    AddDeviceSheet(state, harness.presenter, onDismiss = {})
                }
            }
        }
        onNodeWithText("Bluetooth is off.").assertExists()
        onNodeWithText("Turn on Bluetooth").assertExists()
        onNodeWithText("No adapter is paired", substring = true).assertDoesNotExist()
        onNodeWithTag(ADD_DEVICE_PAIR_NEW_TAG).assertDoesNotExist()

        harness.capabilities.state.value = CapabilityState(bluetooth = LinkCapability.Unavailable(LinkFailure.PermissionMissing))
        scope.testScheduler.advanceUntilIdle()
        waitForIdle()
        onNodeWithText("Bluetooth is off.").assertDoesNotExist()
        onNodeWithText("Grant permission").assertExists()
        onNodeWithText("No adapter is paired", substring = true).assertDoesNotExist()
        onNodeWithTag(ADD_DEVICE_PAIR_NEW_TAG).assertDoesNotExist()

        harness.capabilities.state.value = CapabilityState()
        scope.testScheduler.advanceUntilIdle()
        waitForIdle()
        onNodeWithText("Grant permission").assertDoesNotExist()
        onNodeWithText("No adapter is paired", substring = true).assertExists()
        onNodeWithTag(ADD_DEVICE_PAIR_NEW_TAG).assertExists()
        harness.close()
    }

    @Test
    fun theWifiLinkChecksTheHostAndThePort() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertEquals("8080", harness.state.wifiPort)
        harness.presenter.useWifi()
        assertEquals("Type the host name or the IP address.", harness.state.wifiHostError?.english())
        harness.presenter.setWifiHost("192.168.1.300")
        harness.presenter.setWifiPort("70000")
        harness.presenter.useWifi()
        assertEquals("This is not a valid host name, IPv4 address, or IPv6 address.", harness.state.wifiHostError?.english())
        assertEquals("The port must be a number from 1 to 65535.", harness.state.wifiPortError?.english())
        harness.presenter.setWifiHost("dro.local")
        harness.presenter.setWifiPort("8080")
        harness.presenter.useWifi()
        assertEquals(AddDeviceStep.Profile, harness.state.step)
        assertEquals(AdapterAddress.Tcp("dro.local", 8080), harness.state.selected?.address)
        harness.close()
    }

    @Test
    fun aNewMachineIsStoredRememberedAndConnected() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.presenter.setWifiHost("10.0.0.5")
        harness.presenter.useWifi()
        harness.presenter.setName("Bridgeport")
        harness.presenter.updateSetup { setup -> setup.copy(metric = true, quill = SetupInput(true, 'w')) }
        harness.presenter.connect()
        runCurrent()
        val result = assertNotNull(harness.state.result)
        val profile = harness.store.machines.loadProfile(result.profileId)!!
        assertEquals("Bridgeport", profile.name)
        assertEquals(AdapterAddress.Tcp("10.0.0.5", 8080), profile.adapter)
        assertTrue(profile.metricDefault)
        assertEquals('w', profile.z.secondary?.channel)
        val adapter = harness.store.adapters.adapterProfiles().first().single()
        assertEquals("10.0.0.5:8080", adapter.alias)
        assertEquals(AdapterAddress.Tcp("10.0.0.5", 8080), adapter.lastLink)
        assertEquals(adapter.id, profile.adapterProfileId)
        assertEquals(listOf(result.profileId), harness.store.tabs.state.value.remembered.map { it.profileId })
        assertEquals(false, harness.store.tabs.state.value.remembered.single().paused)
        assertEquals(listOf("connect ${result.profileId}"), harness.connections.calls)
        // The notification permission and the battery request have one flow in the activity.
        // The sheet asks only for the local network at the first TCP connect.
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))), harness.requests.sent)
        assertTrue(harness.settingsStore.settings.value.localNetworkPermissionAsked)
        harness.close()
    }

    @Test
    fun theLocalNetworkRequestGoesOutOneTimeAlsoAfterARestart() = runTest {
        val first = Harness(this)
        runCurrent()
        first.presenter.setWifiHost("10.0.0.5")
        first.presenter.useWifi()
        first.presenter.setName("Mill")
        first.presenter.connect()
        runCurrent()
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))), first.requests.sent)
        first.settingsStore.awaitWrites()
        advanceUntilIdle()
        // A restart: a new store reads the same file.
        val restarted = AppSettingsStore(first.store.storeIO, SuperDroJson, first.store.dispatcher, first.store.wallClock)
        val second = Harness(this, sharedSettings = restarted)
        runCurrent()
        second.presenter.setWifiHost("10.0.0.6")
        second.presenter.useWifi()
        second.presenter.setName("Lathe")
        second.presenter.connect()
        runCurrent()
        assertNotNull(second.state.result)
        assertEquals(emptyList(), second.requests.sent, "A restart does not ask again")
        first.close()
        second.close()
    }

    @Test
    fun aBluetoothConnectSendsNoNotificationAndNoBatteryRequest() = runTest {
        val harness = Harness(this)
        runCurrent()
        harness.presenter.selectLink(harness.state.sections[0].options.single())
        harness.presenter.setName("Mill")
        harness.presenter.connect()
        runCurrent()
        assertNotNull(harness.state.result)
        assertEquals(emptyList(), harness.requests.sent, "The activity asks at the first connect, one time")
        harness.close()
    }

    @Test
    fun aNameThatExistsIsRefused() = runTest {
        val harness = Harness(this)
        harness.store.machines.createProfile(MachineProfile.create(name = "Mill"))
        runCurrent()
        harness.presenter.selectLink(harness.state.sections[0].options.single())
        harness.presenter.setName("Mill")
        harness.presenter.connect()
        runCurrent()
        assertEquals("A profile with this name already exists.", harness.state.nameError?.english())
        assertNull(harness.state.result)
        assertEquals(emptyList(), harness.connections.calls)
        harness.presenter.setName("")
        harness.presenter.connect()
        runCurrent()
        assertEquals("Profile name can't be blank.", harness.state.nameError?.english())
        harness.close()
    }

    @Test
    fun aCopyOfAStoredProfileGetsTheLinkAndANewAdapterProfile() = runTest {
        val harness = Harness(this)
        val lathe = harness.store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Lathe")).valueOrNull()!!
        runCurrent()
        harness.presenter.selectLink(harness.state.sections[0].options.single())
        harness.presenter.setSource(ProfileSource.Copy)
        harness.presenter.connect()
        runCurrent()
        assertEquals("Select a profile.", harness.state.nameError?.english())
        harness.presenter.selectProfile(lathe.id)
        harness.presenter.setName("Lathe 2")
        harness.presenter.connect()
        runCurrent()
        val result = assertNotNull(harness.state.result)
        val copy = harness.store.machines.loadProfile(result.profileId)!!
        assertEquals("Lathe 2", copy.name)
        assertEquals(MachineType.Lathe, copy.machineType)
        assertEquals(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), copy.adapter)
        assertEquals("HC-05", harness.store.adapters.adapterProfiles().first().single().alias)
        harness.close()
    }

    @Test
    fun aStoredProfileGetsTheNewLinkAndKeepsItsAdapterProfile() = runTest {
        val harness = Harness(this)
        val adapter = harness.store.adapters.saveAdapterProfile(org.superdro.core.model.AdapterProfile(alias = "Old adapter")).valueOrNull()!!
        val mill = harness.store.machines.createProfile(MachineProfile.create(name = "Mill").copy(adapterProfileId = adapter.id)).valueOrNull()!!
        runCurrent()
        harness.presenter.selectLink(harness.state.sections[0].options.single())
        harness.presenter.setSource(ProfileSource.Stored)
        harness.presenter.selectProfile(mill.id)
        assertEquals("Mill", harness.state.name)
        harness.presenter.connect()
        runCurrent()
        val stored = harness.store.machines.loadProfile(mill.id)!!
        assertEquals(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), stored.adapter)
        assertEquals(adapter.id, stored.adapterProfileId)
        assertEquals(1, harness.store.adapters.adapterProfiles().first().size)
        assertEquals(listOf("connect ${mill.id}"), harness.connections.calls)
        harness.close()
    }

    @Test
    fun theConnectUsesTheStoredLinkWithTheBaudRateOfTheSetting() = runTest {
        val harness = Harness(this)
        val mill = harness.store.machines.createProfile(MachineProfile.create(name = "Mill").copy(usbBaudRate = 9600)).valueOrNull()!!
        runCurrent()
        // The attached USB adapter has the default rate.
        val usb = harness.state.sections[1].options.single()
        assertEquals(AdapterAddress.UsbSerial.DEFAULT_BAUD_RATE, (usb.address as AdapterAddress.UsbSerial).baudRate)
        harness.presenter.selectLink(usb)
        harness.presenter.setSource(ProfileSource.Stored)
        harness.presenter.selectProfile(mill.id)
        harness.presenter.connect()
        runCurrent()
        val address = harness.connections.session(org.superdro.core.connection.DeviceId(mill.id)).device.value.address
        assertEquals(9600, (address as AdapterAddress.UsbSerial).baudRate)
        assertEquals(address, harness.store.machines.loadProfile(mill.id)!!.adapter)
        harness.close()
    }
}
