package org.superdro.core.readout.expression

/** The syntax tree of an expression. */
internal sealed interface Expr {

    /** A number token. */
    class Literal(val value: Decimal) : Expr

    /** `( expression )`. A group is not a variable, so `(x) = 1` is an invalid assignment. */
    class Grouping(val inner: Expr) : Expr

    /** A name that is not a call. [name] is the name as the text has it. */
    class Variable(val name: String) : Expr

    /** `name = value`. */
    class Assign(val name: String, val value: Expr) : Expr

    /** `-operand` or `√operand`. */
    class Unary(val operator: TokenType, val operand: Expr) : Expr

    /** An arithmetic operator, a comparison, `==`, or `!=`. */
    class Binary(val left: Expr, val operator: TokenType, val right: Expr) : Expr

    /** `||` or `&&`. */
    class Logical(val left: Expr, val operator: TokenType, val right: Expr) : Expr

    /** `name(arguments)`. [name] is the name as the text has it. */
    class Call(val name: String, val arguments: List<Expr>) : Expr
}

/**
 * The recursive descent parser of the expression grammar, from the lowest to the highest priority:
 *
 * ```text
 * expression := assignment
 * assignment := logic_or [ "=" assignment ]          (the target must be a variable)
 * logic_or   := logic_and { "||" logic_and }
 * logic_and  := equality { "&&" equality }
 * equality   := comparison { ("==" | "!=") comparison }
 * comparison := term { (">" | ">=" | "<" | "<=") term }
 * term       := factor { ("+" | "-") factor }
 * factor     := unary { ("*" | "/" | "%") unary }
 * unary      := "-" unary | "√" unary | power
 * power      := primary [ "^" unary ]                 (right associative)
 * primary    := NUMBER | IDENTIFIER | IDENTIFIER "(" [ expression { "," expression } ] ")" | "(" expression ")"
 * ```
 *
 * The whole text must be one expression. There is no unary plus and no implicit multiplication.
 */
internal class Parser(private val tokens: List<Token>) {

    private var position = 0

    /** The number of nested calls of [assignment] and [unary] now. */
    private var depth = 0

    /** Gives the tree of the whole text. */
    fun parse(): Expr {
        if (tokens.size > MAX_TOKENS) fail(ExpressionError.TooComplex)
        val expression = expression()
        if (peek().type != TokenType.END) fail(ExpressionError.ExpectedEndOfExpression)
        return expression
    }

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr = nested {
        val target = logicOr()
        if (match(TokenType.ASSIGN)) {
            val value = assignment()
            if (target !is Expr.Variable) fail(ExpressionError.InvalidAssignmentTarget)
            Expr.Assign(target.name, value)
        } else {
            target
        }
    }

    private fun logicOr(): Expr {
        var expression = logicAnd()
        while (match(TokenType.OR)) {
            expression = Expr.Logical(expression, TokenType.OR, logicAnd())
        }
        return expression
    }

    private fun logicAnd(): Expr {
        var expression = equality()
        while (match(TokenType.AND)) {
            expression = Expr.Logical(expression, TokenType.AND, equality())
        }
        return expression
    }

    private fun equality(): Expr =
        binaryLevel(setOf(TokenType.EQUAL_EQUAL, TokenType.NOT_EQUAL), ::comparison)

    private fun comparison(): Expr = binaryLevel(
        setOf(TokenType.GREATER, TokenType.GREATER_EQUAL, TokenType.LESS, TokenType.LESS_EQUAL),
        ::term,
    )

    private fun term(): Expr = binaryLevel(setOf(TokenType.PLUS, TokenType.MINUS), ::factor)

    private fun factor(): Expr =
        binaryLevel(setOf(TokenType.STAR, TokenType.SLASH, TokenType.MODULO), ::unary)

    /** One level of left-associative binary operators. */
    private fun binaryLevel(operators: Set<TokenType>, operand: () -> Expr): Expr {
        var expression = operand()
        while (peek().type in operators) {
            val operator = advance().type
            expression = Expr.Binary(expression, operator, operand())
        }
        return expression
    }

    private fun unary(): Expr = nested {
        when {
            match(TokenType.MINUS) -> Expr.Unary(TokenType.MINUS, unary())
            match(TokenType.SQUARE_ROOT) -> Expr.Unary(TokenType.SQUARE_ROOT, unary())
            else -> power()
        }
    }

    private fun power(): Expr {
        val base = primary()
        // The exponent is a unary: thus `2^-2` is permitted, and `2^3^2` is `2^(3^2)`.
        return if (match(TokenType.EXPONENT)) Expr.Binary(base, TokenType.EXPONENT, unary()) else base
    }

    private fun primary(): Expr {
        val token = peek()
        return when (token.type) {
            TokenType.NUMBER -> {
                advance()
                Expr.Literal(requireNotNull(token.number))
            }
            TokenType.IDENTIFIER -> {
                advance()
                if (match(TokenType.LEFT_PAREN)) call(token.text) else Expr.Variable(token.text)
            }
            TokenType.LEFT_PAREN -> {
                advance()
                val inner = expression()
                if (!match(TokenType.RIGHT_PAREN)) fail(ExpressionError.ExpectedParenAfterExpression)
                Expr.Grouping(inner)
            }
            else -> fail(ExpressionError.ExpectedExpression(tokens.getOrNull(position - 1)?.text))
        }
    }

    private fun call(name: String): Expr {
        val arguments = mutableListOf<Expr>()
        if (peek().type != TokenType.RIGHT_PAREN) {
            do {
                arguments += expression()
            } while (match(TokenType.COMMA))
        }
        if (!match(TokenType.RIGHT_PAREN)) fail(ExpressionError.ExpectedParenAfterArguments)
        return Expr.Call(name, arguments)
    }

    private fun peek(): Token = tokens[position]

    private fun advance(): Token {
        val token = tokens[position]
        if (token.type != TokenType.END) position++
        return token
    }

    private fun match(type: TokenType): Boolean {
        if (peek().type != type) return false
        advance()
        return true
    }

    /** Runs [block] one level deeper. Too many levels give [ExpressionError.TooComplex]. */
    private inline fun nested(block: () -> Expr): Expr {
        if (++depth > MAX_DEPTH) fail(ExpressionError.TooComplex)
        try {
            return block()
        } finally {
            depth--
        }
    }

    private fun fail(error: ExpressionError): Nothing = throw ExpressionException(error)

    companion object {
        /** The maximum of nested levels. Each pair of parentheses uses two levels. */
        const val MAX_DEPTH = 200

        /** The maximum of tokens in one text, the end token included. */
        const val MAX_TOKENS = 1000
    }
}
