package org.superdro.core.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.model.AdapterAddress
import org.superdro.core.ui.tabs.FakeCapabilities
import org.superdro.core.ui.tabs.RecordingRequests

/** A user connect to a USB adapter with no USB permission asks for the permission. */
class ManagerConnectionsTest {

    private val usb = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "A1")
    private val notPermitted = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60)))

    @Test
    fun onlyAnAttachedUsbAdapterWithNoPermissionGivesARequest() {
        assertEquals(ConnectionRequest.RequestUsbPermission(usb), usbPermissionRequest(usb, notPermitted))
        assertNull(usbPermissionRequest(usb, CapabilityState()), "A detached adapter needs no dialog")
        val permitted = CapabilityState(attachedUsb = listOf(AttachedUsbDevice(0x10C4, 0xEA60, serial = "A1", permitted = true)))
        assertNull(usbPermissionRequest(usb, permitted))
        assertNull(usbPermissionRequest(AdapterAddress.Tcp("10.0.0.5"), notPermitted))
    }

    @Test
    fun aConnectOfAWaitingSessionAsksForTheUsbPermissionEachTime() = runTest {
        val capabilities = FakeCapabilities(notPermitted)
        val manager = ConnectionManager(
            factories = emptyMap(),
            capabilities = capabilities,
            wantedLinks = object : WantedLinkSource {
                override val wantedLinks = flowOf(emptyList<org.superdro.core.connection.WantedLink>())
            },
            clock = { testScheduler.currentTime },
            scope = backgroundScope,
            linkIoDispatcher = StandardTestDispatcher(testScheduler),
        )
        val requests = RecordingRequests()
        val connections = ManagerConnections(manager, capabilities, requests)
        val ref = DeviceRef(DeviceId("mill"), "Mill", usb, twoWayCommunication = false)
        connections.connect(ref)
        runCurrent()
        val session = manager.session(DeviceId("mill"))!!
        assertEquals(SessionState.WaitingForDevice(LinkFailure.PermissionMissing), session.state.value)
        connections.connect(ref)
        runCurrent()
        assertEquals(List<ConnectionRequest>(2) { ConnectionRequest.RequestUsbPermission(usb) }, requests.sent)
        manager.close()
    }
}
