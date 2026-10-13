package org.superdro.core.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The texts of the store rules are exact, and the UI shows them
 * from `core:resources` in six languages (`values/strings_store.xml`).
 */
class StoreStringsTest {

    private val resources = File("../resources/src/commonMain/composeResources")

    private fun strings(locale: String): Map<String, String> {
        val text = File(resources, "$locale/strings_store.xml").readText()
        return Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""").findAll(text)
            .associate { match -> match.groupValues[1] to match.groupValues[2].replace("\\'", "'") }
    }

    @Test
    fun theRefusalTextsAreExact() {
        assertEquals("A profile with this name already exists.", StoreRefusal.ProfileNameExists.message)
        assertEquals("Workspace name can't be blank.", StoreRefusal.WorkspaceNameBlank.message)
        assertEquals("A workspace with this name already exists.", StoreRefusal.WorkspaceNameExists.message)
        assertEquals("The last workspace can't be deleted.", StoreRefusal.LastWorkspace.message)
    }

    @Test
    fun eachRefusalHasItsTextInTheStringFileOfEachLanguage() {
        val english = strings("values")
        StoreRefusal.entries.forEach { refusal -> assertEquals(refusal.message, english[refusal.stringKey], refusal.name) }
        assertEquals(DELETE_PROFILE_TOOLS_QUESTION, english[DELETE_PROFILE_TOOLS_QUESTION_KEY])
        for (locale in listOf("values-es", "values-fr", "values-de", "values-pt-rBR", "values-nl")) {
            val translated = strings(locale)
            assertEquals(english.keys, translated.keys, locale)
            translated.forEach { (key, value) -> assertTrue(value.isNotBlank() && value != english[key], "$locale $key") }
        }
    }
}
