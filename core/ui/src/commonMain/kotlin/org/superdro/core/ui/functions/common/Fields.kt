package org.superdro.core.ui.functions.common

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import org.superdro.core.model.Units
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The result of the parse of the text of a number field with the expression evaluator.
 */
sealed interface FieldParse {
    /** The field is empty (only white space). */
    data object Empty : FieldParse

    /** The value of the expression, in the unit of the field. */
    data class Value(val value: Double) : FieldParse

    /** The evaluator refused the text. [error] is the text of the evaluator. */
    data class Invalid(val error: UiText) : FieldParse
}

/** The parse and the text rules of the number fields of the functions area. */
object FieldMath {

    /** The most decimals of a value that a field shows after a unit change or a prefill. */
    const val FIELD_DECIMALS: Int = 6

    /**
     * Evaluates [text]. [current] is the base of the relative entries `++x` and `--x`, in
     * the unit of the field. An empty text gives [FieldParse.Empty].
     */
    fun parse(text: String, current: Double = 0.0): FieldParse {
        if (text.isBlank()) return FieldParse.Empty
        return when (val result = ExpressionEvaluator.evaluate(text, current)) {
            is ExpressionResult.Value -> FieldParse.Value(result.value)
            is ExpressionResult.Error -> FieldParse.Invalid(result.error.toUiText())
        }
    }

    /** The µm in one unit of a length field: 1000 for mm, 25400 for inch. */
    fun umPerUnit(metric: Boolean): Double = if (metric) Units.UM_PER_MM else Units.UM_PER_INCH

    /**
     * Gives the text of [value] for a field: `.` as the decimal mark, at most [maxDecimals]
     * decimals, no zeros at the end, and no negative zero. For example 12.5, 0.1811, or 100.
     */
    fun format(value: Double, maxDecimals: Int = FIELD_DECIMALS): String {
        if (!value.isFinite()) return ""
        val scale = 10.0.pow(maxDecimals)
        val scaled = floor(abs(value) * scale + 0.5)
        if (scaled == 0.0) return "0"
        val negative = value < 0.0
        val whole = floor(scaled / scale).toLong()
        val fraction = (scaled - whole * scale).toLong()
        val fractionText = if (maxDecimals == 0) "" else fraction.toString().padStart(maxDecimals, '0').trimEnd('0')
        return buildString {
            if (negative) append('-')
            append(whole)
            if (fractionText.isNotEmpty()) append('.').append(fractionText)
        }
    }
}

/**
 * The state of a length field: the text, the unit of the field, and the
 * value in µm. A length field has its own inch or mm control. The field keeps the full value in
 * µm: a unit change shows the same length in the new unit, and the value does not change.
 *
 * @property metric The unit of the field: true for mm, false for inch.
 * @property valueUm The value in µm, null for an empty field, or `Double.NaN` when the evaluator
 *   refused the text.
 * @property error The text of the evaluator for a refused text, or null.
 */
@Immutable
data class LengthFieldState(
    val text: String = "",
    val metric: Boolean = false,
    val valueUm: Double? = null,
    val error: UiText? = null,
) {
    /** True when the field is empty. */
    val isEmpty: Boolean get() = valueUm == null

    /**
     * Gives the field with the typed [text]. [currentUm] is the base of `++x` and `--x`, in µm
     * (for example the current position of the axis of a position field).
     */
    fun withText(text: String, currentUm: Double = 0.0): LengthFieldState {
        val factor = FieldMath.umPerUnit(metric)
        val base = if (currentUm.isFinite()) currentUm / factor else 0.0
        return when (val parse = FieldMath.parse(text, base)) {
            FieldParse.Empty -> copy(text = text, valueUm = null, error = null)
            is FieldParse.Value -> copy(text = text, valueUm = parse.value * factor, error = null)
            is FieldParse.Invalid -> copy(text = text, valueUm = Double.NaN, error = parse.error)
        }
    }

    /** Gives the field in the unit [metric]: the same length in the new unit. A refused text stays. */
    fun withUnit(metric: Boolean): LengthFieldState {
        if (metric == this.metric) return this
        val value = valueUm
        return if (value == null || value.isNaN()) {
            copy(metric = metric)
        } else {
            copy(metric = metric, text = FieldMath.format(value / FieldMath.umPerUnit(metric)))
        }
    }

    /** Gives the field with the value [um] (for example a prefill from a sub-datum). Null clears the field. */
    fun withValue(um: Double?): LengthFieldState =
        if (um == null || !um.isFinite()) {
            copy(text = "", valueUm = null, error = null)
        } else {
            copy(text = FieldMath.format(um / FieldMath.umPerUnit(metric)), valueUm = um, error = null)
        }

    companion object {
        /** A field in the unit [metric] with the value [um], or an empty field. */
        fun of(um: Double?, metric: Boolean): LengthFieldState = LengthFieldState(metric = metric).withValue(um)
    }
}

/**
 * The state of a number field with no unit control: an angle in degrees, a count, or a factor.
 *
 * @property value The value in the unit of the field, null for an empty field, or `Double.NaN`
 *   when the evaluator refused the text.
 * @property error The text of the evaluator for a refused text, or null.
 */
@Immutable
data class NumberFieldState(
    val text: String = "",
    val value: Double? = null,
    val error: UiText? = null,
) {
    /** Gives the field with the typed [text]. [current] is the base of `++x` and `--x`. */
    fun withText(text: String, current: Double = 0.0): NumberFieldState = when (val parse = FieldMath.parse(text, current)) {
        FieldParse.Empty -> copy(text = text, value = null, error = null)
        is FieldParse.Value -> copy(text = text, value = parse.value, error = null)
        is FieldParse.Invalid -> copy(text = text, value = Double.NaN, error = parse.error)
    }

    /** The value in arc seconds, for a field in degrees. Null for an empty field. */
    val arcSec: Double? get() = value?.let { degrees -> degrees * Units.ARCSEC_PER_DEGREE }

    /**
     * The value as a count: the value truncated toward 0.
     * Null for an empty field or a value that is not valid.
     */
    val count: Int? get() = value?.takeIf { it.isFinite() && abs(it) < Int.MAX_VALUE }?.toInt()

    companion object {
        /** A field with the value [value], or an empty field. */
        fun of(value: Double?): NumberFieldState =
            if (value == null || !value.isFinite()) NumberFieldState() else NumberFieldState(FieldMath.format(value), value)

        /** A field with the angle [arcSec] in degrees, or an empty field. */
        fun ofArcSec(arcSec: Double?): NumberFieldState = of(arcSec?.let { it / Units.ARCSEC_PER_DEGREE })

        /** A field with the count [count], or an empty field. */
        fun ofCount(count: Int?): NumberFieldState = of(count?.toDouble())
    }
}
