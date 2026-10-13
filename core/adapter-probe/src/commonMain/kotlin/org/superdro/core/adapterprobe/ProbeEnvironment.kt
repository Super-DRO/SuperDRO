package org.superdro.core.adapterprobe

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.TimeZone
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress

/**
 * A monotonic clock in nanoseconds. Android gives `SystemClock.elapsedRealtimeNanos()`. A
 * test gives the virtual time of the test scheduler.
 */
fun interface ProbeClock {
    /** The time in nanoseconds since an unknown fixed point. The value never decreases. */
    fun nowNanos(): Long
}

/**
 * The link of a run: the adapter address and the SuperDRO transport factory of its link type.
 *
 * @property address The adapter address.
 * @property factory The transport factory of the link type: SPP, USB serial, TCP, or the fake adapter.
 * @param text The text of the link in the log header, or null for the default text.
 */
class LinkSpec(val address: AdapterAddress, val factory: TransportFactory, private val text: String? = null) {

    /** The link name of the file names and the tests: `usb`, `bt`, `tcp`, `ble`, or `fake`. */
    val kind: String = when (address) {
        is AdapterAddress.UsbSerial -> "usb"
        is AdapterAddress.BluetoothSpp -> "bt"
        is AdapterAddress.Tcp -> "tcp"
        is AdapterAddress.Ble -> "ble"
        is AdapterAddress.Fake -> "fake"
    }

    /** The text of the link, for the log header and the summary of T01. */
    fun describe(): String = text ?: when (address) {
        is AdapterAddress.UsbSerial -> {
            val serial = address.serial?.let { " serial $it" }.orEmpty()
            "USB serial ${hex4(address.vendorId)}:${hex4(address.productId)} port ${address.portIndex}$serial, ${address.baudRate} baud, " +
                "8N1, DTR and RTS off (usb-serial-for-android)"
        }
        is AdapterAddress.BluetoothSpp ->
            "Bluetooth SPP ${address.mac}, insecure RFCOMM socket, SPP UUID 00001101-0000-1000-8000-00805F9B34FB"
        is AdapterAddress.Tcp -> "TCP ${address.host}:${address.port} (TCP_NODELAY on)"
        is AdapterAddress.Ble -> "BLE ${address.id}"
        is AdapterAddress.Fake -> "fake adapter, scenario ${address.scenario}"
    }

    /** A key of the link for the report: the same adapter on the same link gives the same key. */
    val key: String
        get() = when (address) {
            is AdapterAddress.UsbSerial -> "usb:${hex4(address.vendorId)}:${hex4(address.productId)}:${address.portIndex}:${address.serial.orEmpty()}"
            is AdapterAddress.BluetoothSpp -> "bt:${address.mac.uppercase()}"
            is AdapterAddress.Tcp -> "tcp:${address.host}:${address.port}"
            is AdapterAddress.Ble -> "ble:${address.id}"
            is AdapterAddress.Fake -> "fake:${address.scenario}"
        }

    private fun hex4(value: Int): String = value.toString(16).uppercase().padStart(4, '0')
}

/**
 * The options of a test, the same as the options of the tool.
 *
 * @property keepAlive `--keepalive on|off`: the BEL byte after 500 ms with no other write (default on).
 * @property durationS `--duration`: the length of the main phase in seconds, or null for the default of the test.
 * @property axis `--axis`: the channel of T10 and T11 (`x y z w u v`).
 * @property pair `--pair`: two readout prefixes for the group check, for example X and W.
 * @property probePrefix `--probe-prefix`: the probe input of T09, `P` or `Q`.
 * @property refRuns `--ref-runs`: T11 with 1 run (no motion) or 2 runs (also over the mark).
 * @property btAddress `--bt-address`: the Bluetooth address of the adapter for the MAC + 2 check, or
 *   null. With null, the app looks in the paired devices.
 * @property label `--label`: a short text for the log header, for example the adapter name.
 * @property printLevel `--print`: the messages that the app shows, or null for the default.
 */
data class TestOptions(
    val keepAlive: Boolean = true,
    val durationS: Double? = null,
    val axis: Char? = null,
    val pair: Pair<String, String>? = null,
    val probePrefix: String = "P",
    val refRuns: Int = 1,
    val btAddress: String? = null,
    val label: String? = null,
    val printLevel: PrintLevel? = null,
) {
    init {
        require(axis == null || axis in ProbeTexts.AXES) { "The axis must be one of x y z w u v, not $axis." }
        require(probePrefix == "P" || probePrefix == "Q") { "The probe prefix must be P or Q, not $probePrefix." }
        require(refRuns == 1 || refRuns == 2) { "The reference runs must be 1 or 2, not $refRuns." }
        require(durationS == null || durationS > 0.0) { "The duration must be more than 0 s." }
    }

    companion object {
        /** Parses `--pair A,B`: two prefixes, in upper case. A text that is not two prefixes gives null. */
        fun parsePair(text: String?): Pair<String, String>? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(",").map { it.trim().uppercase() }
            if (parts.size != 2 || parts.any { it.isEmpty() }) return null
            return parts[0] to parts[1]
        }
    }
}

/** A paired Bluetooth device, for the MAC + 2 check. */
data class PairedAdapter(val address: String, val name: String)

/** The information about the tablet for the report. */
data class TabletInfo(
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val sdkInt: Int = 0,
    val securityPatch: String = "",
    val appVersion: String = "",
)

/**
 * The platform parts that the probe needs. Android gives the clock, the files, and the link
 * dispatcher. A test gives virtual time and the files in memory.
 *
 * @property clock The monotonic clock of the time stamps.
 * @property wallClockMs The wall clock in ms since 1970, for the log header, the file names, and `#get_sig`.
 * @property files The `.bin` and `.log` files of the runs.
 * @property linkIo The dispatcher of the transport open, read, write, and close.
 * @property timeZone The time zone of the file names (the tool uses the local time).
 * @property writer The program name and version in the log header.
 * @property beforeOpen Called before each open of a link, for example to ask for the USB permission again.
 * @property pairedDevices Gives the paired Bluetooth devices.
 */
class ProbeEnvironment(
    val clock: ProbeClock,
    val wallClockMs: () -> Long,
    val files: ProbeFiles,
    val linkIo: CoroutineDispatcher,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    val writer: String = "SuperDRO adapter probe",
    val beforeOpen: suspend (AdapterAddress) -> Unit = {},
    val pairedDevices: suspend () -> List<PairedAdapter> = { emptyList() },
)

/** The kinds of prompts of a test. */
enum class PromptKind {
    /** A text with a "Next" button. The answer is an empty text. */
    Next,

    /** A text with "Next" and "Skip". The answer is an empty text or `skip`. */
    NextOrSkip,

    /** A question with "Yes" and "No". The answer is `yes` or `no`. */
    YesNo,

    /** A number that the user types, or an empty text to skip. */
    Number,

    /** A caution with a field: the user must type `yes` to send the command. */
    TypedYes,
}

/**
 * One prompt of a test.
 *
 * @property kind The kind of the prompt.
 * @property text The text of the prompt, as the tool writes it in the log.
 * @property caution The caution of a [PromptKind.TypedYes] prompt.
 * @property command The command text that a [PromptKind.TypedYes] prompt sends.
 * @property currentMode The current mode of the channel before `#set_mode`, or null.
 */
data class PromptRequest(
    val kind: PromptKind,
    val text: String,
    val caution: String? = null,
    val command: String? = null,
    val currentMode: String? = null,
)

/**
 * The shell of the probe: the UI of the app, or a test. The session gives it the lines, the step
 * texts, the countdown, and the prompts.
 */
interface ProbeShell {

    /** A line of the live messages (the printer of the tool). */
    fun output(line: String) {}

    /** A text that the tool shows and writes in the log (`say`). */
    fun step(text: String) {}

    /** A timed step ends at [endNanos] of the probe clock, or null when no timed step runs. */
    fun countdown(endNanos: Long?) {}

    /** Shows [prompt] and gives the answer of the user. */
    suspend fun ask(prompt: PromptRequest): String
}

/** The printer levels of the tool and of the console filters. */
enum class PrintLevel(val text: String) {
    All("all"),
    Changes("changes"),
    Events("events"),
    None("none"),
    ;

    companion object {
        /** Gives the level of a text, or null. */
        fun of(text: String): PrintLevel? = entries.firstOrNull { it.text == text.trim().lowercase() }
    }
}

/**
 * The live message lines of the tool (`Printer`). The level selects the messages: `all` shows
 * each message, `changes` shows the changes of each input and each event, `events` shows the
 * events and the probe changes, `none` shows nothing.
 */
class ProbePrinter(var level: PrintLevel, private val out: (String) -> Unit) {

    private val lastT = HashMap<String, Double>()
    private val lastV = HashMap<String, Any?>()

    /** Shows one line. */
    fun line(text: String) {
        out(text)
    }

    /** Shows one message when the level selects it. */
    fun message(m: ProbeMessage) {
        val previous = lastT[m.key]
        lastT[m.key] = m.t
        val value: Any? = m.number ?: m.body
        val changed = lastV[m.key] != value
        lastV[m.key] = value
        val level = level
        if (level == PrintLevel.None) return
        if (m.kind == MessageKind.Unknown && level != PrintLevel.All) return
        if ((m.kind == MessageKind.Count || m.kind == MessageKind.Time) && level == PrintLevel.Events) return
        if (m.kind == MessageKind.Bool && level == PrintLevel.Events && !changed) return
        if (level == PrintLevel.Changes &&
            (m.kind == MessageKind.Time || ((m.kind == MessageKind.Count || m.kind == MessageKind.Bool) && !changed))
        ) {
            return
        }
        val dt = if (previous != null) "dt ${PyText.right(PyText.ms(m.t - previous), 8)} ms" else "dt        - ms"
        line("${PyText.right(PyText.ms(m.t), 10)} ms  ${PyText.left(ProbeTexts.describe(m), 52)} $dt")
    }

    /** Shows the characters outside valid messages of one block. */
    fun rejected(text: String, t: Double) {
        if (level != PrintLevel.None) line("${PyText.right(PyText.ms(t), 10)} ms  characters outside valid messages: ${PyText.repr(text.take(120))}")
    }
}
