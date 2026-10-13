package org.superdro.core.readout

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each [ReadoutText] has its string resource in the six languages of `core:resources`, and the
 * English resource is equal to [ReadoutText.english].
 */
class ReadoutTextResourceTest {

    private val resources = File("../resources/src/commonMain/composeResources")

    private val locales = listOf("values", "values-es", "values-fr", "values-de", "values-pt-rBR", "values-nl")

    @Test
    fun eachTextHasAResourceInEachLanguage() {
        locales.forEach { locale ->
            val strings = read(File(resources, "$locale/strings_readout_core.xml"))
            assertEquals(ReadoutText.entries.map { text -> text.key }.toSet(), strings.keys, locale)
        }
    }

    @Test
    fun theEnglishResourcesAreTheTextsOfReadoutText() {
        val strings = read(File(resources, "values/strings_readout_core.xml"))
        ReadoutText.entries.forEach { text -> assertEquals(text.english, strings[text.key], text.key) }
    }

    private fun read(file: File): Map<String, String> {
        assertTrue(file.isFile, "${file.path} is not a file")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
        }
    }

    /** The escapes that Compose resources read: `\uXXXX`, `\n`, `\t`, and `\\`. */
    private fun unescape(value: String): String =
        Regex("""\\u([0-9a-fA-F]{4})|\\n|\\t|\\\\""").replace(value) { match ->
            when {
                match.value == "\\n" -> "\n"
                match.value == "\\t" -> "\t"
                match.value == "\\\\" -> "\\"
                else -> match.groupValues[1].toInt(16).toChar().toString()
            }
        }
}
