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
) {
    /**
     * True when this device can be the adapter of [address]. The vendor ID and the product ID
     * must be the same. When [address] has a serial number, the device must have the same serial
     * number. A device with no permission has no serial number that the app can read, so it can
     * be the adapter. A permitted device with a different serial number, or with no serial
     * number, is a different adapter.
     */
    fun canBe(address: AdapterAddress.UsbSerial): Boolean =
        vendorId == address.vendorId &&
            productId == address.productId &&
            (address.serial == null || serial == address.serial || (serial == null && !permitted))
}

/**
 * Selects the attached device of [address] in [devices]. [info] gives the USB data of one item.
 * Only a device that [AttachedUsbDevice.canBe] the adapter counts. A permitted device comes before
 * a device with no permission, so that one more device with the same vendor ID and product ID
 * does not hide the permitted adapter. The order of [devices] decides between equal devices.
 * Gives null when no device can be the adapter.
 */
fun <T> selectUsbDevice(address: AdapterAddress.UsbSerial, devices: Iterable<T>, info: (T) -> AttachedUsbDevice): T? {
    val candidates = devices.filter { device -> info(device).canBe(address) }
    return candidates.firstOrNull { device -> info(device).permitted } ?: candidates.firstOrNull()
}

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
     * Gives the capability of the link to [address]. A USB adapter is available when one attached
     * device can be the adapter ([AttachedUsbDevice.canBe]) and the app has its permission. It is
     * `PermissionMissing` when only devices with no permission can be the adapter, and
     * `DeviceGone` when no device can be the adapter.
     */
    fun capabilityOf(address: AdapterAddress): LinkCapability = when (address) {
        is AdapterAddress.BluetoothSpp, is AdapterAddress.Ble -> bluetooth
        is AdapterAddress.Tcp -> network
        is AdapterAddress.Fake -> LinkCapability.Available
        is AdapterAddress.UsbSerial -> {
            val device = selectUsbDevice(address, attachedUsb) { device -> device }
            when {
                device == null -> LinkCapability.Unavailable(LinkFailure.DeviceGone)
                !device.permitted -> LinkCapability.Unavailable(LinkFailure.PermissionMissing)
                else -> LinkCapability.Available
            }
        }
    }
}
