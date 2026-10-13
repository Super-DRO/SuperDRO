package org.superdro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The link types of SuperDRO. Each link type has its own transport
 * module.
 */
@Serializable
enum class TransportKind {
    /** Bluetooth Classic SPP (`platform:android:bluetooth`). */
    BluetoothSpp,

    /** USB serial (`platform:android:usb`). */
    UsbSerial,

    /** Wi-Fi TCP (`core:connection-tcp`). */
    Tcp,

    /** BLE with BlueFalcon (`core:connection-ble`). */
    Ble,

    /** The fake adapter of the debug builds and the tests (`core:fake-adapter`). */
    Fake,
}

/**
 * The address of one adapter link. A machine profile keeps one
 * address. The JSON has the field `type` with the [SerialName] of the subclass.
 */
@Serializable
sealed interface AdapterAddress {

    /** The link type of this address. */
    val kind: TransportKind

    /**
     * A Bluetooth Classic SPP adapter. [mac] is the Bluetooth address, for example `24:6F:28:A1:B2:C3`.
     *
     * [serialPort] is the serial port of the operating system that the desktop app opens for the
     * paired adapter, for example `/dev/rfcomm0`, `COM5`, or `/dev/cu.ESP32-DRO`. A desktop
     * operating system maps a paired SPP device to a serial port. Some
     * systems do not give the Bluetooth address of the port, so [mac] can be empty there. Android
     * opens the RFCOMM socket of [mac] and keeps [serialPort] null.
     */
    @Serializable
    @SerialName("bluetooth_spp")
    data class BluetoothSpp(val mac: String = "", val serialPort: String? = null) : AdapterAddress {
        override val kind: TransportKind get() = TransportKind.BluetoothSpp
    }

    /**
     * A USB serial adapter. [serial] is null until the user gives the USB permission.
     * [portIndex] selects one port of a device with more than one
     * port.
     */
    @Serializable
    @SerialName("usb_serial")
    data class UsbSerial(
        val vendorId: Int = 0,
        val productId: Int = 0,
        val serial: String? = null,
        val baudRate: Int = DEFAULT_BAUD_RATE,
        val portIndex: Int = 0,
    ) : AdapterAddress {
        override val kind: TransportKind get() = TransportKind.UsbSerial

        companion object {
            /** The default baud rate of a new profile. A stored value does not change. */
            const val DEFAULT_BAUD_RATE: Int = 115_200

            /** The baud rates that the settings offer. */
            val BAUD_RATES: List<Int> = listOf(9_600, 19_200, 38_400, 57_600, 115_200)
        }
    }

    /** A Wi-Fi TCP adapter. [host] is an IPv4 address, an IPv6 address, or a host name. */
    @Serializable
    @SerialName("tcp")
    data class Tcp(val host: String = "", val port: Int = DEFAULT_PORT) : AdapterAddress {
        override val kind: TransportKind get() = TransportKind.Tcp

        companion object {
            /** The default TCP port of an adapter. */
            const val DEFAULT_PORT: Int = 8080
        }
    }

    /** A BLE adapter (Nordic UART Service). [id] is the BlueFalcon peripheral ID. */
    @Serializable
    @SerialName("ble")
    data class Ble(val id: String = "") : AdapterAddress {
        override val kind: TransportKind get() = TransportKind.Ble
    }

    /** The fake adapter. [scenario] names the fake adapter scenario. */
    @Serializable
    @SerialName("fake")
    data class Fake(val scenario: String = "") : AdapterAddress {
        override val kind: TransportKind get() = TransportKind.Fake
    }
}
