package org.superdro.core.functions

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.compensation.CompensationTexts
import org.superdro.core.functions.patterns.PatternTexts
import org.superdro.core.functions.probe.EdgeFinderTexts
import org.superdro.core.functions.zreference.ZReferenceTexts
import org.w3c.dom.Element

/**
 * Each English template of the machine functions is the English value of its string in
 * `core:resources`. The UI uses the key, and the tests use the template. Thus the two must agree.
 */
class StringFilesTest {

    private val values = File("../resources/src/commonMain/composeResources/values")

    private fun read(fileName: String): Map<String, String> {
        val file = File(values, fileName)
        assertTrue(file.isFile, "No file ${file.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val element = strings.item(index) as Element
            element.getAttribute("name") to decode(element.textContent)
        }
    }

    /** Decodes the escapes `\uXXXX`, `\n`, and `\t`, as Compose Multiplatform does. */
    private fun decode(value: String): String = Regex("""\\u([0-9a-fA-F]{4})|\\n|\\t""").replace(value) { match ->
        when (match.value) {
            "\\n" -> "\n"
            "\\t" -> "\t"
            else -> match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun assertSame(fileName: String, templates: List<MessageTemplate>) {
        val strings = read(fileName)
        assertEquals(templates.size, templates.map { template -> template.key }.toSet().size, "$fileName: a key repeats")
        assertEquals(templates.map { template -> template.key }.toSet(), strings.keys, fileName)
        templates.forEach { template -> assertEquals(template.template, strings[template.key], template.key) }
    }

    @Test
    fun thePatternTextsAgreeWithTheStringFile() = assertSame("strings_patterns.xml", PatternTexts.all)

    @Test
    fun theCompensationTextsAgreeWithTheStringFile() = assertSame("strings_compensation.xml", CompensationTexts.all)

    @Test
    fun theEdgeFinderTextsAgreeWithTheStringFile() = assertSame("strings_edge_finder.xml", EdgeFinderTexts.all)

    @Test
    fun theZReferenceTextsAgreeWithTheStringFile() = assertSame("strings_z_reference.xml", ZReferenceTexts.all)
}
