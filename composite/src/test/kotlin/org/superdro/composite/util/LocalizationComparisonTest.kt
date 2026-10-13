package org.superdro.composite.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests of the rules of [LocalizationComparison]. */
class LocalizationComparisonTest {

    private val locales = setOf("values-de", "values-es")

    private fun file(vararg strings: Pair<String, String>) =
        StringResource.Parsed(linkedMapOf(*strings), emptyList())

    private fun compare(
        base: Map<String, StringResource.Parsed>,
        translations: Map<String, Map<String, StringResource.Parsed>>,
        allowList: Set<String> = emptySet(),
    ) = LocalizationComparison.compare("values", base, translations, locales, allowList)

    @Test
    fun `equal files in each locale give no error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("add" to "Add")),
            translations = mapOf(
                "values-de" to mapOf("strings_app.xml" to file("add" to "Hinzufügen")),
                "values-es" to mapOf("strings_app.xml" to file("add" to "Añadir")),
            ),
        )
        assertEquals(emptyList(), result.errors)
        assertEquals(emptyMap(), result.warnings)
    }

    @Test
    fun `a missing locale directory is an error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("add" to "Add")),
            translations = mapOf("values-de" to mapOf("strings_app.xml" to file("add" to "Hinzufügen"))),
        )
        assertEquals(listOf("values-es: the directory has no string file."), result.errors)
    }

    @Test
    fun `a missing file is an error`() {
        val result = compare(
            base = mapOf(
                "strings_app.xml" to file("add" to "Add"),
                "strings_readout.xml" to file("zero" to "Zero"),
            ),
            translations = mapOf(
                "values-de" to mapOf(
                    "strings_app.xml" to file("add" to "Hinzufügen"),
                    "strings_readout.xml" to file("zero" to "Null"),
                ),
                "values-es" to mapOf("strings_app.xml" to file("add" to "Añadir")),
            ),
        )
        assertEquals(
            listOf("values-es: strings_readout.xml is absent. Each file of values must have a translation."),
            result.errors,
        )
    }

    @Test
    fun `a file with no base file is an error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("add" to "Add")),
            translations = mapOf(
                "values-de" to mapOf(
                    "strings_app.xml" to file("add" to "Hinzufügen"),
                    "strings_extra.xml" to file("x" to "X"),
                ),
                "values-es" to mapOf("strings_app.xml" to file("add" to "Añadir")),
            ),
        )
        assertEquals(listOf("values-de: strings_extra.xml has no file of the same name in values."), result.errors)
    }

    @Test
    fun `a missing key is an error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("add" to "Add", "settings" to "Settings")),
            translations = mapOf(
                "values-de" to mapOf("strings_app.xml" to file("add" to "Hinzufügen", "settings" to "Einstellungen")),
                "values-es" to mapOf("strings_app.xml" to file("add" to "Añadir")),
            ),
        )
        assertEquals(listOf("values-es/strings_app.xml: settings is absent."), result.errors)
    }

    @Test
    fun `an unknown key is an error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("add" to "Add")),
            translations = mapOf(
                "values-de" to mapOf("strings_app.xml" to file("add" to "Hinzufügen", "old" to "Alt")),
                "values-es" to mapOf("strings_app.xml" to file("add" to "Añadir")),
            ),
        )
        assertEquals(listOf("values-de/strings_app.xml: old is not in the base file."), result.errors)
    }

    @Test
    fun `a name in two base files is an error`() {
        val result = compare(
            base = mapOf(
                "strings_app.xml" to file("add" to "Add"),
                "strings_readout.xml" to file("add" to "Add"),
            ),
            translations = mapOf(
                "values-de" to mapOf(
                    "strings_app.xml" to file("add" to "Hinzufügen"),
                    "strings_readout.xml" to file("add" to "Hinzufügen"),
                ),
                "values-es" to mapOf(
                    "strings_app.xml" to file("add" to "Añadir"),
                    "strings_readout.xml" to file("add" to "Añadir"),
                ),
            ),
        )
        assertTrue(result.errors.contains("values: add is in strings_app.xml and in strings_readout.xml."))
    }

    @Test
    fun `a wrong format specifier is an error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("retry" to "Reconnect %1\$d")),
            translations = mapOf(
                "values-de" to mapOf("strings_app.xml" to file("retry" to "Neu verbinden %1\$s")),
                "values-es" to mapOf("strings_app.xml" to file("retry" to "Reconectar %1\$d")),
            ),
        )
        assertEquals(
            listOf("values-de/strings_app.xml: retry uses %1\$s where the base locale uses %1\$d."),
            result.errors,
        )
    }

    @Test
    fun `an English value is only a warning and the allow list removes it`() {
        val base = mapOf("strings_app.xml" to file("app_name" to "SuperDRO", "add" to "Add"))
        val translations = mapOf(
            "values-de" to mapOf("strings_app.xml" to file("app_name" to "SuperDRO", "add" to "Add")),
            "values-es" to mapOf("strings_app.xml" to file("app_name" to "SuperDRO", "add" to "Añadir")),
        )
        val result = compare(base, translations, allowList = setOf("app_name"))
        assertEquals(emptyList(), result.errors)
        assertEquals(mapOf("values-de" to listOf("strings_app.xml: add is still English")), result.warnings)
    }

    @Test
    fun `an allow list entry with no base name is an error`() {
        val result = compare(
            base = mapOf("strings_app.xml" to file("add" to "Add")),
            translations = mapOf(
                "values-de" to mapOf("strings_app.xml" to file("add" to "Hinzufügen")),
                "values-es" to mapOf("strings_app.xml" to file("add" to "Añadir")),
            ),
            allowList = setOf("values-de:gone"),
        )
        assertEquals(listOf("untranslatedAllowList names values-de:gone, which the base locale does not have."), result.errors)
    }

    @Test
    fun `a plural with no other item is an error`() {
        val base = StringResource.Parsed(
            emptyMap(), emptyList(),
            plurals = mapOf("machines" to mapOf("one" to "%1\$d machine", "other" to "%1\$d machines")),
        )
        val de = StringResource.Parsed(
            emptyMap(), emptyList(),
            plurals = mapOf("machines" to mapOf("one" to "%1\$d Maschine")),
        )
        val es = StringResource.Parsed(
            emptyMap(), emptyList(),
            plurals = mapOf("machines" to mapOf("one" to "%1\$d máquina", "many" to "%1\$d de máquinas", "other" to "%1\$d máquinas")),
        )
        val result = compare(
            base = mapOf("strings_app.xml" to base),
            translations = mapOf("values-de" to mapOf("strings_app.xml" to de), "values-es" to mapOf("strings_app.xml" to es)),
        )
        assertEquals(listOf("values-de/strings_app.xml: machines has no item with the quantity `other`."), result.errors)
    }

    @Test
    fun `a backslash quote in a Compose file is an error and a plain apostrophe is not`() {
        val plain = LocalizationComparison.compare(
            "values",
            mapOf("strings_a.xml" to file("a" to "Can't set RPM")),
            mapOf("values-de" to mapOf("strings_a.xml" to file("a" to "Nicht möglich")), "values-es" to mapOf("strings_a.xml" to file("a" to "No se puede"))),
            locales,
            emptySet(),
        )
        assertTrue(plain.errors.isEmpty(), plain.errors.toString())
        val escaped = LocalizationComparison.compare(
            "values",
            mapOf("strings_a.xml" to file("a" to "Can\\'t set RPM")),
            mapOf("values-de" to mapOf("strings_a.xml" to file("a" to "Nicht möglich")), "values-es" to mapOf("strings_a.xml" to file("a" to "No se puede"))),
            locales,
            emptySet(),
        )
        assertTrue(escaped.errors.any { error -> "backslash" in error }, escaped.errors.toString())
    }

    @Test
    fun `an Android resource file still needs the escape`() {
        val result = LocalizationComparison.compare(
            "values",
            mapOf("strings_app.xml" to file("a" to "Can't")),
            mapOf("values-de" to mapOf("strings_app.xml" to file("a" to "Nein")), "values-es" to mapOf("strings_app.xml" to file("a" to "No"))),
            locales,
            emptySet(),
            androidResourceFiles = setOf("strings_app.xml"),
        )
        assertTrue(result.errors.any { error -> "apostrophe with no escape" in error }, result.errors.toString())
    }
}
