package org.superdro.validation.readout

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.validation.Traceability

/**
 * The exact texts of the tachometer, the expressions, the settings, the calibration, and the display states, as
 * the UI shows them: the English string files of `core:resources`. Compose changes an escape
 * `\uXXXX` to its character, so the test does the same.
 */
class ReadoutTextResourceValidationTest {

    private val values: File = Traceability.rootDir.resolve("core/resources/src/commonMain/composeResources/values")

    private val stringLine = Regex("""<string name="([^"]+)">(.*)</string>""")

    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")

    private fun strings(file: String): Map<String, String> =
        values.resolve(file).readLines().mapNotNull { line -> stringLine.find(line) }.associate { match ->
            match.groupValues[1] to unicodeEscape.replace(match.groupValues[2]) { escape -> escape.groupValues[1].toInt(16).toChar().toString() }
        }

    private val readout: Map<String, String> by lazy { strings("strings_readout_core.xml") }

    private val expression: Map<String, String> by lazy { strings("strings_expression.xml") }

    @Test
    fun settings_rule_texts() {
        assertEquals("Primary and secondary inputs can't use the same prefix", readout["readout_core_settings_same_prefix"])
        assertEquals("At least one output axis must be enabled", readout["readout_core_settings_one_axis_required"])
        assertEquals(
            "Number of edge finder samples must be a number between 1 and 10 (inclusive)",
            readout["readout_core_settings_sample_count_range"],
        )
    }

    @Test
    fun calibration_texts() {
        mapOf(
            "readout_core_calibration_secondary_disabled" to "Secondary Input Disabled",
            "readout_core_calibration_prefix_missing" to "Input Prefix Missing",
            "readout_core_calibration_block_required" to "Block Size Required",
            "readout_core_calibration_block_too_small" to "Block Too Small",
            "readout_core_calibration_block_too_small_detail" to "at least 50.000 mm / 2.0000 in",
            "readout_core_calibration_no_reading" to "No Reading Received",
            "readout_core_calibration_sample_too_small" to "Reading Sample Too Small",
            "readout_core_calibration_jitter" to "Excessive Jitter Detected",
            "readout_core_calibration_too_close" to "Readings Too Close",
        ).forEach { (key, text) -> assertEquals(text, readout[key], key) }
    }

    @Test
    fun edge_name_texts() {
        listOf("left", "right", "front", "back", "bottom", "top", "first", "second").forEach { name ->
            assertEquals(name, readout["readout_core_edge_$name"], name)
        }
    }

    @Test
    fun tachometer_and_display_state_texts() {
        assertEquals("Can't set RPM", readout["readout_core_tach_cant_set"])
        assertEquals("Error", readout["readout_core_state_error"])
        assertEquals("No input", readout["readout_core_state_no_input"])
    }

    @Test
    fun expression_error_texts() {
        fun text(key: String, argument: String? = null): String? =
            expression[key]?.let { template -> if (argument == null) template else template.replace("%1\$s", argument) }
        assertEquals("abs requires one argument", text("expression_error_requires_one", "abs"))
        assertEquals("floor requires one argument", text("expression_error_requires_one", "floor"))
        assertEquals("ceil requires one argument", text("expression_error_requires_one", "ceil"))
        assertEquals("sum requires at least one argument", text("expression_error_requires_at_least_one", "sum"))
        assertEquals("min requires at least one argument", text("expression_error_requires_at_least_one", "min"))
        assertEquals("max requires at least one argument", text("expression_error_requires_at_least_one", "max"))
        assertEquals("round requires either one or two arguments", text("expression_error_requires_one_or_two", "round"))
        assertEquals("if requires three arguments", text("expression_error_requires_three", "if"))
        assertEquals("Undefined function 'x'", text("expression_error_undefined_function", "x"))
        assertEquals("Undefined variable 'x'", text("expression_error_undefined_variable", "x"))
        assertEquals("Invalid assignment target", text("expression_error_invalid_assignment_target"))
        assertEquals("Expected ')' after function arguments", text("expression_error_expected_paren_after_arguments"))
        assertEquals("Expected end of expression", text("expression_error_expected_end"))
        assertEquals("Invalid token 'c'", text("expression_error_invalid_token", "c"))
    }
}
