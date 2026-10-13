package org.superdro.platform.android.usb

/** The serial driver of a USB device. */
enum class UsbDriverKind { CdcAcm, Cp210x, Ftdi, Pl2303, Ch34x }

/** The vendor ID and the product ID of a USB device. */
data class UsbDeviceId(val vendorId: Int, val productId: Int) {
    override fun toString(): String = "%04X:%04X".format(vendorId, productId)
}

/**
 * The USB devices that SuperDRO accepts with its own probe table. The device filter
 * `res/xml/superdro_usb_device_filter.xml` has the same IDs. The default prober of
 * usb-serial-for-android comes after this table (see [SuperDroUsbProber]).
 */
object UsbDeviceTable {

    /** Each ID with its driver. */
    val entries: Map<UsbDeviceId, UsbDriverKind> = buildMap {
        fun add(driver: UsbDriverKind, vendorId: Int, vararg productIds: Int) {
            productIds.forEach { productId -> put(UsbDeviceId(vendorId, productId), driver) }
        }
        // CDC-ACM: Arduino, 0x16C0, Atmel, Leaflabs, ARM mbed, ST, Raspberry Pi, WCH CH9102 in CDC mode.
        add(UsbDriverKind.CdcAcm, 0x2341, 0x0001, 0x0043, 0x0010, 0x0042, 0x003B, 0x0044, 0x003F, 0x8036, 0x8037)
        add(UsbDriverKind.CdcAcm, 0x16C0, 0x0483)
        add(UsbDriverKind.CdcAcm, 0x03EB, 0x2044)
        add(UsbDriverKind.CdcAcm, 0x1EAF, 0x0004)
        add(UsbDriverKind.CdcAcm, 0x0D28, 0x0204)
        add(UsbDriverKind.CdcAcm, 0x0483, 0x5740)
        add(UsbDriverKind.CdcAcm, 0x2E8A, 0x0005, 0x000A)
        add(UsbDriverKind.CdcAcm, 0x1A86, 0x55D4)
        add(UsbDriverKind.Cp210x, 0x10C4, 0xEA60, 0xEA70, 0xEA71)
        add(UsbDriverKind.Ftdi, 0x0403, 0x6001, 0x6014, 0x6010, 0x6011, 0x6015)
        add(UsbDriverKind.Pl2303, 0x067B, 0x2303, 0x23A3, 0x23B3, 0x23C3, 0x23D3, 0x23E3, 0x23F3)
        add(UsbDriverKind.Ch34x, 0x1A86, 0x7523, 0x5523)
    }

    /** All IDs of the table. */
    val ids: Set<UsbDeviceId> get() = entries.keys

    /** Gives the driver of the device, or null for a device that is not in the table. */
    fun driverOf(vendorId: Int, productId: Int): UsbDriverKind? = entries[UsbDeviceId(vendorId, productId)]

    /** True when the device is in the table. */
    fun isKnown(vendorId: Int, productId: Int): Boolean = driverOf(vendorId, productId) != null
}
