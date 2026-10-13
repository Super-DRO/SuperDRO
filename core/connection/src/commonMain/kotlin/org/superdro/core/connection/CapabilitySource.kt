package org.superdro.core.connection

import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.model.AdapterAddress

/**
 * Gives the state of the capabilities that the links need: the Bluetooth radio and permission, the
 * attached USB devices and their permission, and the network for the TCP links. The Android
 * implementation uses broadcast receivers and permission checks.
 *
 * A session in `WaitingForDevice` collects [state]. When [CapabilityState.capabilityOf] gives
 * [LinkCapability.Available] for its address, the session goes to `Connecting(1)` at once.
 */
interface CapabilitySource {
    val state: StateFlow<CapabilityState>
}

/** The capability of one link. */
sealed interface LinkCapability {

    /** The link can open now. */
    data object Available : LinkCapability

    /** The link cannot open. [cause] gives the reason. */
    data class Unavailable(val cause: LinkFailure) : LinkCapability
}

/**
 * One attached USB device.
 *
 * @property permitted True when the app has the USB permission for the device.
 */
data class AttachedUsbDevice(
    val vendorId: Int,
    val productId: Int,
    val serial: String? = null,
    val permitted: Boolean = false,
)

/**
 * The capabilities at one time.
 *
 * @property bluetooth The Bluetooth radio and the Bluetooth permission. It applies to SPP and BLE.
 * @property network The network for the TCP links, with the local network permission.
 * @property attachedUsb The USB devices that are attached now.
 */
data class CapabilityState(
    val bluetooth: LinkCapability = LinkCapability.Available,
    val network: LinkCapability = LinkCapability.Available,
    val attachedUsb: List<AttachedUsbDevice> = emptyList(),
) {
    /**
     * Gives the capability of the link to [address]. A USB adapter is available only when a
     * device with the same vendor ID and product ID is attached (and the same serial number,
     * when the address has one) and the app has its permission.
     */
    fun capabilityOf(address: AdapterAddress): LinkCapability = when (address) {
        is AdapterAddress.BluetoothSpp, is AdapterAddress.Ble -> bluetooth
        is AdapterAddress.Tcp -> network
        is AdapterAddress.Fake -> LinkCapability.Available
        is AdapterAddress.UsbSerial -> {
            val device = attachedUsb.firstOrNull { device ->
                device.vendorId == address.vendorId &&
                    device.productId == address.productId &&
                    (address.serial == null || device.serial == null || device.serial == address.serial)
            }
            when {
                device == null -> LinkCapability.Unavailable(LinkFailure.DeviceGone)
                !device.permitted -> LinkCapability.Unavailable(LinkFailure.PermissionMissing)
                else -> LinkCapability.Available
            }
        }
    }
}
