package org.superdro.core.dataoutput

/**
 * The limits of the data output configuration. The settings screen refuses a value outside a range.
 *
 * The bind address comes from the contract `org.superdro.core.connection.NetworkInterfaces`,
 * and the secrets from `org.superdro.core.util.SecretStore`.
 */
object DataOutputLimits {

    /** The end of each record: one byte 0x0A. */
    const val RECORD_END: Byte = 0x0A

    /** The TCP port of the server. */
    val PORT: IntRange = 1024..65535

    /** The interval of the cadences with an interval, in ms. */
    val INTERVAL_MS: LongRange = 50L..5000L

    /** The rate limit of the cadences "on change", in Hz. */
    val MAX_RATE_HZ: IntRange = 1..20

    /** The decimals of a number. */
    val PRECISION: IntRange = 0..6

    /** The length of a field label. */
    val LABEL_LENGTH: IntRange = 1..32

    /** The minimum time between two records at [rateHz]: `1000 / rateHz` with an integer division. */
    fun minimumGapMs(rateHz: Int): Long {
        require(rateHz in MAX_RATE_HZ) { "The rate $rateHz Hz is not in $MAX_RATE_HZ." }
        return (1000 / rateHz).toLong()
    }
}
