package org.superdro.core.readout.expression

/** The result of [ExpressionEvaluator.evaluate]: a value or an error. */
sealed interface ExpressionResult {

    /**
     * A value.
     *
     * @property text The decimal value in plain notation, without an exponent and without
     *   trailing zeros after the decimal point. For example "3.333333333333333" for `10/3`.
     * @property value The nearest `Double` of [text]. It is always finite. The callers use this
     *   value.
     */
    data class Value(val text: String, val value: Double) : ExpressionResult

    /** An error. [message] is the English text of [error]. */
    data class Error(val error: ExpressionError) : ExpressionResult {
        val message: String get() = error.message
    }
}

/** Gives the `Double` of a [ExpressionResult.Value], or null for an [ExpressionResult.Error]. */
fun ExpressionResult.valueOrNull(): Double? = (this as? ExpressionResult.Value)?.value
