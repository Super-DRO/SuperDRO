package org.superdro.core.store.files

/**
 * The text of an import file and the checks of the full file.
 */
object ImportText {

    /** The largest file that an import reads: 1,000,000 bytes. */
    const val MAX_BYTES: Int = 1_000_000

    /** The UTF-8 byte order mark as a character. */
    const val BOM: Char = '﻿'

    private const val BINARY_SAMPLE = 2000
    private const val BINARY_FRACTION = 20

    /** The result of [decode]: the text, or the problem that stops the import. */
    sealed interface Decoded {
        data class Text(val text: String) : Decoded
        data class Refused(val problem: ImportProblem) : Decoded
    }

    /**
     * Decodes [bytes] as UTF-8 and removes a byte order mark at the start. A file of more
     * than [MAX_BYTES] bytes, an empty file, and a binary file give a problem.
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size > MAX_BYTES) return Decoded.Refused(ImportProblem.error(MessageKey.FileTooLarge, MAX_BYTES))
        val text = removeBom(bytes.decodeToString())
        return check(text)?.let { problem -> Decoded.Refused(problem) } ?: Decoded.Text(text)
    }

    /** Removes the byte order mark at the start of [text], if it has one. */
    fun removeBom(text: String): String = if (text.isNotEmpty() && text[0] == BOM) text.substring(1) else text

    /**
     * Gives the problem of an empty or a binary text, or null. The text is binary when it has a
     * NUL character, or when more than 1/20 of the first 2000 characters are U+FFFD or control
     * characters other than CR, LF, and TAB.
     */
    fun check(text: String): ImportProblem? {
        if (text.isBlank()) return ImportProblem.error(MessageKey.FileEmpty)
        if (isBinary(text)) return ImportProblem.error(MessageKey.FileBinary)
        return null
    }

    internal fun isBinary(text: String): Boolean {
        if ('\u0000' in text) return true
        val sample = text.take(BINARY_SAMPLE)
        val bad = sample.count { character ->
            character == '�' || (character.isISOControl() && character != '\r' && character != '\n' && character != '\t')
        }
        return bad * BINARY_FRACTION > sample.length
    }

    /** Gives the lines of [text]. A line ends with CR LF, LF, or CR. */
    internal fun lines(text: String): List<String> = text.split("\r\n", "\n", "\r")
}
