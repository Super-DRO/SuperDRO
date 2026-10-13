package org.superdro.core.ui.settings.components

import kotlin.math.abs
import org.superdro.core.model.Units
import org.superdro.core.readout.NumberPattern

/**
 * The text of a length setting: the value shows in the metric default
 * unit of the profile and the store keeps µm. The factor is 1000 for mm and 25400 for inch. The
 * display format is `####0.0####`: one decimal or more, five decimals or less, and no group
 * separator.
 *
 * Example: 2540 µm shows "0.1" in inch and "2.54" in
 * mm.
 */
object LengthSettingFormat {

    /** The most decimals of the format `####0.0####`. */
    const val MAX_DECIMALS: Int = 5

    private val pattern = NumberPattern("0.00000")

    /** The factor from the unit to µm: 1000 for mm, 25400 for inch. */
    fun factor(metric: Boolean): Double = if (metric) Units.UM_PER_MM else Units.UM_PER_INCH

    /** Gives the text of [um] in mm ([metric]) or inch with the format `####0.0####`. */
    fun format(um: Double, metric: Boolean): String {
        if (!um.isFinite()) return ""
        val text = pattern.format(um / factor(metric))
        val trimmed = text.trimEnd('0')
        return if (trimmed.endsWith('.')) trimmed + "0" else trimmed
    }

    /**
     * Gives the µm of the text [text] in mm ([metric]) or inch, or null when the text is not a
     * number. The text can have a decimal comma. The value is rounded to the five decimals of
     * the format, so that a value that the user typed shows again as typed.
     */
    fun parse(text: String, metric: Boolean): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        val rounded = NumberPattern.roundHalfUp(value * SCALE) / SCALE
        return if (abs(rounded) == 0.0) 0.0 else rounded * factor(metric)
    }

    private const val SCALE: Double = 100_000.0
}
