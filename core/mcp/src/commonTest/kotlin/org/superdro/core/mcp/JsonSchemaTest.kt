package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The small JSON Schema builder. */
class JsonSchemaTest {

    @Test
    fun anObjectSchemaNamesTheTypeThePropertiesTheRequiredListAndNoExtraProperties() {
        val schema = objectSchema {
            string("name", required = true, description = "The name", maxLength = 64)
            number("x", minimum = -1.0, maximum = 1.0)
            integer("count", minimum = 0L)
            boolean("preview", default = false)
            enumString("units", listOf("mm", "inch"))
            array("tags", items = stringSchema(), maxItems = 10)
            obj("limits") { integer("maxEntities", required = true) }
        }
        assertEquals(
            """{"type":"object","properties":{""" +
                """"name":{"type":"string","description":"The name","maxLength":64},""" +
                """"x":{"type":"number","minimum":-1.0,"maximum":1.0},""" +
                """"count":{"type":"integer","minimum":0},""" +
                """"preview":{"type":"boolean","default":false},""" +
                """"units":{"type":"string","enum":["mm","inch"]},""" +
                """"tags":{"type":"array","items":{"type":"string"},"maxItems":10},""" +
                """"limits":{"type":"object","properties":{"maxEntities":{"type":"integer"}},"required":["maxEntities"],"additionalProperties":false}},""" +
                """"required":["name"],"additionalProperties":false}""",
            schema.toString(),
        )
    }

    @Test
    fun anEmptySchemaAcceptsOnlyAnEmptyObject() {
        assertEquals("""{"type":"object","properties":{},"additionalProperties":false}""", objectSchema().toString())
        assertEquals("""{"type":"object","properties":{}}""", objectSchema(additionalProperties = true).toString())
    }

    @Test
    fun aPropertyTwoTimesIsAnError() {
        assertFailsWith<IllegalArgumentException> { objectSchema { string("a"); number("a") } }
    }
}
