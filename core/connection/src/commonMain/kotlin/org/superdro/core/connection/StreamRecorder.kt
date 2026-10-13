package org.superdro.core.connection

import org.superdro.core.model.AdapterAddress

/**
 * One received chunk of a recorded byte stream.
 *
 * @property timeMs The arrival time in ms after the open of the link.
 * @property bytes The bytes of the chunk, as the transport gave them.
 */
class RecordedChunk(val timeMs: Long, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RecordedChunk && other.timeMs == timeMs && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * timeMs.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "RecordedChunk($timeMs ms, ${bytes.size} bytes)"
}

/**
 * Receives the chunks of one recorded link. The platform gives the file.
 * The user exports the file through the share sheet.
 */
interface RecordSink {

    /** Writes one chunk. */
    fun write(chunk: RecordedChunk)

    /** The link closed. Flush and close the file. */
    fun close()
}

/**
 * Opens a [RecordSink] for each link of a session that has [DeviceRef.recordStream]. The platform
 * gives the implementation.
 */
fun interface StreamRecorder {

    /**
     * Opens a sink for the link number [link] (1 for the first link) of [device] to [address].
     * Null means: no record for this link.
     */
    fun open(device: DeviceId, address: AdapterAddress, link: Int): RecordSink?

    companion object {
        /** A recorder that records nothing. */
        val None: StreamRecorder = StreamRecorder { _, _, _ -> null }
    }
}

/**
 * The text form of a recorded byte stream, version 1. The replay transport reads it.
 *
 * ```text
 * # SuperDRO byte stream 1
 * 0 5831323030 3b
 * 250 40334538 3b
 * ```
 *
 * - The first line is [HEADER]. A line that starts with `#` is a comment.
 * - Each other line is one chunk: the arrival time in ms after the open of the link, a space, and
 *   the bytes as lower case hex digits. Spaces between the hex digits are permitted.
 */
object RecordedStream {

    /** The first line of the file. */
    const val HEADER: String = "# SuperDRO byte stream 1"

    private const val HEX = "0123456789abcdef"

    /** Gives the line of [chunk], with no line end. */
    fun formatLine(chunk: RecordedChunk): String {
        val text = StringBuilder(chunk.bytes.size * 2 + 12)
        text.append(chunk.timeMs).append(' ')
        chunk.bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            text.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return text.toString()
    }

    /** Gives the file text of [chunks]: the header, then one line for each chunk. */
    fun format(chunks: List<RecordedChunk>): String =
        (listOf(HEADER) + chunks.map(::formatLine)).joinToString(separator = "\n", postfix = "\n")

    /**
     * Parses the file text [text]. Empty lines and comments are skipped.
     *
     * @throws IllegalArgumentException for a line that is not a chunk, or for times that go back.
     */
    fun parse(text: String): List<RecordedChunk> {
        val chunks = ArrayList<RecordedChunk>()
        var last = Long.MIN_VALUE
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val space = line.indexOf(' ')
            val timeText = if (space < 0) line else line.substring(0, space)
            val time = requireNotNull(timeText.toLongOrNull()) { "Line ${index + 1}: \"$timeText\" is not a time." }
            require(time >= last) { "Line ${index + 1}: the time $time ms is before the time $last ms." }
            last = time
            val hex = if (space < 0) "" else line.substring(space + 1).filterNot { it == ' ' }
            require(hex.length % 2 == 0) { "Line ${index + 1}: the hex text has an odd length." }
            val bytes = ByteArray(hex.length / 2) { position ->
                val high = HEX.indexOf(hex[position * 2].lowercaseChar())
                val low = HEX.indexOf(hex[position * 2 + 1].lowercaseChar())
                require(high >= 0 && low >= 0) { "Line ${index + 1}: \"${hex.substring(position * 2, position * 2 + 2)}\" is not hex." }
                ((high shl 4) or low).toByte()
            }
            chunks += RecordedChunk(time, bytes)
        }
        return chunks
    }
}

/**
 * A [RecordSink] that writes the text form of [RecordedStream] line by line. The platform gives
 * the function that writes one line to the file.
 *
 * @param writeLine Writes one line with its line end.
 * @param onClose Flushes and closes the file.
 */
class LineRecordSink(
    private val writeLine: (String) -> Unit,
    private val onClose: () -> Unit = {},
) : RecordSink {

    private var started = false

    override fun write(chunk: RecordedChunk) {
        if (!started) {
            started = true
            writeLine(RecordedStream.HEADER)
        }
        writeLine(RecordedStream.formatLine(chunk))
    }

    override fun close() {
        if (!started) {
            started = true
            writeLine(RecordedStream.HEADER)
        }
        onClose()
    }
}
