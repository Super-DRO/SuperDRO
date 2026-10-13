package org.superdro.platform.desktop.serial

import com.fazecast.jSerialComm.SerialPort
import com.fazecast.jSerialComm.SerialPortInvalidPortException
import com.toxicbakery.logging.Arbor
import java.io.IOException
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException

/**
 * The serial ports of the operating system through jSerialComm (Apache 2.0, one of its two
 * licenses). jSerialComm has its own native library in the jar for Linux, macOS, and Windows.
 *
 * - [list] reads the ports with `SerialPort.getCommPorts()`. jSerialComm gives the text "Unknown"
 *   for a value that the system does not give. This class changes it to null.
 * - [open] sets 8N1, no flow control, a semi-blocking read with the read timeout, a blocking write
 *   with the write timeout, and DTR and RTS false. It clears DTR and RTS before the open and again
 *   after the open.
 *
 * @param osName The `os.name` of the system. It selects the meaning of the error codes.
 * @param bluetoothMacOf Gives the Bluetooth address of a port name, or null. The default reads the
 *   address of a Linux RFCOMM port.
 */
class JSerialCommPorts(
    osName: String = System.getProperty("os.name").orEmpty(),
    private val bluetoothMacOf: (String) -> String? = { name -> LinuxRfcomm.macOf(name) },
) : SerialPorts {

    private val windows = SerialPortRules.isWindows(osName)

    override fun list(): List<SerialPortInfo> = try {
        SerialPort.getCommPorts().map(::infoOf)
    } catch (error: Exception) {
        log.e(error) { "The serial port list failed" }
        emptyList()
    } catch (error: LinkageError) {
        // The native library of jSerialComm did not load.
        log.e(error) { "The serial port library did not load" }
        emptyList()
    }

    override fun open(id: String, settings: SerialLineSettings): OpenSerialPort {
        val port = try {
            SerialPort.getCommPort(id)
        } catch (error: SerialPortInvalidPortException) {
            throw LinkFailureException(LinkFailure.DeviceGone, "The serial port $id is not there.", error)
        } catch (error: LinkageError) {
            throw LinkFailureException(LinkFailure.Refused, "The serial port library did not load.", error)
        }
        port.setComPortParameters(settings.baudRate, settings.dataBits, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING,
            settings.readTimeoutMs,
            settings.writeTimeoutMs,
        )
        // jSerialComm keeps these values and applies them at the open.
        port.clearDTR()
        port.clearRTS()
        if (!port.openPort()) {
            val code = port.lastErrorCode
            val failure = SerialPortRules.openFailure(code, windows)
            throw LinkFailureException(failure, "The open of $id failed: error $code at line ${port.lastErrorLocation}.")
        }
        // Some systems set the lines at the open. Clear them again at once.
        port.clearDTR()
        port.clearRTS()
        log.i { "Opened $id at ${settings.baudRate} baud, 8N1, DTR and RTS false" }
        return JSerialCommOpenPort(port, id)
    }

    private fun infoOf(port: SerialPort): SerialPortInfo {
        val path = port.systemPortPath.orEmpty()
        val name = port.systemPortName.orEmpty()
        // Windows gives the path \\.\COM5. The address keeps the name COM5, which getCommPort accepts.
        val id = if (path.startsWith("""\\.\""") || path.isEmpty()) name else path
        val usb = port.vendorID >= 0 && port.productID >= 0
        return SerialPortInfo(
            id = id,
            name = name,
            description = known(port.portDescription).orEmpty(),
            friendlyName = known(port.descriptivePortName).orEmpty(),
            vendorId = port.vendorID.takeIf { usb },
            productId = port.productID.takeIf { usb },
            serialNumber = known(port.serialNumber),
            manufacturer = known(port.manufacturer),
            location = known(port.portLocation),
            bluetoothMac = bluetoothMacOf(name),
            accessible = SerialPortRules.isAccessible(id),
        )
    }

    private fun known(text: String?): String? =
        text?.trim()?.takeUnless { value -> value.isEmpty() || value.equals(UNKNOWN, ignoreCase = true) }

    private class JSerialCommOpenPort(private val port: SerialPort, private val id: String) : OpenSerialPort {

        override fun read(buffer: ByteArray): Int {
            val count = port.readBytes(buffer, buffer.size)
            if (count < 0) throw IOException("The read of $id failed: error ${port.lastErrorCode}.")
            return count
        }

        override fun write(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = port.writeBytes(bytes, bytes.size - offset, offset)
                if (count < 0) throw IOException("The write to $id failed: error ${port.lastErrorCode}.")
                if (count == 0) throw IOException("The write to $id did not end in its time limit.")
                offset += count
            }
        }

        override fun close() {
            if (port.isOpen) port.closePort()
        }
    }

    private companion object {
        const val UNKNOWN = "Unknown"
        val log = Arbor.tag("SerialPorts")
    }
}
