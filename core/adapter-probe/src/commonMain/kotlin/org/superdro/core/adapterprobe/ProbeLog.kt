package org.superdro.core.adapterprobe

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * The text log of format 1. `adapter_probe.py --analyze`
 * reads it. Each line is one received block (`RX`), one sent block (`TX`), the block of a second
 * link (`RX2`), or a note (`NOTE`):
 *
 * ```text
 *      123.456 RX      18 58 31 32 37 30 30 3b 59 2d 33 31 37 35 3b 5a 30 3b | X12700;Y-3175;Z0;
 *     2001.020 TX      11 22 23 67 65 74 3a 6d 61 63 3b 22 | "#get:mac;"
 *     2003.311 NOTE request '"#get:mac;"': ack 1.2 ms, answer after 2.3 ms: ?0530aea4a1b2c3; ...
 * ```
 *
 * The first column is the time in ms from the start of the run, with 3 decimals (µs). The header
 * lines start with `#`. The analysis skips them.
 */
object ProbeLogFormat {

    /** The first line of a log. */
    const val FORMAT_LINE: String = "# adapter_probe.py log, format 1"

    /** The last line of a log. */
    const val END_LINE: String = "# end"

    /** The direction of a received block. */
    const val RX: String = "RX"

    /** The direction of a sent block. */
    const val TX: String = "TX"

    /** The direction of a block of the second link of T14. */
    const val RX2: String = "RX2"

    /**
     * Gives the header lines.
     *
     * @param startUtc The start time, `yyyy-MM-ddTHH:mm:ssZ`.
     * @param mode The mode tag, for example `T03-idle` or `interactive`.
     * @param label The label of the run, or null.
     * @param link The text of the link.
     * @param keepAlive The BEL byte state at the start.
     * @param writer The name and the version of the program that writes the log.
     */
    fun header(startUtc: String, mode: String, label: String?, link: String, keepAlive: Boolean, writer: String): List<String> = listOf(
        FORMAT_LINE,
        "# start (UTC): $startUtc; mode: $mode; label: ${label?.takeIf { it.isNotEmpty() } ?: "-"}",
        "# link: $link",
        "# keep-alive at start: ${if (keepAlive) "on" else "off"}; time scale: 1.0",
        "# columns: time_ms RX|TX length hex_bytes | ascii  (or: time_ms NOTE text)",
        "# writer: $writer",
    )

    /** Gives the time column: `"%12.3f"` of the time in ms, from a time in µs. */
    fun time(us: Long): String {
        val negative = us < 0
        val magnitude = if (negative) -us else us
        val text = "${magnitude / 1000}.${(magnitude % 1000).toString().padStart(3, '0')}"
        return (if (negative) "-$text" else text).padStart(12)
    }

    /** Gives the line of one block: the time, the direction, the length, the hex bytes, ` | `, and the printable ASCII. */
    fun block(us: Long, direction: String, data: ByteArray): String {
        val hex = StringBuilder(data.size * 3)
        val ascii = StringBuilder(data.size)
        data.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            if (index > 0) hex.append(' ')
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            ascii.append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
        return "${time(us)} ${direction.padEnd(4)} ${data.size.toString().padStart(5)} $hex | $ascii"
    }

    /** Gives the line of one note. A line end in [text] becomes ` / `. */
    fun note(us: Long, text: String): String = "${time(us)} NOTE ${text.replace("\n", " / ")}"

    /**
     * Gives the time of a line in seconds, as `adapter_probe.py --analyze` reads it: the time
     * column in ms (µs / 1000), then divided by 1000. The session uses this value for each time
     * of the statistics. Thus the summary of the app and the analysis of its log have the same
     * values. A division by 1e6 can give a different last bit, and then a different rounding of
     * a 0.1 ms text (for example 50.4 in place of 50.5).
     */
    fun seconds(us: Long): Double = (us / 1000.0) / 1000.0

    /**
     * Gives [line] as ASCII. Each other character becomes a backslash form, as the tool writes
     * its log (`errors="backslashreplace"`): `\xNN`, `\uNNNN`, or `\UNNNNNNNN`.
     */
    fun ascii(line: String): String {
        if (line.all { it.code < 0x80 }) return line
        val out = StringBuilder(line.length + 16)
        var index = 0
        while (index < line.length) {
            val character = line[index]
            val code = character.code
            when {
                code < 0x80 -> out.append(character)
                character.isHighSurrogate() && index + 1 < line.length && line[index + 1].isLowSurrogate() -> {
                    val point = 0x10000 + ((code - 0xD800) shl 10) + (line[index + 1].code - 0xDC00)
                    out.append("\\U").append(point.toString(16).padStart(8, '0'))
                    index++
                }
                code < 0x100 -> out.append("\\x").append(code.toString(16).padStart(2, '0'))
                else -> out.append("\\u").append(code.toString(16).padStart(4, '0'))
            }
            index++
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}

/** The two files of one run: the `.bin` file and the `.log` file. */
interface ProbeFileSet {

    /** The file name of the `.log` file, with no folder. */
    val logName: String

    /** The file name of the `.bin` file, with no folder. */
    val binName: String

    /** Writes one line of the `.log` file. The line has no line end. */
    fun writeLogLine(line: String)

    /** Writes received bytes to the `.bin` file, exactly as received. */
    fun writeBin(bytes: ByteArray)

    /** Writes the buffered data to the files. */
    fun flush()

    /** Closes the two files. */
    fun close()
}

/** Opens the files of a run. */
fun interface ProbeFiles {
    /**
     * Opens `<baseName>.bin` and `<baseName>.log`. When the `.log` file is there already, the
     * name gets `-2`, `-3`, and so on, as in the tool.
     */
    fun open(baseName: String): ProbeFileSet
}

/** The files of the runs in a folder, with kotlinx-io. The app gives its log folder. */
class DirectoryProbeFiles(
    private val folder: Path,
    private val fileSystem: FileSystem = SystemFileSystem,
) : ProbeFiles {

    /** Makes the files of the runs in the folder with the path [folder]. */
    constructor(folder: String) : this(Path(folder))

    override fun open(baseName: String): ProbeFileSet {
        if (!fileSystem.exists(folder)) fileSystem.createDirectories(folder)
        var name = baseName
        var number = 1
        while (fileSystem.exists(Path(folder, "$name.log"))) {
            number++
            name = "$baseName-$number"
        }
        val log = fileSystem.sink(Path(folder, "$name.log")).buffered()
        val bin = fileSystem.sink(Path(folder, "$name.bin")).buffered()
        return SinkFileSet("$name.log", "$name.bin", log, bin)
    }

    private class SinkFileSet(
        override val logName: String,
        override val binName: String,
        private val log: Sink,
        private val bin: Sink,
    ) : ProbeFileSet {
        private var closed = false

        override fun writeLogLine(line: String) {
            if (closed) return
            log.writeString(ProbeLogFormat.ascii(line))
            log.writeString("\n")
        }

        override fun writeBin(bytes: ByteArray) {
            if (!closed) bin.write(bytes)
        }

        override fun flush() {
            if (closed) return
            log.flush()
            bin.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            log.close()
            bin.close()
        }
    }
}

/** The files of the runs in memory, for the tests. */
class MemoryProbeFiles : ProbeFiles {

    /** The files of one run in memory. */
    class Files(override val logName: String, override val binName: String) : ProbeFileSet {
        private val lines = ArrayList<String>()
        private val bytes = ArrayList<Byte>()

        /** The lines of the `.log` file. */
        val logLines: List<String> get() = lines.toList()

        /** The bytes of the `.bin` file. */
        val binBytes: ByteArray get() = bytes.toByteArray()

        /** True after [close]. */
        var closed: Boolean = false
            private set

        override fun writeLogLine(line: String) {
            lines += ProbeLogFormat.ascii(line)
        }

        override fun writeBin(bytes: ByteArray) {
            bytes.forEach { this.bytes += it }
        }

        override fun flush() = Unit

        override fun close() {
            closed = true
        }
    }

    private val opened = ArrayList<Files>()

    /** The runs in the order of their open. */
    val runs: List<Files> get() = opened.toList()

    override fun open(baseName: String): ProbeFileSet {
        var name = baseName
        var number = 1
        while (opened.any { it.logName == "$name.log" }) {
            number++
            name = "$baseName-$number"
        }
        return Files("$name.log", "$name.bin").also { opened += it }
    }
}
