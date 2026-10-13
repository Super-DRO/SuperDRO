package org.superdro.core.functions.thread

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * The text formats of the thread setup: a length in mm with 3 decimals
 * (`%.3f`), a length in inch with 4 decimals (`%.4f`), a TPI value with 2 decimals and no zeros at
 * the end. The round-off is a half away from zero, as `%.Nf`.
 */
object ThreadText {

    /** Gives the text of a length of [um] µm in the [unit], for example `9.968` or `0.2489`. */
    fun length(um: Double, unit: ThreadUnit): String = fixed(um / unit.umPerUnit, unit.decimals)

    /** Gives the TPI text of a pitch of [pitchUm] µm, for example `20` or `4.5`. */
    fun threadsPerInch(pitchUm: Double): String = trimNumber(25400.0 / pitchUm, 2)

    /** Gives the value of "Cut load ≈ %1$s×": the ratio with one decimal (`%.1f`), or "—". */
    fun cutLoad(ratio: Double?): String = ratio?.let { fixed(it, 1) } ?: "—"
}

/** Gives [value] with [decimals] decimals. A half goes away from zero. "-0.000" does not occur. */
internal fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    val scale = 10.0.pow(decimals)
    val scaled = floor(abs(value) * scale + 0.5).toLong()
    val negative = value < 0 && scaled != 0L
    val whole = scaled / scale.toLong()
    val fraction = scaled % scale.toLong()
    val text = if (decimals == 0) {
        whole.toString()
    } else {
        whole.toString() + "." + fraction.toString().padStart(decimals, '0')
    }
    return if (negative) "-$text" else text
}

/** Gives [value] with at most [maxDecimals] decimals and no zeros at the end, for example `1.25`. */
internal fun trimNumber(value: Double, maxDecimals: Int): String {
    val text = fixed(value, maxDecimals)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}
