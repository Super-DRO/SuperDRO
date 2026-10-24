package org.superdro.core.mcp

/** Base64 for the token (URL-safe, no padding) and for the `=?base64?...?=` header values. */
object Base64Text {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Encodes [bytes] with the URL-safe alphabet and no padding. 32 bytes give 43 characters. */
    fun encodeUrlSafe(bytes: ByteArray): String = encode(bytes, URL_SAFE, padding = false)

    /** Encodes [bytes] with the standard alphabet and padding. */
    fun encodeStandard(bytes: ByteArray): String = encode(bytes, STANDARD, padding = true)

    private fun encode(bytes: ByteArray, alphabet: String, padding: Boolean): String {
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
            builder.append(alphabet[b0 shr 2])
            builder.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 < 0) {
                if (padding) builder.append("==")
            } else {
                builder.append(alphabet[((b1 and 0x0F) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
                if (b2 < 0) {
                    if (padding) builder.append('=')
                } else {
                    builder.append(alphabet[b2 and 0x3F])
                }
            }
            index += 3
        }
        return builder.toString()
    }

    /**
     * Decodes [text] in the standard or the URL-safe alphabet, with or without padding. Gives
     * null for a text that is not Base64.
     */
    fun decode(text: String): ByteArray? {
        val clean = text.trimEnd('=')
        if (clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4)
        var bits = 0
        var count = 0
        var position = 0
        for (char in clean) {
            val value = when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            bits = (bits shl 6) or value
            count += 6
            if (count >= 8) {
                count -= 8
                out[position++] = ((bits shr count) and 0xFF).toByte()
            }
        }
        return out
    }
}
