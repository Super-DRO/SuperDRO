package org.superdro.core.adapterprobe

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.protocol.StatusCode
import org.superdro.core.protocol.Subsystem

/**
 * The texts and the value helpers of `scripts/adapter_probe.py`: the decode of the answers, the
 * MAC + 2 rule, the unit guess, and the cautions.
 */
object ProbeTexts {

    /** The channels of the tool, in the order of the start queries. */
    const val AXES: String = "xyzwuv"

    /** The fields of the "id" group. */
    val ID_FIELDS: List<String> = listOf("mac", "uuid", "features", "uptime", "model")

    /** The fields of the "secondary" group. */
    val SECONDARY_FIELDS: List<String> = listOf("name", "version", "build", "date")

    /** The commands that change the adapter state. They need a typed confirmation. */
    val STATE_COMMANDS: List<String> = listOf("#set_mode", "#clear_counter", "#ref_axis", "#reboot")

    /** The text of the caution before `#set_mode` (`%s` is the channel). */
    fun cautionSetMode(axis: Char): String =
        "#set_mode changes the input mode of channel $axis. Record the current mode (get_mode) first, so that you can set it " +
            "back. A new mode can need a restart."

    /** The text of the caution before `#clear_counter`. */
    fun cautionClear(axis: Char): String =
        "#clear_counter sets the count of channel $axis to zero. A machine datum that uses this channel becomes incorrect."

    /** The text of the caution before `#ref_axis`. */
    fun cautionRef(axis: Char): String =
        "#ref_axis starts a reference mark search on channel $axis. The adapter can change the count of the channel at the mark."

    /** The text of the caution before `#reboot`. */
    const val CAUTION_REBOOT: String = "#reboot restarts the adapter. The link stops. The adapter can lose the counts of the channels."

    /** The text of the caution before a raw text with a command that changes the adapter state. */
    const val CAUTION_RAW: String = "This raw text has a command that changes the adapter state."

    /**
     * The text of the caution before `#get_sig` in the console. The app sends `#get_sig` only
     * when the user asks for it. The caution makes the console request explicit.
     */
    const val CAUTION_GET_SIG: String =
        "#get_sig asks the adapter for a signature. SuperDRO does not use the signature (DC15). Send it only for the " +
            "follow-up of T10 or for test T16."

    /** The caution before each test: only one link at a time. */
    const val CAUTION_ONE_LINK: String =
        "Disconnect the other DRO app (close it, or turn off the Bluetooth or the Wi-Fi of the tablet). The adapter accepts " +
            "only one link. The other DRO app connects again when it comes back."

    /** The warning of T08. */
    const val WARNING_SPINDLE: String =
        "WARNING: Keep your hands, your clothes, and the cables away from the spindle. Use an optical tachometer and read it " +
            "from a safe distance."

    /** The caution of T09. */
    const val CAUTION_PROBE_TIP: String =
        "CAUTION: Make sure that the probe tip and the part cannot move into each other. Move the axis by hand only."

    /** The caution of T10. */
    const val CAUTION_RECORD_MODES: String = "CAUTION: Do T02 first and record the input modes. You need them if the mode changes."

    /** The caution of T13 before the power cycle of step 2. */
    const val CAUTION_POWER_CYCLE: String = "CAUTION: A power cycle can set the counts to zero. The machine datums can become incorrect."

    /**
     * The caution of T15 before the break of a Bluetooth link. On the PC, the `rfkill` command
     * breaks the link. On the tablet, the radio of the quick settings has the same effect.
     */
    const val CAUTION_BREAK_BLUETOOTH: String =
        "CAUTION: When you turn off Bluetooth, a Bluetooth keyboard or mouse of the tablet also disconnects. Use the touch " +
            "screen for this test."

    /**
     * The caution of T15 before the break of a Wi-Fi link. On the PC, the `nmcli` command breaks
     * the link. On the tablet, the radio of the quick settings has the same effect.
     */
    const val CAUTION_BREAK_WIFI: String =
        "CAUTION: When you turn off Wi-Fi, the tablet disconnects from the network. If you control the tablet through the " +
            "network (for example with wireless debugging), you lose that control."

    /**
     * Gives the warnings and the cautions at the top of the test [id].
     * The Test screen shows them before the start. A caution that applies to only one step is
     * in [testSteps]. The confirmation dialogs show the cautions of the commands that change the
     * adapter state again.
     */
    fun testCautions(id: ProbeTestId): List<String> = when (id) {
        ProbeTestId.T08 -> listOf(WARNING_SPINDLE)
        ProbeTestId.T09 -> listOf(CAUTION_PROBE_TIP)
        ProbeTestId.T10 -> listOf(CAUTION_RECORD_MODES)
        ProbeTestId.T15 -> listOf(CAUTION_BREAK_BLUETOOTH, CAUTION_BREAK_WIFI)
        else -> emptyList()
    }

    /** The heading of the follow-up of T10 in [testSteps]. */
    const val FOLLOW_UP_T10: String = "Follow-up when the adapter refuses:"

    /**
     * Gives the steps of the test [id] in the form of the app. Each step has its number, for
     * example `4. ` or `2.3. ` for a sub-step. A line that starts with `CAUTION:` comes before the
     * step that it applies to. A command of the PC tool becomes the action in the app, for example
     * "Push Start.", and an option of the PC tool becomes the option field of the app.
     */
    fun testSteps(id: ProbeTestId): List<String> = when (id) {
        ProbeTestId.T01 -> listOf(
            "1. Turn on the adapter. Wait 10 s.",
            "2. Push Start. Do the test on each link: USB serial, Bluetooth SPP, and Wi-Fi TCP. Do not touch the machine. The app " +
                "writes nothing for the first 5 s.",
        )
        ProbeTestId.T02 -> listOf(
            "1. Push Start. On a USB link or a TCP link, type the Bluetooth address of the adapter in the option \"Bluetooth address\" " +
                "for Q51, or pair the adapter first, so that the app finds the address.",
            "2. Write the line \"Record these input modes\" in the results table.",
            "3. Write the model, the version, the build, and the date in the results table.",
        )
        ProbeTestId.T03 -> listOf(
            "1. Do not touch the machine and the probe.",
            "2. Push Start (default 30 s).",
            "3. With an older adapter, do the test again on its link (Q19).",
        )
        ProbeTestId.T04 -> listOf(
            "1. For Q40, attach the reference scale on the same axis as the target scale. Connect it to a second input.",
            "2. Type the prefixes of the target input and the reference input in the option \"Pair of inputs\", for example X,W. Push " +
                "Start.",
            "3. Push Next at the prompt. Turn the handwheel back and forth for the full test (default 30 s).",
        )
        ProbeTestId.T05 -> listOf(
            "1. Push Start. Do the test on each link.",
            "2. If the rate does not change in 60 s, do the test again with the duration 300 (option \"Duration\").",
        )
        ProbeTestId.T06 -> listOf("1. Push Start. No action is necessary.")
        ProbeTestId.T07 -> listOf("1. Push Start. The test takes about 60 s.")
        ProbeTestId.T08 -> listOf(
            "1. Read the input mode of the tachometer channel in T02. It must be Tachometer (4).",
            "2. Find the number of pulses for each revolution of the sensor.",
            "3. Start the spindle at a low constant speed.",
            "4. Measure the speed with the optical tachometer.",
            "5. Push Start.",
            "6. Push Next when the speed is constant. The app records 20 s.",
            "7. Type the measured speed in RPM and the pulses for each revolution.",
            "8. Do the steps 3 to 7 again at a second speed.",
        )
        ProbeTestId.T09 -> listOf(
            "1. Read the mode of the probe channel in T02. It must be Digital Probe (8).",
            "2. Write down the contact type of the probe: normally open or normally closed.",
            "3. Push Start.",
            "4. Follow the prompts of the app. Push Next at the first step. In the step \"taps\", touch the part 5 times or more. If the " +
                "probe is on the machine, move an axis slowly while you touch the part.",
            "5. If the adapter has a second probe input, do the test again with the probe input Q.",
        )
        ProbeTestId.T10 -> listOf(
            "1. Turn the adapter power off. Wait 10 s. Turn the power on. Keep the other DRO app disconnected. Thus the adapter got no " +
                "#get_sig since the power on.",
            "2. Select a channel in the mode Quadrature A/B with a scale in the option \"Channel\", for example x.",
            "3. Push Start.",
            "4. Push Yes at the question about the power cycle.",
            CAUTION_SAME_MODE,
            "5. Read the caution of the app. Type yes to send #set_mode:<axis>,<current mode>;.",
            "CAUTION: The next command sets the count of the channel to zero. A machine datum that uses this channel becomes incorrect.",
            "6. Read the caution of the app. Type yes to send #clear_counter:<axis>;.",
            FOLLOW_UP_T10,
            "1. Turn the adapter power off and on.",
            "2. Push Console, then \"Open the console\".",
            "3. The app sends get mac, get uuid, get features, get uptime, and get model by itself. No action is necessary.",
            "4. Type get_sig and push Send. Read the caution and type yes.",
            "5. Type get_mode x. Write down the mode.",
            CAUTION_SAME_MODE,
            "6. Type set_mode x <mode> with the mode of step 5. Type yes at the confirmation.",
            "7. Push \"Close the console\".",
        )
        ProbeTestId.T11 -> listOf(
            "1. Read in T02 that the model starts with tda- and that the channel is in the mode Quadrature A/B (2).",
            "2. Select a channel x, y, z, or w with a scale that has a reference mark in the option \"Channel\".",
            "3. Push Start with the reference runs 1 (no motion).",
            "CAUTION: The next command starts a reference search. The adapter can change the count of the channel at the mark.",
            "4. Do not move the axis. Read the caution of the app. Type yes to send #ref_axis:<axis>,15;.",
            "5. For a run over the mark, do the test again with the reference runs 2. In the second run, move the axis slowly over the " +
                "reference mark.",
            "Optional evidence: in the console, send raw #ref_axis:x,5; (with the same caution). If the timeout becomes 5 s, the function " +
                "of the constant is confirmed. SuperDRO always sends the protocol constant 15.",
        )
        ProbeTestId.T12 -> listOf(
            "1. Do not move an axis during the test.",
            "2. Push Start.",
            "CAUTION: The next command restarts the adapter. The link stops. The adapter can lose the counts of the channels.",
            "3. Read the caution of the app. Type yes to send #reboot:1000;.",
            "4. Wait. The app waits up to 30 s for the stop and the start of the stream. If the link closes, it opens the link again each " +
                "1 s for 30 s.",
        )
        ProbeTestId.T13 -> listOf(
            "1. Move each axis away from zero. Then do not move an axis during the test.",
            "2. Push Start.",
            "3. Step 1 of the app closes the link and opens it again. No action is necessary.",
            CAUTION_POWER_CYCLE,
            "4. At the prompt of step 2, turn the adapter power off. Wait 10 s. Turn the power on. Push Next. Push Skip to skip the power " +
                "cycle.",
            "5. If the adapter gets its power from the USB cable, disconnect the cable and connect it again. Android then asks for the USB " +
                "permission again. Push OK.",
        )
        ProbeTestId.T14 -> listOf(
            "1. Push Start. Do the test on the Bluetooth link and on the TCP link.",
            "2. Manual part for Bluetooth (a second link from a different device):",
            "2.1. On the second device, open the console on the Bluetooth link of the adapter: the probe app, or the PC tool with -i.",
            "2.2. Type keepalive off. Do not type more.",
            "2.3. On the tablet, turn on the Bluetooth and connect the other DRO app to the adapter.",
            "2.4. Write down if the app shows the readout, and the time to the readout.",
            "2.5. On the second device, type close. Write down if the app shows the readout now.",
            "2.6. Type quit.",
            "Note: a second RFCOMM link from the same tablet can fail because of the tablet. Then use the manual part.",
        )
        ProbeTestId.T15 -> listOf(
            "1. Push Start. Do the test on the Bluetooth link and on the TCP link.",
            "2. At the first prompt, break the link: turn off Bluetooth or Wi-Fi in the quick settings of the tablet. Push Next.",
            "3. Wait 20 s. This time is shorter than the 30 s limits of the session. The app shows a countdown.",
            "4. At the second prompt, restore the link: turn on Bluetooth or Wi-Fi in the quick settings. Push Next when the link is on " +
                "again.",
            "5. The app opens the link again each 1 s for 120 s.",
        )
        ProbeTestId.T16 -> listOf("1. Push Start. The app sends #get_sig once and does not verify the answer.")
        ProbeTestId.TT1 -> TabletTests.TT1_STEPS.mapIndexed { index, step -> "${index + 1}. $step" }
        ProbeTestId.TT2 -> TabletTests.TT2_STEPS.mapIndexed { index, step -> "${index + 1}. $step" }
    }

    /** The caution before `#set_mode` with the current mode (in the steps and the follow-up of T10). */
    private const val CAUTION_SAME_MODE: String = "CAUTION: The next command sets the channel to its current mode. The adapter can ask for a restart."

    private val subsystemNames: Map<Int, String> = mapOf(
        1 to "ESP32", 2 to "Core", 3 to "Brownout Detection", 4 to "Firmware Update", 6 to "Inputs", 7 to "Touch Probe",
        8 to "Tachometer", 9 to "Home Switch", 0x0A to "RX", 0x32 to "Dirty Axes", 0x77 to "W Axis Reference",
        0x78 to "X Axis Reference", 0x79 to "Y Axis Reference", 0x7A to "Z Axis Reference",
    )

    /** Gives the name of a subsystem, as the tool shows it. */
    fun subsystemName(sub: Int): String = subsystemNames[sub] ?: "Unknown"

    /** Gives the text of a status code (`status_name`). The protocol module has the same table. */
    fun statusName(code: Int): String = if (code < 0) "Status code $code" else StatusCode.text(code)

    /** Gives the name of an info field, or `unknown field`. */
    fun fieldName(field: Int): String = AdapterField.forId(field)?.name?.lowercase() ?: "unknown field"

    /** Gives the short text of one message (`describe`). */
    fun describe(m: ProbeMessage): String = when (m.kind) {
        MessageKind.Count, MessageKind.Bool -> "${m.key} = ${m.number}"
        MessageKind.Time -> "@ 0x${PyText.hex(m.number ?: 0L)} (${m.number})"
        MessageKind.Status, MessageKind.Error -> {
            val sub = m.sub ?: 0
            val code = m.code ?: 0
            "${m.key} sub ${PyText.hex2(sub)} ${subsystemName(sub)}, code ${code.toString(16)} ${statusName(code)}"
        }
        MessageKind.Info -> "?${PyText.hex2(m.field ?: 0)} ${fieldName(m.field ?: 0)} = ${PyText.repr(m.body.orEmpty())}"
        MessageKind.Signature -> "?64 signature, ${m.body.orEmpty().length} Base64 characters"
        MessageKind.Unknown -> "not valid: ${PyText.repr(m.text)}"
    }

    /** The "get field" command text with the two `"` bytes. */
    fun getText(field: String): String = "\"#get:$field;\""

    /** The `#get_sig` text for the wall clock time [epochNowMs]. */
    fun getSigText(epochNowMs: Long): String = AdapterCommand.GetSig.at(epochNowMs).text

    /** The two forms of MAC + 2: with the carry, and on the last byte only. Each is upper case with colons. */
    fun macPlusTwo(macHex: String): Pair<String, String>? {
        if (!MAC_PATTERN.matches(macHex)) return null
        val value = macHex.toLong(16)
        val carry = (value + 2L) and 0xFFFF_FFFF_FFFFL
        val last = (value and 0xFFFF_FFFF_FF00L) or (((value and 0xFF) + 2L) and 0xFF)
        return PyText.macText(carry) to PyText.macText(last)
    }

    /** Gives the hex digits of an address in upper case, with no separators. */
    fun normMac(text: String?): String = text.orEmpty().filter { character -> character.isHexDigitAscii() }.uppercase()

    /** One decoded mode answer. */
    data class ModeAnswer(val channel: Char, val mask: Int, val mode: Int) {
        /** The supported mode IDs, in increasing order. */
        val supported: List<Int> get() = InputMode.wireModes.map { it.id }.sorted().filter { id -> id and mask != 0 }

        /** The name of the current mode, or "Unknown". */
        val name: String get() = InputMode.forId(mode).takeIf { it != InputMode.Unknown }?.text ?: "Unknown"
    }

    /** Decodes a `#get_mode` answer value, for example `x000f0002`. A value that does not match gives null. */
    fun decodeMode(value: String?): ModeAnswer? {
        if (value == null || !MODE_PATTERN.matches(value)) return null
        return ModeAnswer(value[0], value.substring(1, 5).toInt(16), value.substring(5, 9).toInt(16))
    }

    /** Gives a short text that explains an info value (`decode_info`). */
    fun decodeInfo(field: Int, value: String): String {
        val name = AdapterField.forId(field)?.name?.lowercase().orEmpty()
        return try {
            when (name) {
                "mac" -> {
                    val forms = macPlusTwo(value) ?: return "not valid (the protocol needs 12 hex digits)"
                    "MAC + 2 = ${forms.first} (display with no carry: ${forms.second})"
                }
                "uuid" -> if (UUID_PATTERN.matches(value)) "UUID text" else "not a UUID text"
                "features" -> {
                    val features = pyInt(value, 10)
                    "decimal $features, bit 0 (Plus adapter) = ${features and 1L}"
                }
                "uptime" -> "hex $value = ${pyInt(value, 16)}"
                "model" -> if (value.lowercase().startsWith("tda-")) "starts with tda-" else "does not start with tda-"
                "date" -> utcMinute(pyInt(value, 16) * 1000L) + " UTC"
                "mode" -> {
                    val mode = decodeMode(value) ?: return "not valid (the protocol needs [xyzwuv][0-9a-f]{8})"
                    "channel ${mode.channel}: mode ${mode.mode} ${mode.name}; supported ${mode.supported.joinToString(", ")}"
                }
                else -> ""
            }
        } catch (_: IllegalArgumentException) {
            "not valid for this field"
        }
    }

    /**
     * Guesses the unit of a counter from its rate in units for each second (`unit_name`). A rate
     * within 3 % of a reference gives its name.
     */
    fun unitName(perSecond: Double): String {
        for ((name, reference) in UNITS) {
            if (0.97 * reference <= perSecond && perSecond <= 1.03 * reference) return name
        }
        return "not known"
    }

    /** Makes bytes from a typed text (`decode_escapes`): `\xNN` gives one byte, `\\` one backslash, `\r` and `\n` the line bytes. */
    fun decodeEscapes(text: String): ByteArray {
        val out = ArrayList<Byte>()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '\\' && index + 1 < text.length) {
                val next = text[index + 1]
                val hex = text.substring(index + 2, minOf(text.length, index + 4))
                if (next == 'x' && hex.length == 2 && hex.all { it.isHexDigitAscii() }) {
                    out += hex.toInt(16).toByte()
                    index += 4
                    continue
                }
                when (next) {
                    '\\' -> out += '\\'.code.toByte()
                    'r' -> out += '\r'.code.toByte()
                    'n' -> out += '\n'.code.toByte()
                }
                if (next == '\\' || next == 'r' || next == 'n') {
                    index += 2
                    continue
                }
            }
            if (character.code > 0x7F) throw IllegalArgumentException("The raw text must be ASCII: '$character'.")
            out += character.code.toByte()
            index++
        }
        return out.toByteArray()
    }

    /** Gives the text of [bytes] with a `\xNN` for each byte that is not printable ASCII (`printable`). */
    fun printable(bytes: ByteArray): String = buildString {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) append(value.toChar()) else append("\\x").append(PyText.hex2(value))
        }
    }

    /** Gives the bytes as ASCII text with a backslash for each other byte (Python `decode("ascii", "backslashreplace")`). */
    fun asciiBackslash(bytes: ByteArray): String = buildString {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value < 0x80) append(value.toChar()) else append("\\x").append(PyText.hex2(value))
        }
    }

    /** Gives the channel of a lower case axis letter, or null. */
    fun channel(letter: Char): InputChannel? = InputChannel.forLetter(letter.lowercaseChar())

    /** Gives the reference subsystem of a channel `x y z w`, or null. */
    fun refSub(axis: Char): Int? = channel(axis)?.referenceSubsystem

    /** The subsystem of the inputs (`$060;`). */
    const val INPUTS: Int = Subsystem.INPUTS

    /** Gives `yyyy-MM-dd HH:mm` in UTC for [epochMs]. */
    @OptIn(ExperimentalTime::class)
    fun utcMinute(epochMs: Long): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
        return "${time.year.toString().padStart(4, '0')}-${two(time.month.ordinal + 1)}-${two(time.day)} " +
            "${two(time.hour)}:${two(time.minute)}"
    }

    /** Gives `yyyy-MM-ddTHH:mm:ssZ` in UTC for [epochMs] (the log header). */
    @OptIn(ExperimentalTime::class)
    fun utcSecond(epochMs: Long): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
        return "${time.year.toString().padStart(4, '0')}-${two(time.month.ordinal + 1)}-${two(time.day)}T" +
            "${two(time.hour)}:${two(time.minute)}:${two(time.second)}Z"
    }

    /** Gives `yyyyMMdd-HHmmss` in the time zone [zone] for [epochMs] (the file names). */
    @OptIn(ExperimentalTime::class)
    fun fileStamp(epochMs: Long, zone: TimeZone): String {
        val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        return "${time.year.toString().padStart(4, '0')}${two(time.month.ordinal + 1)}${two(time.day)}-" +
            "${two(time.hour)}${two(time.minute)}${two(time.second)}"
    }

    /** Gives `yyyyMMdd-HHmmss` in the time zone of the system for [epochMs]. */
    fun fileStampLocal(epochMs: Long): String = fileStamp(epochMs, TimeZone.currentSystemDefault())

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    /** Parses an integer as Python `int(text, radix)`: an optional sign, digits, and underscores are not accepted here. */
    private fun pyInt(text: String, radix: Int): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw NumberFormatException("empty")
        return trimmed.toLong(radix)
    }

    private fun Char.isHexDigitAscii(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val MAC_PATTERN = Regex("[0-9A-Fa-f]{12}")
    private val MODE_PATTERN = Regex("[xyzwuv][0-9a-f]{8}")
    private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val UNITS: List<Pair<String, Double>> = listOf(
        "ms" to 1000.0, "us" to 1e6, "s" to 1.0, "10 ms" to 100.0, "100 us" to 1e4, "1/1024 s" to 1024.0, "1/32768 s" to 32768.0,
    )
}
