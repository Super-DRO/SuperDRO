package org.superdro.composite.util

import org.superdro.composite.util.StringResource.Specifier
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests of the rules that `LocalizationCheckTask` applies to one string file. */
class StringResourceTest {

    private val temporaryFiles = mutableListOf<File>()

    @AfterTest
    fun deleteTemporaryFiles() {
        temporaryFiles.forEach { file -> file.delete() }
    }

    private fun stringsFile(body: String): File {
        val file = File.createTempFile("strings", ".xml")
        temporaryFiles += file
        file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body\n</resources>")
        return file
    }

    @Test
    fun `a value with no format specifier has an empty list`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("Add machine"))
    }

    @Test
    fun `positional specifiers keep their index and conversion`() {
        assertEquals(
            listOf(Specifier(1, 'd'), Specifier(2, 'd')),
            StringResource.formatSpecifiers("%1\$d/%2\$d")
        )
    }

    @Test
    fun `a specifier with no argument position has a null index`() {
        assertEquals(listOf(Specifier(null, 's')), StringResource.formatSpecifiers("Version %s"))
    }

    @Test
    fun `a literal percent sign takes no argument`() {
        assertEquals(listOf(Specifier(1, 'd')), StringResource.formatSpecifiers("%1\$d%% done"))
    }

    @Test
    fun `a space before a percent sign is not a specifier`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("100 % de la batterie"))
        assertEquals(emptyList(), StringResource.formatSpecifiers("Helligkeit 50 % maximal"))
    }

    @Test
    fun `a letter that is not a conversion character is not a specifier`() {
        assertEquals(emptyList(), StringResource.formatSpecifiers("100%zip"))
    }

    @Test
    fun `a changed conversion character is a different specifier`() {
        assertNotEquals(
            StringResource.formatSpecifiers("Version %1\$s"),
            StringResource.formatSpecifiers("Version %1\$d")
        )
    }

    @Test
    fun `a width and a precision do not change the specifier`() {
        assertEquals(listOf(Specifier(1, 'f')), StringResource.formatSpecifiers("%1\$.2f ms"))
    }

    @Test
    fun `a bare apostrophe is a finding`() {
        assertTrue(StringResource.hasUnescapedApostrophe("Can't connect"))
    }

    @Test
    fun `a backslash escapes the apostrophe`() {
        assertFalse(StringResource.hasUnescapedApostrophe("Can\\'t connect"))
    }

    @Test
    fun `a value in double quotes keeps its apostrophes`() {
        assertFalse(StringResource.hasUnescapedApostrophe("\"Can't connect\""))
    }

    @Test
    fun `a value that only starts and ends with a quote is not enclosed`() {
        assertTrue(StringResource.hasUnescapedApostrophe("\"a\" and 'b'"))
    }

    @Test
    fun `a typographic apostrophe is not a finding`() {
        assertFalse(StringResource.hasUnescapedApostrophe("Impossible de lire l’adaptateur"))
    }

    @Test
    fun `an apos entity becomes a bare apostrophe and is a finding`() {
        val file = stringsFile("""    <string name="a">Can&apos;t connect</string>""")
        val value = StringResource.read(file).strings.getValue("a")
        assertEquals("Can't connect", value)
        assertTrue(StringResource.hasUnescapedApostrophe(value))
    }

    @Test
    fun `a value with words is translatable`() {
        assertTrue(StringResource.isTranslatable("Add machine"))
        assertTrue(StringResource.isTranslatable("Reconnecting %1\$d"))
    }

    @Test
    fun `a value of only specifiers and punctuation is not translatable`() {
        assertFalse(StringResource.isTranslatable("%1\$d/%2\$d"))
        assertFalse(StringResource.isTranslatable("+"))
    }

    @Test
    fun `read gives each string in file order and ignores a comment`() {
        val file = stringsFile(
            """
            |    <string name="app_name">SuperDRO</string>
            |    <!-- A comment is not a string. -->
            |    <string name="add_machine">Add machine</string>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(mapOf("app_name" to "SuperDRO", "add_machine" to "Add machine"), parsed.strings)
        assertEquals(emptyList(), parsed.duplicates)
    }

    @Test
    fun `read gives the plurals and the string arrays`() {
        val file = stringsFile(
            """
            |    <plurals name="machines">
            |        <item quantity="one">%1${'$'}d machine</item>
            |        <item quantity="other">%1${'$'}d machines</item>
            |    </plurals>
            |    <string-array name="units">
            |        <item>mm</item>
            |        <item>in</item>
            |    </string-array>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(mapOf("one" to "%1\$d machine", "other" to "%1\$d machines"), parsed.plurals["machines"])
        assertEquals(listOf("mm", "in"), parsed.arrays["units"])
        assertEquals(setOf("machines", "units"), parsed.names)
    }

    @Test
    fun `a repeated name is reported`() {
        val file = stringsFile(
            """
            |    <string name="a">First</string>
            |    <string name="a">Second</string>
            """.trimMargin()
        )
        val parsed = StringResource.read(file)
        assertEquals(listOf("a"), parsed.duplicates)
        assertEquals("Second", parsed.strings.getValue("a"))
    }
}
