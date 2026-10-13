package org.superdro.core.ui.settings.help

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.ui.tabs.EnglishStrings

/**
 * The in-app help texts: SuperDRO writes a short text for each main
 * screen. The texts have no external link and no name of a different app.
 */
class HelpTextsTest {

    private val locales = listOf("values", "values-es", "values-fr", "values-de", "values-pt-rBR", "values-nl")
    private val pattern = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun helpStrings(locale: String): Map<String, String> =
        File("../resources/src/commonMain/composeResources/$locale/strings_settings_help.xml").readText()
            .let { text -> pattern.findAll(text).associate { match -> match.groupValues[1] to match.groupValues[2] } }

    @Test
    fun eachTopicHasATitleAndAShortTextOfTwoToFiveSentences() {
        HelpTopic.entries.forEach { topic ->
            val title = EnglishStrings.all.getValue(topic.title.key)
            val text = EnglishStrings.all.getValue(topic.text.key)
            assertTrue(title.isNotBlank(), topic.name)
            assertTrue(text != title, "${topic.name} has a text, not only the title")
            val sentences = text.split(Regex("""[.!?](\s|$)""")).count { part -> part.isNotBlank() }
            assertTrue(sentences in 2..5, "${topic.name}: $sentences sentences")
        }
    }

    @Test
    fun noHelpTextHasALinkOrTheNameOfADifferentApp() {
        locales.forEach { locale ->
            val strings = helpStrings(locale)
            assertEquals(HelpTopic.entries.size * 2, strings.size, locale)
            strings.forEach { (name, value) ->
                val lower = value.lowercase()
                assertTrue("http" !in lower && "www" !in lower && "touchdro" !in lower, "$locale/$name: $value")
            }
        }
    }

    @Test
    fun eachTranslationDiffersFromTheEnglishText() {
        val english = helpStrings("values")
        locales.drop(1).forEach { locale ->
            helpStrings(locale).filterKeys { it.endsWith("_text") }.forEach { (name, value) ->
                assertTrue(value != english[name], "$locale/$name is still English")
            }
        }
    }
}
