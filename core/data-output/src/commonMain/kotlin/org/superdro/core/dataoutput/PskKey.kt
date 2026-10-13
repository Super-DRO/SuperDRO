package org.superdro.core.dataoutput

/**
 * The pre-shared key of the encrypted mode "Encrypted (PSK)": an identity text and
 * 32 random bytes. The output uses it as an external PSK of TLS 1.3 (RFC 8446), with the hash
 * SHA-256.
 *
 * The share text is `<identity>:<key as 64 lowercase hex digits>`, for example
 * `superdro:00112233...eeff`. It is the line format of the PSK files of GnuTLS (`psktool`). A
 * user copies it to a client. `openssl s_client` takes the identity with `-psk_identity` and the
 * hex key with `-psk`. The key stays in the `SecretStore`, not in the configuration.
 *
 * [toString] does not show the key.
 */
class PskKey(val identity: String, secret: ByteArray) {

    private val bytes: ByteArray = secret.copyOf()

    init {
        require(isValidIdentity(identity)) {
            "The identity must have 1 to $MAX_IDENTITY_LENGTH printable ASCII characters with no ':' and no space."
        }
        require(bytes.size == KEY_BYTES) { "The key must have $KEY_BYTES bytes, not ${bytes.size}." }
    }

    /** A copy of the 32 key bytes. */
    val secret: ByteArray get() = bytes.copyOf()

    /** The identity as UTF-8 bytes (ASCII). */
    val identityBytes: ByteArray get() = identity.encodeToByteArray()

    /** The key as 64 lowercase hex digits. */
    val keyHex: String get() = bytes.joinToString("") { byte -> HEX_DIGITS[(byte.toInt() shr 4) and 0xF].toString() + HEX_DIGITS[byte.toInt() and 0xF] }

    /** The share text `<identity>:<hex key>`. */
    fun shareText(): String = "$identity:$keyHex"

    override fun equals(other: Any?): Boolean =
        other is PskKey && other.identity == identity && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = identity.hashCode() * 31 + bytes.contentHashCode()

    override fun toString(): String = "PskKey(identity=$identity, key=***)"

    companion object {
        /** The number of key bytes: 256 bits. */
        const val KEY_BYTES: Int = 32

        /** The longest identity. */
        const val MAX_IDENTITY_LENGTH: Int = 128

        /** The identity of a new key. */
        const val DEFAULT_IDENTITY: String = "superdro"

        private const val HEX_DIGITS = "0123456789abcdef"

        /** Gives true when [identity] has 1 to 128 characters from 0x21 to 0x7E, with no `:`. */
        fun isValidIdentity(identity: String): Boolean =
            identity.length in 1..MAX_IDENTITY_LENGTH && identity.all { char -> char in '!'..'~' && char != ':' }

        /**
         * Reads a share text `<identity>:<64 hex digits>`. Spaces at the two ends are ignored.
         * The hex digits can be uppercase or lowercase.
         *
         * @throws IllegalArgumentException when the text is not a valid share text.
         */
        fun parse(text: String): PskKey {
            val trimmed = text.trim()
            val separator = trimmed.lastIndexOf(':')
            require(separator > 0) { "A PSK share text has the form <identity>:<hex key>." }
            val identity = trimmed.substring(0, separator)
            val hex = trimmed.substring(separator + 1)
            require(hex.length == 2 * KEY_BYTES) { "The key must have ${2 * KEY_BYTES} hex digits, not ${hex.length}." }
            val bytes = ByteArray(KEY_BYTES) { index ->
                val high = hexValue(hex[2 * index])
                val low = hexValue(hex[2 * index + 1])
                ((high shl 4) or low).toByte()
            }
            return PskKey(identity, bytes)
        }

        /** Makes a new key with 32 bytes from [random] (default: the secure random source of the platform). */
        fun generate(identity: String = DEFAULT_IDENTITY, random: (Int) -> ByteArray = ::secureRandomBytes): PskKey =
            PskKey(identity, random(KEY_BYTES))

        private fun hexValue(char: Char): Int = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> throw IllegalArgumentException("The character '$char' is not a hex digit.")
        }
    }
}

/** Gives [count] bytes from the secure random source of the platform (`java.security.SecureRandom`). */
expect fun secureRandomBytes(count: Int): ByteArray
