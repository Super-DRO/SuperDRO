package org.superdro.core.store.files

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** The fixture files in `src/jvmTest/resources/files`. */
internal object Fixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/files/$name")) { "No fixture $name" }.use { stream -> stream.readBytes() }

    fun text(name: String): String = bytes(name).decodeToString()
}

/** The time of the exports in the tests: 2026-10-11 14:30:05 UTC. */
internal val FIXED_TIME: Instant = Instant.parse("2026-10-11T14:30:05Z")

/** The export context of the tests. */
internal val CONTEXT: ExportContext = ExportContext("1.0.0", FIXED_TIME, TimeZone.UTC)

/** Gives the IDs `id-1`, `id-2`, and so on. */
internal fun sequentialIds(): () -> String {
    var next = 0
    return { "id-${++next}" }
}

/**
 * A small stand-in for the expression evaluator (it is under construction in another
 * module): `+ - * /`, parentheses, and the decimal comma between two digits.
 */
internal object TestEvaluator {

    fun evaluate(text: String): Double? = try {
        val parser = Parser(Regex("""(\d),(\d)""").replace(text.trim(), "$1.$2"))
        val value = parser.expression()
        if (parser.done()) value else null
    } catch (_: IllegalStateException) {
        null
    }

    private class Parser(val text: String) {
        var index = 0

        fun done(): Boolean {
            skip()
            return index == text.length
        }

        private fun skip() {
            while (index < text.length && text[index] == ' ') index += 1
        }

        fun expression(): Double {
            var value = term()
            while (true) {
                skip()
                when (text.getOrNull(index)) {
                    '+' -> { index += 1; value += term() }
                    '-' -> { index += 1; value -= term() }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                skip()
                when (text.getOrNull(index)) {
                    '*' -> { index += 1; value *= factor() }
                    '/' -> { index += 1; value /= factor() }
                    else -> return value
                }
            }
        }

        private fun factor(): Double {
            skip()
            return when (val character = text.getOrNull(index)) {
                '-' -> { index += 1; -factor() }
                '+' -> { index += 1; factor() }
                '(' -> {
                    index += 1
                    val value = expression()
                    skip()
                    check(text.getOrNull(index) == ')')
                    index += 1
                    value
                }
                else -> {
                    check(character != null && (character.isDigit() || character == '.'))
                    val start = index
                    while (index < text.length && (text[index].isDigit() || text[index] == '.')) index += 1
                    text.substring(start, index).toDoubleOrNull() ?: error("not a number")
                }
            }
        }
    }
}
