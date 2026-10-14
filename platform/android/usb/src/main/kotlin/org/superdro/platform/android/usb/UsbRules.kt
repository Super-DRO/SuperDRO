package org.superdro.platform.android.usb

import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.model.AdapterAddress

/**
 * The line settings of a USB serial link: the baud rate of the profile, 8 data bits, no parity, 1
 * stop bit. DTR and RTS stay false: an ESP32 board with an auto-reset circuit can reset when these
 * lines change.
 */
data class UsbLineSettings(
    val baudRate: Int,
    val dataBits: Int = UsbSerialDefaults.DATA_BITS,
    val stopBits: Int = UsbSerialDefaults.STOP_BITS,
    val parity: Int = UsbSerialDefaults.PARITY,
    val dtr: Boolean = false,
    val rts: Boolean = false,
) {
    companion object {
        /**
         * Gives the settings of [address]. The baud rate is the stored value with no change, also
         * 9600. A new profile has 115200.
         */
        fun of(address: AdapterAddress.UsbSerial): UsbLineSettings = UsbLineSettings(baudRate = address.baudRate)
    }
}

/**
 * The identity of one USB serial port: the vendor ID, the product ID,
 * the port index, and the serial number. The app can read the serial number only after the USB
 * permission. Without a serial number, the product name identifies the port.
 */
data class UsbIdentity(
    val vendorId: Int,
    val productId: Int,
    val portIndex: Int,
    val serial: String?,
    val productName: String?,
) {
    /** The serial number, else the product name. */
    val name: String? get() = serial?.takeIf { text -> text.isNotBlank() } ?: productName?.takeIf { text -> text.isNotBlank() }

    /** A text for the logs and the tests: `"10C4:EA60 #0 0001"`. */
    val key: String get() = "%04X:%04X #%d %s".format(vendorId, productId, portIndex, name ?: "-")

    /** True when this port is the port of [address]. A serial number counts only when both have one. */
    fun matches(address: AdapterAddress.UsbSerial): Boolean =
        address.vendorId == vendorId &&
            address.productId == productId &&
            address.portIndex == portIndex &&
            (address.serial == null || serial == null || address.serial == serial)

    companion object {
        /** Makes the identity. [readSerial] runs only when [permitted] is true. */
        fun of(
            vendorId: Int,
            productId: Int,
            portIndex: Int,
            permitted: Boolean,
            productName: String?,
            readSerial: () -> String?,
        ): UsbIdentity = UsbIdentity(
            vendorId = vendorId,
            productId = productId,
            portIndex = portIndex,
            serial = if (permitted) readSerial()?.takeIf { text -> text.isNotBlank() } else null,
            productName = productName,
        )
    }
}

/**
 * One attached USB serial device, with no Android type (see `UsbSerialDiscovery`).
 *
 * @property serial The serial number. Null without the USB permission.
 * @property portCount The number of serial ports of the driver. CP210x and FTDI have one port for
 *   each interface.
 * @property deviceName The device path of the system (`UsbDevice.getDeviceName()`), for example
 *   `/dev/bus/usb/001/004`. It identifies one attached device, also without the permission.
 */
data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val permitted: Boolean,
    val portCount: Int = 1,
    val deviceName: String? = null,
)

/** The rules of the USB link with no Android type. */
object UsbRules {

    /** The value of `PendingIntent.FLAG_MUTABLE`. */
    const val FLAG_MUTABLE: Int = 0x02000000

    /** API 31: the first API level that has `PendingIntent.FLAG_MUTABLE`. Before it, a `PendingIntent` is mutable. */
    const val MUTABLE_FLAG_API: Int = 31

    /**
     * Gives the device name: `"<manufacturer> <product>"`, `"<manufacturer> USB UART Device"`,
     * `"Generic <product>"`, or `"Generic USB UART Device"`. A blank value counts as no value.
     */
    fun deviceName(manufacturer: String?, product: String?): String {
        val maker = manufacturer?.trim()?.takeIf { text -> text.isNotEmpty() }
        val model = product?.trim()?.takeIf { text -> text.isNotEmpty() }
        return when {
            maker != null && model != null -> "$maker $model"
            maker != null -> "$maker USB UART Device"
            model != null -> "Generic $model"
            else -> "Generic USB UART Device"
        }
    }

    /**
     * The action of the USB permission broadcast of the app: `"<package>.USB_PERMISSION"`. The
     * package in the action keeps the results of SuperDRO and of the adapter probe app apart.
     */
    fun permissionAction(packageName: String): String = "$packageName.USB_PERMISSION"

    /**
     * Gives the flags of the `PendingIntent` of the USB permission request: `FLAG_MUTABLE` on API
     * 31 and higher, so that the system can add `EXTRA_DEVICE` and `EXTRA_PERMISSION_GRANTED`.
     * Before API 31 each `PendingIntent` is mutable.
     */
    fun permissionPendingIntentFlags(sdkInt: Int): Int = if (sdkInt >= MUTABLE_FLAG_API) FLAG_MUTABLE else 0

    /** The permission is granted when the broadcast says so, or when `UsbManager.hasPermission` says so. */
    fun isGranted(extraGranted: Boolean, hasPermission: Boolean): Boolean = extraGranted || hasPermission

    /**
     * Gives the rows of the add-device list: one row for each port of
     * each attached device that a prober accepts. The name is [deviceName]. The detail is the
     * vendor ID and the product ID in hex, and `#<n>` for the port n of a device with more than one
     * port. A device with no permission gives an address with no serial number and
     * `needsPermission = true`. A new address has the default baud rate 115200. The
     * `deviceKey` of each row is the [UsbDeviceInfo.deviceName], so that the permission request
     * goes to the device of the row.
     */
    fun discoveredAdapters(devices: Collection<UsbDeviceInfo>): List<DiscoveredAdapter> =
        devices.flatMap { device ->
            (0 until device.portCount.coerceAtLeast(1)).map { portIndex ->
                val ids = "%04X:%04X".format(device.vendorId, device.productId)
                DiscoveredAdapter(
                    address = AdapterAddress.UsbSerial(
                        vendorId = device.vendorId,
                        productId = device.productId,
                        serial = device.serial.takeIf { device.permitted },
                        portIndex = portIndex,
                    ),
                    displayName = deviceName(device.manufacturer, device.product),
                    detail = if (device.portCount > 1) "$ids #${portIndex + 1}" else ids,
                    needsPermission = !device.permitted,
                    deviceKey = device.deviceName,
                )
            }
        }

    /**
     * Gives the order of the attached devices: the vendor ID, the product ID, and the device path.
     * The permission and the serial number do not change the order.
     */
    val deviceOrder: Comparator<UsbDeviceInfo> =
        compareBy({ info -> info.vendorId }, { info -> info.productId }, { info -> info.deviceName.orEmpty() })

    /** Gives the capability part (`CapabilityState.attachedUsb`) of the attached devices. */
    fun attachedDevices(devices: Collection<UsbDeviceInfo>): List<AttachedUsbDevice> =
        devices.map { device ->
            AttachedUsbDevice(
                vendorId = device.vendorId,
                productId = device.productId,
                serial = device.serial.takeIf { device.permitted },
                permitted = device.permitted,
            )
        }
}
