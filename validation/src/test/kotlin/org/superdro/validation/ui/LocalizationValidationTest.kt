package org.superdro.validation.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.validation.Traceability

/**
 * All strings are in `core:resources` in English (the
 * base), Spanish, French, German, Portuguese (pt-BR), and Dutch. Each translation has the keys of
 * the base.
 */
class LocalizationValidationTest {

    private val resources: File = Traceability.rootDir.resolve("core/resources/src/commonMain/composeResources")

    private val languages = listOf("values-es", "values-fr", "values-de", "values-pt-rBR", "values-nl")

    private val key = Regex("""<(string|plurals)\s+name="([^"]+)"""")

    private fun keys(file: File): Set<String> = key.findAll(file.readText()).map { match -> match.groupValues[2] }.toSet()

    @Test
    fun each_string_file_has_the_same_keys_in_the_six_languages() {
        val base = resources.resolve("values").listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }.orEmpty().sortedBy { it.name }
        assertTrue(base.size > 20, "the string files of the areas: ${base.map { it.name }}")
        val problems = base.flatMap { file ->
            val expected = keys(file)
            languages.mapNotNull { language ->
                val translated = resources.resolve(language).resolve(file.name)
                when {
                    !translated.isFile -> "$language/${file.name}: no file"
                    keys(translated) != expected -> "$language/${file.name}: missing ${expected - keys(translated)}, extra ${keys(translated) - expected}"
                    else -> null
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun the_ui_texts_of_master_are_in_the_english_base() {
        val all = resources.resolve("values").listFiles().orEmpty().filter { it.extension == "xml" }.joinToString("\n") { it.readText() }
        // The fixed UI texts of the readout, the hints, the tabs, and the keypad.
        listOf(
            "No machine is online", "Add machine", "Disconnect all", "Undo", "Calibration required", "Calibrate",
            "Press and hold to set the absolute zero.", "Press and hold to clear the offsets.",
            "Press and hold to cancel the hold.", "Press and hold the '+' button for more shapes.",
            "Show hints", "Show the setup reminder", "Bluetooth off", "Unplugged", "Waiting for the adapter",
            "No adapter connected", "online", "offline", "paused", "Measurement",
            "%1\$s axis, %2\$s %3\$s",
        ).forEach { text -> assertTrue(">$text<" in all, "the base has \"$text\"") }
    }
}
