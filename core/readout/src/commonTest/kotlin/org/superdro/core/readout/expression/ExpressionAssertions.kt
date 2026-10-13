package org.superdro.core.readout.expression

import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Evaluates [text] and compares the result with [expected]. The decimal text must be equal to
 * [expected], and the `Double` must be the nearest `Double` of [expected].
 */
internal fun assertValue(expected: String, text: String, current: Double = 0.0) {
    val result = ExpressionEvaluator.evaluate(text, current)
    assertIs<ExpressionResult.Value>(result, "\"$text\" gives $result")
    assertEquals(expected, result.text, "The text of \"$text\"")
    assertEquals(expected.toDouble(), result.value, "The value of \"$text\"")
}

/** Evaluates [text] and compares the error text with [expected]. */
internal fun assertError(expected: String, text: String, current: Double = 0.0) {
    val result = ExpressionEvaluator.evaluate(text, current)
    assertIs<ExpressionResult.Error>(result, "\"$text\" gives $result")
    assertEquals(expected, result.message, "The error of \"$text\"")
}
