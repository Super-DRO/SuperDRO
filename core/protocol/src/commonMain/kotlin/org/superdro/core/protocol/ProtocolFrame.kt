package org.superdro.core.protocol

/**
 * The character set and the message frame of the adapter protocol.
 *
 * - The adapter sends printable ASCII 0x20 to 0x7E. The parser drops each other byte, also inside
 *   a message.
 * - A message starts with one start character and ends with `;`.
 * - The frame has no line end, no length field, no checksum, and no sequence number.
 */
object ProtocolFrame {

    /** The end of each message: `;` (0x3B). */
    const val END: Char = ';'

    /** The start of a time stamp message. */
    const val TIMESTAMP: Char = '@'

    /** The start of a status message. */
    const val STATUS: Char = '$'

    /** The start of an error message. */
    const val ERROR: Char = '!'

    /** The start of an information or signature message. */
    const val INFO: Char = '?'

    /** The start of the dirty status pseudo readout (logged only). */
    const val DIRTY_STATUS: Char = '.'

    /** The start of an outbound command, for example `#get:mac;`. */
    const val COMMAND: Char = '#'

    /** True when the parser keeps [byte]: 0x20 to 0x7E. */
    fun isFrameByte(byte: Byte): Boolean = (byte.toInt() and 0xFF) in 0x20..0x7E

    /**
     * True when [character] can start a message. The prefix letters are not
     * case-sensitive.
     */
    fun isStart(character: Char): Boolean {
        val lower = character.lowercaseChar()
        return lower in 'a'..'f' || lower in 'p'..'z' || character in "@\$!?."
    }
}
