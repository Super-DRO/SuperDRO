package org.superdro.core.protocol

/**
 * The subsystems of the status and error messages.
 *
 * The subsystem is the first two hex digits after `$` or `!`. The names are protocol texts.
 */
object Subsystem {
    /** Internal only: the subsystem of a timeout. */
    const val NONE: Int = -1
    const val ESP32: Int = 0x01
    const val CORE: Int = 0x02
    const val BROWNOUT_DETECTION: Int = 0x03
    const val FIRMWARE_UPDATE: Int = 0x04

    /** The result of `#set_mode`. */
    const val INPUTS: Int = 0x06
    const val TOUCH_PROBE: Int = 0x07
    const val TACHOMETER: Int = 0x08
    const val HOME_SWITCH: Int = 0x09

    /** The command receiver of the adapter: ack, Busy, Bad data, Failed. */
    const val RX: Int = 0x0A

    /** The code of this subsystem is a bit mask of the dirty channels. */
    const val DIRTY_AXES: Int = 0x32

    /** The reference subsystems are the ASCII codes of `w`, `x`, `y`, `z`. */
    const val W_AXIS_REFERENCE: Int = 0x77
    const val X_AXIS_REFERENCE: Int = 0x78
    const val Y_AXIS_REFERENCE: Int = 0x79
    const val Z_AXIS_REFERENCE: Int = 0x7A

    private val names: Map<Int, String> = mapOf(
        NONE to "None",
        ESP32 to "ESP32",
        CORE to "Core",
        BROWNOUT_DETECTION to "Brownout Detection",
        FIRMWARE_UPDATE to "Firmware Update",
        INPUTS to "Inputs",
        TOUCH_PROBE to "Touch Probe",
        TACHOMETER to "Tachometer",
        HOME_SWITCH to "Home Switch",
        RX to "RX",
        DIRTY_AXES to "Dirty Axes",
        W_AXIS_REFERENCE to "W Axis Reference",
        X_AXIS_REFERENCE to "X Axis Reference",
        Y_AXIS_REFERENCE to "Y Axis Reference",
        Z_AXIS_REFERENCE to "Z Axis Reference",
    )

    /** Gives the name of the subsystem [id]. A subsystem that is not in the table gives "Unknown". */
    fun name(id: Int): String = names[id] ?: "Unknown"
}

/**
 * The status codes of the status and error messages.
 *
 * The code is 1 to 4 hex digits after the subsystem. A `$` message with a code from 1 to 159 uses
 * the error path. A `!` message always uses the error path.
 */
object StatusCode {
    /** Internal only: the default. */
    const val NONE: Int = -1
    const val OK: Int = 0x00
    const val FAILED: Int = 0x01
    const val INVALID_STATE: Int = 0x02
    const val INVALID_ARGUMENT: Int = 0x03
    const val PERIPHERAL_NOT_FOUND: Int = 0x04
    const val NOT_SUPPORTED: Int = 0x05
    const val INITIALIZATION_FAILED: Int = 0x14
    const val SYSTEM_TIMEOUT: Int = 0x15

    /** Ends a reference search. */
    const val USER_TIMEOUT: Int = 0x16
    const val PERIPHERAL_BUSY: Int = 0x17
    const val PERIPHERAL_OVERFLOW: Int = 0x18
    const val DATA_CHECKSUM_FAILED: Int = 0x1E
    const val INVALID_DATA_LENGTH: Int = 0x1F

    /** On the subsystem RX: send the request again. */
    const val BUSY: Int = 0x20

    /** On the subsystem RX: call the error function. */
    const val BAD_DATA: Int = 0x21

    /** A reference search started. */
    const val INITIALIZED: Int = 0xA0

    /** A reference search is complete. */
    const val FINISHED: Int = 0xA1
    const val READY: Int = 0xA2

    /** On the subsystem RX: the ack of a command. */
    const val RECEIVED: Int = 0xA3
    const val WRITE_OK: Int = 0xA5
    const val READ_OK: Int = 0xA6
    const val NOT_CHANGED: Int = 0xA7
    const val DONE: Int = 0xA8

    /** A reference search is cancelled. */
    const val CANCELLED: Int = 0xA9

    /** The adapter accepts a reference search. */
    const val NOOP: Int = 0xAA
    const val X_AXIS_CLEARED: Int = 0xC8
    const val Y_AXIS_CLEARED: Int = 0xC9
    const val Z_AXIS_CLEARED: Int = 0xCA
    const val W_AXIS_CLEARED: Int = 0xCB
    const val U_AXIS_CLEARED: Int = 0xCC
    const val V_AXIS_CLEARED: Int = 0xCD
    const val REBOOTING: Int = 0xFF

    /** Internal only: a wait ended. A code 256 on the wire gives "Status code 256". */
    const val TIMEOUT: Int = 256

    /** The text of the internal [TIMEOUT]. */
    const val TIMEOUT_TEXT: String = "Timeout"

    private val texts: Map<Int, String> = mapOf(
        NONE to "None",
        OK to "OK",
        FAILED to "Failed",
        INVALID_STATE to "Invalid state",
        INVALID_ARGUMENT to "Invalid argument",
        PERIPHERAL_NOT_FOUND to "Peripheral not found",
        NOT_SUPPORTED to "Not supported",
        INITIALIZATION_FAILED to "Initialization failed",
        SYSTEM_TIMEOUT to "System timeout",
        USER_TIMEOUT to "User timeout",
        PERIPHERAL_BUSY to "Peripheral busy",
        PERIPHERAL_OVERFLOW to "Peripheral overflow",
        DATA_CHECKSUM_FAILED to "Data checksum failed",
        INVALID_DATA_LENGTH to "Invalid data length",
        BUSY to "Busy",
        BAD_DATA to "Bad data",
        INITIALIZED to "Initialized",
        FINISHED to "Finished",
        READY to "Ready",
        RECEIVED to "Received",
        WRITE_OK to "Write OK",
        READ_OK to "Read OK",
        NOT_CHANGED to "Not changed",
        DONE to "Done",
        CANCELLED to "Cancelled",
        NOOP to "NOOP",
        X_AXIS_CLEARED to "X Axis Cleared",
        Y_AXIS_CLEARED to "Y Axis Cleared",
        Z_AXIS_CLEARED to "Z Axis Cleared",
        W_AXIS_CLEARED to "W Axis Cleared",
        U_AXIS_CLEARED to "U Axis Cleared",
        V_AXIS_CLEARED to "V Axis Cleared",
        REBOOTING to "Rebooting",
    )

    /**
     * Gives the text of a status code. A code that is not in the table gives `Status code <n>`,
     * with `n` in decimal. Thus 164 gives "Status code 164", and 256 gives "Status code 256".
     */
    fun text(code: Int): String = texts[code] ?: "Status code $code"

    /** True when a `$` message with [code] uses the error path: the codes 1 to 159. */
    fun isErrorPath(code: Int): Boolean = code in 1..159
}

/**
 * A failure of a request.
 *
 * @property subsystem The subsystem, or [Subsystem.NONE] for a timeout.
 * @property code The status code, or [StatusCode.TIMEOUT] for a timeout.
 * @property text The text of the status code.
 */
data class CommandFailure(val subsystem: Int, val code: Int, val text: String) {

    /** The failure text of the channel row: `Command failed: <text> (<sub>:<code>)`, in decimal. */
    val message: String get() = "Command failed: $text ($subsystem:$code)"

    /** True when this failure is the internal timeout. */
    val isTimeout: Boolean get() = subsystem == Subsystem.NONE && code == StatusCode.TIMEOUT

    companion object {
        /** The internal timeout: subsystem −1 ("None") and code 256 ("Timeout"). */
        val TIMEOUT: CommandFailure = CommandFailure(Subsystem.NONE, StatusCode.TIMEOUT, StatusCode.TIMEOUT_TEXT)

        /** Gives the failure of a status or error message from the adapter. */
        fun of(subsystem: Int, code: Int): CommandFailure = CommandFailure(subsystem, code, StatusCode.text(code))
    }
}
