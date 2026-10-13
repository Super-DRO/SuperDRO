package org.superdro.core.readout.expression

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The string file `strings_expression.xml` of `core:resources` has a resource for each
 * [ExpressionError.key]. The English value with the arguments gives [ExpressionError.message].
 *
 * The files write each apostrophe as `\u0027`. Compose Multiplatform 1.10.3 changes `\u0027` to
 * an apostrophe, but it keeps the backslash of `\'`.
 */
class ExpressionStringsTest {

    private val directory = File("../resources/src/commonMain/composeResources")

    private fun strings(locale: String): Map<String, String> {
        val file = File(directory, "$locale/strings_expression.xml")
        assertTrue(file.isFile, "${file.path} must exist")
        return Regex("""<string name="([a-z_]+)">(.*?)</string>""")
            .findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2] }
    }

    /** One error of each kind and each text form. */
    private val samples: List<ExpressionError> = listOf(
        ExpressionError.InvalidToken("#"),
        ExpressionError.InvalidNumber("1.2.3"),
        ExpressionError.UndefinedFunction("foo"),
        ExpressionError.UndefinedVariable("x"),
        ExpressionError.InvalidAssignmentTarget,
        ExpressionError.ExpectedParenAfterArguments,
        ExpressionError.ExpectedParenAfterExpression,
        ExpressionError.ExpectedExpression(null),
        ExpressionError.ExpectedExpression("*"),
        ExpressionError.ExpectedEndOfExpression,
        ExpressionError.DivisionByZero,
        ExpressionError.NegativeBaseFractionalExponent,
        ExpressionError.OutOfRange,
        ExpressionError.TooComplex,
    ) + ExpressionError.Arity.entries.map { arity -> ExpressionError.WrongArgumentCount("abs", arity) }

    @Test
    fun englishValueOfEachKeyGivesTheMessage() {
        val english = strings("values")
        samples.forEach { error ->
            val template = english[error.key] ?: error("values/strings_expression.xml has no ${error.key}")
            var text = template.replace("\\u0027", "'")
            error.arguments.forEachIndexed { index, argument -> text = text.replace("%${index + 1}\$s", argument) }
            assertEquals(error.message, text, error.key)
        }
        assertEquals(samples.map { error -> error.key }.toSet(), english.keys)
    }

    @Test
    fun eachLocaleHasTheSameKeys() {
        val keys = strings("values").keys
        listOf("values-es", "values-fr", "values-de", "values-pt-rBR", "values-nl").forEach { locale ->
            assertEquals(keys, strings(locale).keys, locale)
        }
    }
}
