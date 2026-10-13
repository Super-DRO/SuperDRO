package org.superdro.core.functions

import kotlin.math.abs
import kotlin.math.floor

/**
 * Gives [value] as a text with [decimals] decimals, `.` as the decimal mark, `-` as the minus
 * sign, and no group separator. The round-off goes half up toward +∞, as Java `Math.round`.
 * The text never shows a negative zero.
 */
internal fun formatFixed(value: Double, decimals: Int): String {
    require(decimals in 0..9) { "The decimals must be 0 to 9 ($decimals)." }
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = floor(value * scale + 0.5).toLong()
    val negative = scaled < 0L
    val magnitude = abs(scaled)
    val whole = magnitude / scale
    val fraction = magnitude % scale
    return buildString {
        if (negative) append('-')
        append(whole)
        if (decimals > 0) {
            append('.')
            append(fraction.toString().padStart(decimals, '0'))
        }
    }
}
