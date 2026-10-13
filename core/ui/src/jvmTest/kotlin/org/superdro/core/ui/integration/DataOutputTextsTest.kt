package org.superdro.core.ui.integration

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The texts of the data output that name the controls of SuperDRO. */
class DataOutputTextsTest {

    private fun text(locale: String, key: String): String {
        val file = File("../resources/src/commonMain/composeResources/$locale/strings_data_output.xml").readText()
        return Regex("""<string name="$key">([^<]*)</string>""").find(file)?.groupValues?.get(1) ?: error("No $key in $locale")
    }

    @Test
    fun onDemandNamesTheStatusChipInTheSixLanguages() {
        val expected = mapOf(
            "values" to "On demand (chip in the status strip)",
            "values-es" to "A petición (chip de la barra de estado)",
            "values-fr" to "À la demande (puce de la bande d'état)",
            "values-de" to "Bei Bedarf (Chip in der Statusleiste)",
            "values-pt-rBR" to "Sob demanda (chip da faixa de status)",
            "values-nl" to "Op verzoek (chip in de statusbalk)",
        )
        expected.forEach { (locale, value) -> assertEquals(value, text(locale, "data_output_activation_on_demand"), locale) }
        assertFalse(text("values", "data_output_activation_on_demand").contains("toolbar", ignoreCase = true))
        // The other texts of the data output screen.
        assertEquals("Always on", text("values", "data_output_activation_always"))
        assertEquals("Encrypted (PSK)", text("values", "data_output_security_psk"))
        assertEquals("Plain text (TouchDRO compatible)", text("values", "data_output_security_plain"))
        assertEquals("Use only on trusted networks", text("values", "data_output_warning_trusted"))
        assertEquals("All interfaces", text("values", "data_output_interface_all"))
    }
}
