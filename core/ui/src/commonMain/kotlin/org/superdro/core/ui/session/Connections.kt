package org.superdro.core.ui.session

import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AdapterAddress
import org.superdro.core.ui.tabs.RequestSender

/**
 * The functions of the connection manager that the presenters use. The UI does not select a session
 * in the manager: each tab reads the session of its own
 * device. The presenter tests give a fake.
 */
interface Connections {

    /** The sessions, in the order of their start. */
    val sessions: StateFlow<Map<DeviceId, DeviceSession>>

    /** Starts or resumes the session of [device] (a user connect). */
    fun connect(device: DeviceRef)

    /** The pause of one machine: the session goes to `Disconnected`. */
    fun disconnect(id: DeviceId)

    /** "Disconnect all". */
    fun disconnectAll()

    /** The user removes a remembered machine: the session stops and goes away. */
    fun remove(id: DeviceId)

    /**
     * The record switch of the logs for the device [id]. A running
     * session starts a new link with the new flag at once. The session does not go to
     * `Disconnected`, so the tab stays and the selected tab does not move.
     *
     * The default closes the link and opens it again with the flag, for an implementation that
     * has no own record switch. [ManagerConnections] uses the call of the manager.
     */
    fun setRecordStream(id: DeviceId, on: Boolean) {
        val device = sessions.value[id]?.device?.value ?: return
        disconnect(id)
        connect(device.copy(recordStream = on))
    }
}

/**
 * The [Connections] of the real [ConnectionManager].
 *
 * A user connect to a USB adapter with no USB permission also sends the permission request
 * through [requests] (see [usbPermissionRequest]). The session waits until the grant.
 */
class ManagerConnections(
    private val manager: ConnectionManager,
    private val capabilities: CapabilitySource? = null,
    private val requests: RequestSender? = null,
) : Connections {

    override val sessions: StateFlow<Map<DeviceId, DeviceSession>> get() = manager.sessions

    override fun connect(device: DeviceRef) {
        manager.connect(device)
        val state = capabilities?.state?.value ?: return
        usbPermissionRequest(device.address, state)?.let { request -> requests?.send(request) }
    }

    override fun disconnect(id: DeviceId) = manager.disconnect(id)

    override fun disconnectAll() = manager.disconnectAll()

    override fun remove(id: DeviceId) = manager.remove(id)

    override fun setRecordStream(id: DeviceId, on: Boolean) = manager.setRecordStream(id, on)
}

/**
 * Gives the USB permission request of a user connect to [address], or null. The request goes out
 * only for a USB adapter that is attached with no USB permission.
 */
fun usbPermissionRequest(address: AdapterAddress, state: CapabilityState): ConnectionRequest.RequestUsbPermission? =
    if (address is AdapterAddress.UsbSerial && state.capabilityOf(address) == LinkCapability.Unavailable(LinkFailure.PermissionMissing)) {
        ConnectionRequest.RequestUsbPermission(address)
    } else {
        null
    }
