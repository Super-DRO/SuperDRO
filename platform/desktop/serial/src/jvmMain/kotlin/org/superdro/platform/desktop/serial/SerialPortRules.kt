package org.superdro.platform.desktop.serial

import java.io.File
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkFailure
import org.superdro.core.model.AdapterAddress

/** The kind of a serial port of the operating system. */
enum class SerialPortKind {
    /** A USB serial adapter: the port has a USB vendor ID and a USB product ID. */
    Usb,

    /** The RFCOMM port of a paired Bluetooth adapter (SPP). */
    Bluetooth,

    /** Each other port: a physical RS-232 port, a pseudo-terminal, or a port of the system. The add-device sheet does not show it. */
    Other,
}

/**
 * The rules of the desktop serial links with no jSerialComm type, so that a unit test can examine
 * them.
 *
 * The operating system maps a paired SPP adapter to a serial port:
 * `/dev/rfcommN` on Linux (after `rfcomm bind`), `COMn` on Windows, and `/dev/cu.<device name>` on
 * macOS. A USB serial adapter is a port with a USB vendor ID and a USB product ID.
 */
object SerialPortRules {

    /** The macOS ports with no USB IDs that are not a paired adapter. */
    private val MAC_SYSTEM_PORTS: Set<String> = setOf("cu.Bluetooth-Incoming-Port", "cu.debug-console", "cu.wlan-debug")

    /** The callout ports of macOS. A paired SPP device gets one. */
    private const val MAC_CALLOUT_PREFIX = "/dev/cu."

    /** The RFCOMM ports of Linux. */
    private const val RFCOMM_PREFIX = "/dev/rfcomm"

    /** Gives the kind of [port]. */
    fun kindOf(port: SerialPortInfo): SerialPortKind = when {
        port.vendorId != null && port.productId != null -> SerialPortKind.Usb
        isBluetooth(port) -> SerialPortKind.Bluetooth
        else -> SerialPortKind.Other
    }

    private fun isBluetooth(port: SerialPortInfo): Boolean {
        if (port.name in MAC_SYSTEM_PORTS) return false
        if (port.bluetoothMac != null || port.id.startsWith(RFCOMM_PREFIX)) return true
        // Windows: "Standard Serial over Bluetooth link (COM5)".
        if ("bluetooth" in "${port.description} ${port.friendlyName}".lowercase()) return true
        return port.id.startsWith(MAC_CALLOUT_PREFIX)
    }

    /**
     * Gives the USB device name: `"<manufacturer> <product>"`, `"<manufacturer> USB UART Device"`,
     * `"Generic <product>"`, or `"Generic USB UART Device"`. A blank value counts as no value. The
     * Android USB discovery uses the same rule.
     */
    fun usbDeviceName(manufacturer: String?, product: String?): String {
        val maker = manufacturer?.trim()?.takeIf { text -> text.isNotEmpty() }
        val model = product?.trim()?.takeIf { text -> text.isNotEmpty() }
        return when {
            maker != null && model != null -> "$maker $model"
            maker != null -> "$maker USB UART Device"
            model != null -> "Generic $model"
            else -> "Generic USB UART Device"
        }
    }

    /** The product text of a USB port: the description of the bus, else the name that the system gives. */
    private fun productOf(port: SerialPortInfo): String? =
        port.description.takeIf { text -> text.isNotBlank() } ?: port.friendlyName.takeIf { text -> text.isNotBlank() }

    /** The order of the ports of one USB device or of identical devices: the location, then the ID. */
    private fun usbOrder(ports: List<SerialPortInfo>): List<SerialPortInfo> =
        ports.sortedWith(compareBy({ port -> port.location.orEmpty() }, { port -> port.id }))

    private fun usbPorts(ports: List<SerialPortInfo>): List<SerialPortInfo> =
        ports.filter { port -> kindOf(port) == SerialPortKind.Usb }

    /**
     * Gives the USB rows of the add-device list: one row for each USB serial port. The
     * ports with the same vendor ID, product ID, and serial number are one group. The port index of
     * the address is the position in the group (the location, then the ID). The detail is the vendor
     * ID and the product ID in hex, `#<n>` for a group with more than one port, and the system name
     * of the port. A new address has the default baud rate 115200. A port that the user
     * cannot open gives `needsPermission = true`.
     */
    fun usbAdapters(ports: List<SerialPortInfo>): List<DiscoveredAdapter> =
        usbPorts(ports)
            .groupBy { port -> Triple(port.vendorId, port.productId, port.serialNumber) }
            .values
            .flatMap { group ->
                val ordered = usbOrder(group)
                ordered.mapIndexed { index, port ->
                    val vendorId = requireNotNull(port.vendorId)
                    val productId = requireNotNull(port.productId)
                    val ids = "%04X:%04X".format(vendorId, productId)
                    DiscoveredAdapter(
                        address = AdapterAddress.UsbSerial(
                            vendorId = vendorId,
                            productId = productId,
                            serial = port.serialNumber,
                            portIndex = index,
                        ),
                        displayName = usbDeviceName(port.manufacturer, productOf(port)),
                        detail = if (ordered.size > 1) "$ids #${index + 1} (${port.name})" else "$ids (${port.name})",
                        needsPermission = !port.accessible,
                    )
                }
            }
            .sortedWith(compareBy({ adapter -> adapter.displayName.lowercase() }, { adapter -> adapter.detail }))

    /**
     * Gives the SPP rows of the add-device list: one row for each RFCOMM port. The
     * address has the Bluetooth address when the system gives it, and always the port. The name is
     * the device name of a macOS port, else the system name of the port. The detail is the
     * Bluetooth address, else the name that the system gives, else the port.
     */
    fun sppAdapters(ports: List<SerialPortInfo>): List<DiscoveredAdapter> =
        ports.filter { port -> kindOf(port) == SerialPortKind.Bluetooth }
            .map { port ->
                DiscoveredAdapter(
                    address = AdapterAddress.BluetoothSpp(mac = port.bluetoothMac.orEmpty(), serialPort = port.id),
                    displayName = port.name.removePrefix("cu."),
                    detail = port.bluetoothMac
                        ?: port.friendlyName.takeIf { text -> text.isNotBlank() && text != port.name }
                        ?: port.id,
                    needsPermission = !port.accessible,
                )
            }
            .sortedWith(compareBy({ adapter -> adapter.displayName.lowercase() }, { adapter -> adapter.detail }))

    /**
     * Gives the capability part `CapabilityState.attachedUsb`: one device for each USB port.
     * The device is permitted when the user can open its port.
     */
    fun attachedUsb(ports: List<SerialPortInfo>): List<AttachedUsbDevice> =
        usbPorts(ports).map { port ->
            AttachedUsbDevice(
                vendorId = requireNotNull(port.vendorId),
                productId = requireNotNull(port.productId),
                serial = port.serialNumber,
                permitted = port.accessible,
            )
        }

    /**
     * Gives the port of a USB address: the ports with the vendor ID and the product ID of
     * [address] and a serial number that agrees (no serial number on one side agrees), then the
     * group with the same serial number when it has ports, then the port at the port index.
     */
    fun usbPortFor(address: AdapterAddress.UsbSerial, ports: List<SerialPortInfo>): SerialPortInfo? {
        val matching = usbPorts(ports).filter { port ->
            port.vendorId == address.vendorId &&
                port.productId == address.productId &&
                (address.serial == null || port.serialNumber == null || port.serialNumber == address.serial)
        }
        val group = matching.filter { port -> port.serialNumber == address.serial }.ifEmpty { matching }
        return usbOrder(group).getOrNull(address.portIndex)
    }

    /**
     * Gives the port of an SPP address: first the port with the Bluetooth address of [address],
     * then the listed port [AdapterAddress.BluetoothSpp.serialPort], then that port also when the
     * list does not have it (the open then tells if it is there). A listed port with a different
     * known Bluetooth address is not the adapter: the result is then null.
     */
    fun sppPortFor(address: AdapterAddress.BluetoothSpp, ports: List<SerialPortInfo>): String? {
        val mac = address.mac.trim()
        if (mac.isNotEmpty()) {
            ports.firstOrNull { port -> port.bluetoothMac.equals(mac, ignoreCase = true) }?.let { port -> return port.id }
        }
        val wanted = address.serialPort?.takeIf { text -> text.isNotBlank() } ?: return null
        val listed = ports.firstOrNull { port -> port.id == wanted }
        if (listed?.bluetoothMac != null && mac.isNotEmpty()) return null
        return wanted
    }

    /** Gives the port to open for [address], or null when the adapter has no port now (`DeviceGone`). */
    fun portIdFor(address: AdapterAddress, ports: List<SerialPortInfo>): String? = when (address) {
        is AdapterAddress.UsbSerial -> usbPortFor(address, ports)?.id
        is AdapterAddress.BluetoothSpp -> sppPortFor(address, ports)
        else -> null
    }

    /** True while the port of [address] is in [ports]. */
    fun isPresent(address: AdapterAddress, portId: String?, ports: List<SerialPortInfo>): Boolean = when (address) {
        is AdapterAddress.UsbSerial -> usbPortFor(address, ports) != null
        else -> ports.any { port -> port.id == portId }
    }

    /**
     * Gives the link failure of an open that failed with the system error [errorCode] (the
     * `errno` value on Linux and macOS, the Windows error code on Windows):
     * - Linux and macOS: `EPERM` (1) and `EACCES` (13) are [LinkFailure.PermissionMissing];
     *   `ENOENT` (2), `ENXIO` (6), and `ENODEV` (19) are [LinkFailure.DeviceGone].
     * - Windows: `ERROR_FILE_NOT_FOUND` (2) and `ERROR_PATH_NOT_FOUND` (3) are
     *   [LinkFailure.DeviceGone]. `ERROR_ACCESS_DENIED` (5) tells that a different program has the
     *   port open: [LinkFailure.Refused].
     * - Each other code is [LinkFailure.Refused]: the session tries again with the reconnect policy.
     */
    fun openFailure(errorCode: Int, windows: Boolean): LinkFailure = if (windows) {
        when (errorCode) {
            2, 3 -> LinkFailure.DeviceGone
            else -> LinkFailure.Refused
        }
    } else {
        when (errorCode) {
            1, 13 -> LinkFailure.PermissionMissing
            2, 6, 19 -> LinkFailure.DeviceGone
            else -> LinkFailure.Refused
        }
    }

    /** True for the `os.name` of Windows. */
    fun isWindows(osName: String): Boolean = osName.trim().lowercase().startsWith("windows")

    /** True when the user can read and write the device file [id]. A Windows port name has no file: true. */
    fun isAccessible(id: String): Boolean {
        if (!id.startsWith("/")) return true
        val file = File(id)
        return file.canRead() && file.canWrite()
    }
}

/**
 * The Bluetooth address of a Linux RFCOMM port. The kernel gives it in
 * `/sys/class/tty/rfcomm<N>/address` (the attribute `address` of the RFCOMM TTY driver).
 */
object LinuxRfcomm {

    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    private const val NO_ADDRESS = "00:00:00:00:00:00"

    /** Gives the Bluetooth address of the port [portName] (for example `rfcomm0`) in upper case, or null. */
    fun macOf(portName: String, sysClassTty: File = File("/sys/class/tty")): String? {
        if (!portName.startsWith("rfcomm")) return null
        val text = runCatching { File(File(sysClassTty, portName), "address").readText() }.getOrNull() ?: return null
        return parseMac(text)
    }

    /** Gives the address in [text] in upper case, or null for a text that is not an address or for the address 0. */
    fun parseMac(text: String): String? =
        text.trim().takeIf { value -> MAC.matches(value) }?.uppercase()?.takeUnless { value -> value == NO_ADDRESS }
}
