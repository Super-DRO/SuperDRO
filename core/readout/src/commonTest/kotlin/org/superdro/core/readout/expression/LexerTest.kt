package org.superdro.core.readout.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The lexer table and the decimal comma rule. */
class LexerTest {

    private fun types(text: String): List<TokenType> = Lexer.scan(text).map { token -> token.type }

    private fun lexerError(text: String): ExpressionError =
        assertFailsWith<ExpressionException> { Lexer.scan(text) }.error

    @Test
    fun eachOperatorGivesItsToken() {
        assertEquals(
            listOf(
                TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH, TokenType.MODULO,
                TokenType.EXPONENT, TokenType.SQUARE_ROOT, TokenType.END,
            ),
            types("+ - * / % ^ √"),
        )
        assertEquals(
            listOf(
                TokenType.ASSIGN, TokenType.EQUAL_EQUAL, TokenType.NOT_EQUAL, TokenType.GREATER,
                TokenType.GREATER_EQUAL, TokenType.LESS, TokenType.LESS_EQUAL, TokenType.OR, TokenType.AND,
                TokenType.COMMA, TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN, TokenType.END,
            ),
            types("= == != > >= < <= || && , ( )"),
        )
    }

    @Test
    fun singleExclamationBarOrAmpersandIsAnError() {
        assertEquals(ExpressionError.InvalidToken("!"), lexerError("!"))
        assertEquals(ExpressionError.InvalidToken("|"), lexerError("|"))
        assertEquals(ExpressionError.InvalidToken("&"), lexerError("&"))
        assertEquals(ExpressionError.InvalidToken("!"), lexerError("1 ! 2"))
        assertEquals(ExpressionError.InvalidToken("|"), lexerError("1 | 2"))
        assertEquals(ExpressionError.InvalidToken("&"), lexerError("1 & 2"))
        assertError("Invalid token '!'", "!")
        assertError("Invalid token '|'", "|")
        assertError("Invalid token '&'", "&")
    }

    @Test
    fun spaceCarriageReturnAndTabAreIgnored() {
        assertEquals(listOf(TokenType.NUMBER, TokenType.PLUS, TokenType.NUMBER, TokenType.END), types(" 1\t+\r2 "))
        assertValue("3", " 1\t+\r2 ")
    }

    @Test
    fun otherCharactersAreInvalid() {
        assertEquals(ExpressionError.InvalidToken("\n"), lexerError("1\n"))
        assertEquals(ExpressionError.InvalidToken("#"), lexerError("#"))
        assertEquals(ExpressionError.InvalidToken("−"), lexerError("−5"))
        assertEquals(ExpressionError.InvalidToken("²"), lexerError("2²"))
    }

    @Test
    fun numberForms() {
        assertValue("0.5", ".5")
        assertValue("5", "5.")
        assertValue("1000", "1e3")
        assertValue("0.001", "1E-3")
        assertValue("250", "2.5e+2")
        assertValue("12", "012")
        assertValue("0", "0.000")
    }

    @Test
    fun anExponentNeedsADigit() {
        // `2e` is the number 2 and the identifier e. There is no implicit multiplication.
        assertEquals(listOf(TokenType.NUMBER, TokenType.IDENTIFIER, TokenType.END), types("2e"))
        assertError("Expected end of expression", "2e")
        assertEquals(
            listOf(TokenType.NUMBER, TokenType.IDENTIFIER, TokenType.PLUS, TokenType.IDENTIFIER, TokenType.END),
            types("2e+x1"),
        )
    }

    @Test
    fun numberHas16SignificantDigitsHalfEven() {
        assertValue("1.234567890123457", "1.23456789012345678")
        assertValue("1.000000000000002", "1.0000000000000015")
        assertValue("1", "1.0000000000000005")
    }

    @Test
    fun identifierCharacters() {
        val tokens = Lexer.scan("_a.b1 x2")
        assertEquals(listOf("_a.b1", "x2", ""), tokens.map { token -> token.text })
        assertEquals(listOf(TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.END), tokens.map { token -> token.type })
        assertValue("2", "x.1 = 2")
    }

    @Test
    fun commaBetweenTwoDigitsOutsideACallIsADecimalMark() {
        assertValue("1.5", "1,5")
        assertValue("3.5", "2,5+1")
        assertValue("1.5", "(1,5)")
        assertValue("7", "2*(3,5)")
        assertValue("150", "1,5e2")
        assertValue("0.25", "0,25")
    }

    @Test
    fun commaInsideACallSeparatesTheArguments() {
        assertValue("1", "min(3, 1, 2)")
        assertValue("1", "min(1,5)")
        assertValue("5", "max(1,5)")
        assertError("round requires either one or two arguments", "round(2,345, 2)")
    }

    @Test
    fun commaRuleWorksWithAFunctionCallInTheText() {
        assertValue("3.5", "1,5+max(1,2)")
        assertValue("4.5", "max(1,2)+2,5")
    }

    @Test
    fun groupInsideACallHasItsOwnParentheses() {
        assertValue("1.5", "max((1,5), 1)")
        assertValue("2.5", "abs(-(2,5))")
    }

    @Test
    fun commaThatIsNotBetweenTwoDigitsIsAComma() {
        assertEquals(listOf(TokenType.NUMBER, TokenType.COMMA, TokenType.NUMBER, TokenType.END), types("1, 5"))
        assertEquals(listOf(TokenType.NUMBER, TokenType.COMMA, TokenType.NUMBER, TokenType.END), types("1 ,5"))
        assertError("Expected end of expression", "1, 5")
        assertError("Expected end of expression", "abs(1),5")
    }
}
