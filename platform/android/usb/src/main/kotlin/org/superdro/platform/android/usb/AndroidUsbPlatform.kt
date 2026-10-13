package org.superdro.platform.android.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

/** Gives the USB manager of the device, or null when the device has no USB host. */
internal fun Context.usbManager(): UsbManager? = getSystemService(UsbManager::class.java)

/**
 * Reads the serial number of [device] only when the app has its permission. Without it,
 * `getSerialNumber()` throws a `SecurityException` for an app that targets API 29 or higher.
 */
internal fun UsbManager.serialOf(device: UsbDevice): String? =
    if (hasPermission(device)) runCatching { device.serialNumber }.getOrNull()?.takeIf { text -> text.isNotBlank() } else null

/** Finds the attached device of [address] (see [UsbIdentity.matches] for the serial number rule). */
internal fun UsbManager.deviceOf(address: AdapterAddress.UsbSerial): UsbDevice? =
    deviceList.values.firstOrNull { device ->
        device.vendorId == address.vendorId &&
            device.productId == address.productId &&
            (address.serial == null || serialOf(device).let { serial -> serial == null || serial == address.serial })
    }

/** The Android USB calls of [UsbSerialTransport]. */
internal class AndroidUsbPlatform(
    context: Context,
    private val prober: SuperDroUsbProber = SuperDroUsbProber(),
) : UsbPlatform {

    private val context: Context = context.applicationContext

    override fun openPort(address: AdapterAddress.UsbSerial, settings: UsbLineSettings): UsbPort {
        val manager = context.usbManager()
            ?: throw LinkFailureException(LinkFailure.DeviceGone, "This device has no USB host.")
        val device = manager.deviceOf(address)
            ?: throw LinkFailureException(LinkFailure.DeviceGone, "The USB device ${address.describe()} is not attached.")
        if (!manager.hasPermission(device)) {
            throw LinkFailureException(LinkFailure.PermissionMissing, "The app has no permission for ${address.describe()}.")
        }
        val driver = prober.probe(device)
            ?: throw LinkFailureException(LinkFailure.Refused, "No serial driver accepts ${address.describe()}.")
        val serialPort = driver.ports.getOrNull(address.portIndex)
            ?: throw LinkFailureException(LinkFailure.DeviceGone, "The USB device has no port ${address.portIndex}.")
        val connection = manager.openDevice(device)
            ?: throw LinkFailureException(
                if (manager.hasPermission(device)) LinkFailure.Refused else LinkFailure.PermissionMissing,
                "openDevice() gave no connection for ${address.describe()}.",
            )
        try {
            serialPort.open(connection)
        } catch (openFailure: Exception) {
            connection.close()
            throw LinkFailureException(LinkFailure.Refused, "The USB port ${address.describe()} did not open.", openFailure)
        }
        try {
            serialPort.setParameters(settings.baudRate, settings.dataBits, settings.stopBits, settings.parity)
            keepControlLinesLow(serialPort, settings)
        } catch (settingsFailure: Exception) {
            runCatching { serialPort.close() }
            throw LinkFailureException(
                LinkFailure.Refused,
                "The line settings ${settings.baudRate} 8N1 of ${address.describe()} failed.",
                settingsFailure,
            )
        }
        return AndroidUsbPort(serialPort)
    }

    override fun isAttached(address: AdapterAddress.UsbSerial): Boolean =
        context.usbManager()?.deviceOf(address) != null

    /**
     * Keeps DTR and RTS false. The drivers open with both lines not asserted. The
     * transport writes false again only for a line that a driver reports as true, so that no line
     * changes. A driver with no control lines is not an error.
     */
    private fun keepControlLinesLow(serialPort: UsbSerialPort, settings: UsbLineSettings) {
        try {
            if (serialPort.dtr != settings.dtr) serialPort.dtr = settings.dtr
            if (serialPort.rts != settings.rts) serialPort.rts = settings.rts
        } catch (_: UnsupportedOperationException) {
            // This driver has no control lines.
        } catch (_: IOException) {
            // The lines keep the state of the open, which is not asserted.
        }
    }
}

/** A [UsbPort] on a `UsbSerialPort` of usb-serial-for-android. */
internal class AndroidUsbPort(private val serialPort: UsbSerialPort) : UsbPort {

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = serialPort.read(buffer, timeoutMs)

    /** The library writes in chunks of the endpoint packet size, with [timeoutMs] as the total limit. */
    override fun write(bytes: ByteArray, timeoutMs: Int) = serialPort.write(bytes, timeoutMs)

    override fun close() {
        try {
            serialPort.close()
        } catch (_: IOException) {
            // The port is closed already.
        }
    }
}
