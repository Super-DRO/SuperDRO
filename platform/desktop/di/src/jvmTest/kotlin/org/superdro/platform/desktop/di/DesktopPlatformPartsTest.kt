package org.superdro.platform.desktop.di

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraException
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.StreamEvent
import org.superdro.core.camera.UvcCodec
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.VideoTarget
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceLine
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.KeepAliveSummary
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.PermissionKind
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/** The desktop parts of the platform contracts and the recorded streams. */
class DesktopPlatformPartsTest {

    @Test
    fun theCapabilitySourceFollowsTheNetworkAndTheUsbPorts() = runTest(UnconfinedTestDispatcher()) {
        val network = MutableStateFlow<LinkCapability>(LinkCapability.Unavailable(LinkFailure.NetworkOff))
        val usb = MutableStateFlow(emptyList<AttachedUsbDevice>())
        val source = DesktopCapabilitySource(network, usb, backgroundScope)
        val usbAddress = AdapterAddress.UsbSerial(0x10C4, 0xEA60)
        val tcp = AdapterAddress.Tcp("192.168.1.20")
        val spp = AdapterAddress.BluetoothSpp(serialPort = "/dev/rfcomm0")

        assertEquals(LinkCapability.Unavailable(LinkFailure.NetworkOff), source.state.value.capabilityOf(tcp))
        assertEquals(LinkCapability.Unavailable(LinkFailure.DeviceGone), source.state.value.capabilityOf(usbAddress))
        // The desktop does not read the radio: SPP is always available.
        assertEquals(LinkCapability.Available, source.state.value.capabilityOf(spp))

        network.value = LinkCapability.Available
        usb.value = listOf(AttachedUsbDevice(0x10C4, 0xEA60, permitted = true))
        assertEquals(LinkCapability.Available, source.state.value.capabilityOf(tcp))
        assertEquals(LinkCapability.Available, source.state.value.capabilityOf(usbAddress))
    }

    @Test
    fun theKeepAliveIsANoOpThatReportsTheLinks() {
        val keepAlive = DesktopConnectionKeepAlive()
        assertEquals(KeepAliveStatus.Idle, keepAlive.status.value)
        val line = DeviceLine(DeviceId("mill"), "Mill", TransportKind.UsbSerial, SessionState.Connected(sinceMs = 0L))
        keepAlive.update(KeepAliveSummary(listOf(line)))
        assertEquals(KeepAliveStatus.Running, keepAlive.status.value)
        assertEquals(false, keepAlive.status.value.linksCanStopInBackground)
        keepAlive.update(null)
        assertEquals(KeepAliveStatus.Idle, keepAlive.status.value)
    }

    @Test
    fun theLinkDispatcherMakesAHighPriorityDaemonThreadForEachBlockedTask() = runBlocking {
        val dispatcher = DesktopLinkIoDispatcher(keepAliveSeconds = 1L)
        val names = ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(1)
        val blocked = launch(dispatcher) {
            names += Thread.currentThread().name
            assertEquals(Thread.MAX_PRIORITY, Thread.currentThread().priority)
            assertTrue(Thread.currentThread().isDaemon)
            latch.await(5, TimeUnit.SECONDS)
        }
        // A second task runs while the first one blocks its thread.
        withContext(dispatcher) { names += Thread.currentThread().name }
        latch.countDown()
        blocked.join()
        assertEquals(2, names.size)
        assertTrue(names.all { name -> name.startsWith("superdro-link-") }, names.toString())
        dispatcher.close()
    }

    @Test
    fun theRequestsOfTheBusDoTheDesktopPart() {
        var refreshes = 0
        var opens = 0
        val handler = DesktopRequestHandler(refreshPorts = { refreshes += 1 }, openBluetoothSettings = { opens += 1; true })
        assertEquals(RequestAction.OpenedBluetoothSettings, handler.handle(ConnectionRequest.OpenBluetoothSettings))
        assertEquals(RequestAction.OpenedBluetoothSettings, handler.handle(ConnectionRequest.EnableBluetooth))
        assertEquals(2, opens)
        assertEquals(RequestAction.RefreshedPorts, handler.handle(ConnectionRequest.RequestUsbPermission(AdapterAddress.UsbSerial(0x10C4, 0xEA60))))
        assertEquals(RequestAction.RefreshedPorts, handler.handle(ConnectionRequest.RequestPermissions(setOf(PermissionKind.BluetoothConnect))))
        assertEquals(2, refreshes)
        assertEquals(RequestAction.NothingToDo, handler.handle(ConnectionRequest.RequestIgnoreBatteryOptimizations))
        assertEquals(RequestAction.NothingToDo, handler.handle(ConnectionRequest.OpenBatteryOptimizationSettings))
        assertEquals(RequestAction.NothingToDo, handler.handle(ConnectionRequest.OpenAppSettings))

        val noProgram = DesktopRequestHandler(refreshPorts = {}, openBluetoothSettings = { false })
        assertEquals(RequestAction.NothingToDo, noProgram.handle(ConnectionRequest.OpenBluetoothSettings))
    }

    @Test
    fun eachSystemHasItsBluetoothSettingsCommands() {
        assertEquals(listOf(listOf("explorer.exe", "ms-settings:bluetooth")), SystemSettings.bluetoothSettingsCommands("Windows 11"))
        assertEquals("open", SystemSettings.bluetoothSettingsCommands("Mac OS X").first().first())
        assertEquals(listOf("gnome-control-center", "bluetooth"), SystemSettings.bluetoothSettingsCommands("Linux").first())
        val tried = mutableListOf<String>()
        val started = SystemSettings.openBluetoothSettings("Linux") { command -> tried += command.first(); command.first() == "blueman-manager" }
        assertTrue(started)
        assertEquals(listOf("gnome-control-center", "systemsettings", "blueman-manager"), tried)
        assertEquals(false, SystemSettings.openBluetoothSettings("Linux") { false })
    }

    @Test
    fun theDesktopHasNoCameraAndEachRequestGivesTheReason() = runBlocking {
        val backend = DesktopNoCameraBackend
        assertEquals(emptyList(), backend.cameras.value)
        val camera = CameraIdentity(vendorId = 0x046D, productId = 0x0825)
        val access = backend.requestAccess(camera).exceptionOrNull() as CameraException
        assertEquals(CameraErrorKind.NativeUnavailable, access.error.kind)
        assertEquals(DesktopNoCameraBackend.REASON, access.error.detail)
        assertTrue(backend.listModes(camera).isFailure)
        val events = backend.start(camera, UvcMode(UvcCodec.Mjpeg, 640, 480, 333_333L), object : VideoTarget {}).toList()
        assertEquals(listOf<StreamEvent>(StreamEvent.Ended(access.error)), events)
        backend.stop()
        backend.close()
    }

    @Test
    fun theFirstValueOfTheCapabilityIsThereAtOnce() = runTest {
        val source = DesktopCapabilitySource(
            MutableStateFlow(LinkCapability.Available),
            MutableStateFlow(listOf(AttachedUsbDevice(1, 2, permitted = true))),
            backgroundScope,
        )
        assertEquals(listOf(AttachedUsbDevice(1, 2, permitted = true)), source.state.first().attachedUsb)
    }
}
