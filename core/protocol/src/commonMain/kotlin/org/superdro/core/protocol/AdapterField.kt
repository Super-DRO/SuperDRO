package org.superdro.core.protocol

/** The lifetime of an adapter value. */
enum class Lifetime {
    /** The value does not change while the adapter runs. An adapter restart keeps it. */
    Constant,

    /** The value can change between two links. An adapter restart clears it. */
    Session,

    /** The value changes. A reported value is old after [DYNAMIC_AGE_LIMIT_MS]. */
    Dynamic,
    ;

    companion object {
        /** The age limit of a [Dynamic] value (`uptime`). */
        const val DYNAMIC_AGE_LIMIT_MS: Long = 5_000L
    }
}

/**
 * The information fields of the `?<field><text>;` answers.
 *
 * @property id The field ID. The answer has it as 2 hex digits.
 * @property getName The name in `"#get:<name>;"`, or null when SuperDRO never sends it.
 * @property lifetime The lifetime of the value.
 */
enum class AdapterField(val id: Int, val getName: String?, val lifetime: Lifetime) {
    /** Field `01`: the modes of a channel. SuperDRO uses `#get_mode`, not `#get:mode`. */
    Mode(0x01, null, Lifetime.Session),
    Uuid(0x02, "uuid", Lifetime.Constant),
    Name(0x03, "name", Lifetime.Session),
    Model(0x04, "model", Lifetime.Constant),
    Mac(0x05, "mac", Lifetime.Constant),
    Version(0x06, "version", Lifetime.Constant),
    Build(0x07, "build", Lifetime.Constant),
    Date(0x08, "date", Lifetime.Constant),
    Uptime(0x09, "uptime", Lifetime.Dynamic),
    Features(0x0A, "features", Lifetime.Session),

    /** Field `64`: the signature. SuperDRO ignores it. */
    Signature(0x64, null, Lifetime.Session),
    ;

    /** The 2 lower case hex digits of the ID, as in the answer. Example: `0a`. */
    val hexId: String get() = id.toString(16).padStart(2, '0')

    /** True when SuperDRO can send `"#get:<name>;"` for this field. */
    val gettable: Boolean get() = getName != null

    companion object {
        /** Gives the field of an ID, or null for an ID that is not in the table (the field −1). */
        fun forId(id: Int): AdapterField? = entries.firstOrNull { it.id == id }

        /** The "id" group of the start queries, in this order. */
        val idGroup: List<AdapterField> = listOf(Mac, Uuid, Features, Uptime, Model)

        /** The "secondary" group of the Adapter Details screen, in this order. */
        val secondaryGroup: List<AdapterField> = listOf(Name, Version, Build, Date)
    }
}

/**
 * The parse and the validity rules of the adapter field values, and
 * the adapter identity.
 */
object AdapterFieldValues {

    private val macPattern = Regex("[0-9A-Fa-f]{12}")
    private val uuidPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
    private val hexPattern = Regex("[0-9A-Fa-f]+")
    private val decimalPattern = Regex("[0-9]+")
    private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
    private const val MAC_MASK = 0xFFFF_FFFF_FFFFL

    /**
     * Parses a `mac` value: exactly 12 hex digits with no separators. The result is lower case.
     * Each other value gives null.
     */
    fun parseMac(text: String): String? = if (macPattern.matches(text)) text.lowercase() else null

    /**
     * Parses a `uuid` value: the UUID text `8-4-4-4-12` hex digits, for example
     * `123e4567-e89b-12d3-a456-426614174000`. The result is lower case. A text that is not a UUID,
     * and the UUID `UUID(0,0)`, give null.
     */
    fun parseUuid(text: String): String? {
        if (!uuidPattern.matches(text)) return null
        val uuid = text.lowercase()
        return if (uuid == NIL_UUID) null else uuid
    }

    /**
     * Parses a hex number: the `date` value (seconds since 1970-01-01 UTC) and the `uptime`
     * value. The text must have only hex digits. A value larger than [Long.MAX_VALUE] gives null.
     */
    fun parseHex(text: String): Long? = if (hexPattern.matches(text)) parseDigits(text, 16) else null

    /** Parses the decimal `features` value. The text must have only decimal digits. */
    fun parseDecimal(text: String): Long? = if (decimalPattern.matches(text)) parseDigits(text, 10) else null

    private fun parseDigits(text: String, radix: Int): Long? {
        var value = 0L
        for (character in text) {
            val digit = character.digitToInt(radix)
            if (value > (Long.MAX_VALUE - digit) / radix) return null
            value = value * radix + digit
        }
        return value
    }

    /**
     * Gives the Bluetooth address of an ESP32 adapter from its base MAC: the MAC + 2 as a 48-bit
     * number, with the carry. The result has 6 bytes of 2 lower case hex digits,
     * joined by `:`. The "Bluetooth ID" text and the adapter identity use this one function.
     *
     * Examples: `246f28a1b2c3` gives "24:6f:28:a1:b2:c5". `246f28a1b2fe` gives
     * "24:6f:28:a1:b3:00". `ffffffffffff` gives "00:00:00:00:00:01".
     *
     * @param mac 12 hex digits. Each other value gives null.
     */
    fun macPlusTwo(mac: String): String? {
        val valid = parseMac(mac) ?: return null
        val value = (valid.toLong(16) + 2L) and MAC_MASK
        val hex = value.toString(16).padStart(12, '0')
        return hex.chunked(2).joinToString(":")
    }
}
