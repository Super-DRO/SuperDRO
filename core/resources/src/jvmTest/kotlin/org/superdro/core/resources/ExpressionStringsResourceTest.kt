package org.superdro.core.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString

/** The English error texts of the expression evaluator. */
class ExpressionStringsResourceTest {

    @Test
    fun englishErrorTextsWithTheirArguments() = runTest {
        assertEquals("Invalid token '#'", getString(TestEnvironment, Res.string.expression_error_invalid_token, "#"))
        assertEquals("Undefined function 'foo'", getString(TestEnvironment, Res.string.expression_error_undefined_function, "foo"))
        assertEquals(
            "Expected ')' after function arguments",
            getString(TestEnvironment, Res.string.expression_error_expected_paren_after_arguments),
        )
        assertEquals("Expected end of expression", getString(TestEnvironment, Res.string.expression_error_expected_end))
        assertEquals("floor requires one argument", getString(TestEnvironment, Res.string.expression_error_requires_one, "floor"))
        assertEquals("if requires three arguments", getString(TestEnvironment, Res.string.expression_error_requires_three, "if"))
    }
}
