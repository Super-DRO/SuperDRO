package org.superdro.core.readout.expression

/** The token types of the lexer table. */
internal enum class TokenType {
    PLUS,
    MINUS,
    STAR,
    SLASH,
    MODULO,
    EXPONENT,
    SQUARE_ROOT,
    ASSIGN,
    EQUAL_EQUAL,
    NOT_EQUAL,
    GREATER,
    GREATER_EQUAL,
    LESS,
    LESS_EQUAL,
    OR,
    AND,
    COMMA,
    LEFT_PAREN,
    RIGHT_PAREN,
    NUMBER,
    IDENTIFIER,

    /** The end of the text. The lexer adds it after the last token. */
    END,
}

/**
 * A token. [text] is the part of the input text. [number] is the value of a [TokenType.NUMBER],
 * with 16 significant digits.
 */
internal class Token(val type: TokenType, val text: String, val number: Decimal? = null)

/**
 * The lexer of the evaluator.
 *
 * - Space, `\r`, and `\t` are ignored.
 * - A number has digits and `.`, then an optional exponent `e` or `E` with an optional sign. A
 *   number can start with `.` when a digit follows. The value has 16 significant digits, HALF_EVEN.
 * - An identifier starts with `[A-Za-z_]`, and the next characters are `[A-Za-z0-9_.]`.
 * - A single `!`, `|`, or `&`, and each other character, give "Invalid token 'c'".
 *
 * Decimal comma: a `(` directly after an identifier opens the parentheses of a function
 * call. In those parentheses, `,` separates the arguments. In other places, a `,` between two
 * digits is a decimal mark. A group `( ... )` in a call has its own parentheses: a `,` between
 * two digits in the group is a decimal mark.
 */
internal object Lexer {

    fun scan(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        // For each open parenthesis: true for the parentheses of a function call.
        val parentheses = ArrayDeque<Boolean>()
        var index = 0

        fun add(type: TokenType, length: Int) {
            tokens += Token(type, text.substring(index, index + length))
            index += length
        }

        fun next(offset: Int = 1): Char? = text.getOrNull(index + offset)

        while (index < text.length) {
            val char = text[index]
            when {
                char == ' ' || char == '\r' || char == '\t' -> index++
                char == '+' -> add(TokenType.PLUS, 1)
                char == '-' -> add(TokenType.MINUS, 1)
                char == '*' -> add(TokenType.STAR, 1)
                char == '/' -> add(TokenType.SLASH, 1)
                char == '%' -> add(TokenType.MODULO, 1)
                char == '^' -> add(TokenType.EXPONENT, 1)
                char == SQUARE_ROOT -> add(TokenType.SQUARE_ROOT, 1)
                char == '=' -> if (next() == '=') add(TokenType.EQUAL_EQUAL, 2) else add(TokenType.ASSIGN, 1)
                char == '!' -> if (next() == '=') add(TokenType.NOT_EQUAL, 2) else invalid(char)
                char == '>' -> if (next() == '=') add(TokenType.GREATER_EQUAL, 2) else add(TokenType.GREATER, 1)
                char == '<' -> if (next() == '=') add(TokenType.LESS_EQUAL, 2) else add(TokenType.LESS, 1)
                char == '|' -> if (next() == '|') add(TokenType.OR, 2) else invalid(char)
                char == '&' -> if (next() == '&') add(TokenType.AND, 2) else invalid(char)
                char == ',' -> add(TokenType.COMMA, 1)
                char == '(' -> {
                    parentheses.addLast(tokens.lastOrNull()?.type == TokenType.IDENTIFIER)
                    add(TokenType.LEFT_PAREN, 1)
                }
                char == ')' -> {
                    parentheses.removeLastOrNull()
                    add(TokenType.RIGHT_PAREN, 1)
                }
                char.isAsciiDigit() || (char == '.' && next().isAsciiDigit()) -> {
                    val decimalComma = parentheses.lastOrNull() != true
                    val end = numberEnd(text, index, decimalComma)
                    tokens += number(text.substring(index, end))
                    index = end
                }
                char.isIdentifierStart() -> {
                    var end = index + 1
                    while (end < text.length && text[end].isIdentifierPart()) end++
                    add(TokenType.IDENTIFIER, end - index)
                }
                else -> invalid(char)
            }
        }
        tokens += Token(TokenType.END, "")
        return tokens
    }

    /** The square root sign U+221A. */
    const val SQUARE_ROOT: Char = '√'

    /** The number of significant digits of a number token (`MathContext.DECIMAL64`). */
    private const val DIGITS = 16

    /**
     * Gives the end index of the number that starts at [start]. With [decimalComma], a `,` between
     * two digits is part of the number.
     */
    private fun numberEnd(text: String, start: Int, decimalComma: Boolean): Int {
        var index = start
        while (index < text.length) {
            val char = text[index]
            val isDecimalComma = decimalComma && char == ',' &&
                text.getOrNull(index - 1).isAsciiDigit() && text.getOrNull(index + 1).isAsciiDigit()
            if (char.isAsciiDigit() || char == '.' || isDecimalComma) index++ else break
        }
        // An exponent is part of the number only when a digit follows the `e` or the sign. Thus
        // `2e` is the number 2 and the identifier `e`.
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            val sign = text.getOrNull(index + 1)
            val digitAt = if (sign == '+' || sign == '-') index + 2 else index + 1
            if (text.getOrNull(digitAt).isAsciiDigit()) {
                index = digitAt
                while (index < text.length && text[index].isAsciiDigit()) index++
            }
        }
        return index
    }

    private fun number(raw: String): Token {
        val value = Decimal.parse(raw.replace(',', '.'))
            ?: throw ExpressionException(ExpressionError.InvalidNumber(raw))
        return Token(TokenType.NUMBER, raw, Range.checked(value.round(DIGITS, RoundingMode.HALF_EVEN)))
    }

    private fun invalid(char: Char): Nothing = throw ExpressionException(ExpressionError.InvalidToken(char.toString()))

    private fun Char?.isAsciiDigit(): Boolean = this != null && this in '0'..'9'

    private fun Char.isIdentifierStart(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this == '_'

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isAsciiDigit() || this == '.'
}
