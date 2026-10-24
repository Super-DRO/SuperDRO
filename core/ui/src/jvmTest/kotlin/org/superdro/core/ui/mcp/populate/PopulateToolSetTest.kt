package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.McpScope

class PopulateToolSetTest {

    private val writeTools = listOf(
        "create_workspace", "update_workspace", "delete_workspace", "put_sub_datums", "delete_sub_datums", "put_guides",
        "set_drawing", "clear_drawing", "set_reference_image", "clear_reference_image",
        "put_tools", "delete_tools", "set_tool_outline", "clear_tool_outline",
        "put_profile_settings", "create_profile", "import_profile_bundle",
    )
    private val exportTools = listOf("export_tools", "export_profile_bundle")
    private val interactionTools = setOf(
        "delete_workspace", "put_sub_datums", "delete_sub_datums", "put_guides", "clear_drawing", "clear_reference_image",
        "put_tools", "delete_tools", "clear_tool_outline", "put_profile_settings", "create_profile", "import_profile_bundle",
    )
    private val notIdempotent = setOf("create_workspace", "create_profile")

    @Test
    fun theSetHasTheToolsOfTheIssueWithThePopulateScope() = runTest {
        val fixture = PopulateFixture(this)
        val specs = fixture.toolSet.tools.map { tool -> tool.spec }
        assertEquals((writeTools + exportTools).sorted(), specs.map { spec -> spec.name }.sorted())
        assertEquals(specs.size, specs.map { spec -> spec.name }.toSet().size)
        assertTrue(specs.all { spec -> spec.scope == McpScope.Populate })
        assertTrue(fixture.toolSet.resources.isEmpty())
        fixture.close()
    }

    @Test
    fun theAnnotationsFollowTheTables() = runTest {
        val fixture = PopulateFixture(this)
        fixture.toolSet.tools.map { tool -> tool.spec }.forEach { spec ->
            val export = spec.name in exportTools
            assertEquals(export, spec.annotations.readOnlyHint, spec.name)
            assertEquals(!export, spec.annotations.destructiveHint, spec.name)
            assertEquals(spec.name !in notIdempotent, spec.annotations.idempotentHint, spec.name)
            assertEquals(spec.name in interactionTools, spec.requiresUserInteraction, spec.name)
            assertEquals(false, spec.annotations.openWorldHint, spec.name)
            assertTrue(spec.description.isNotBlank() && spec.title.isNotBlank(), spec.name)
            val hasPreview = spec.inputSchema["properties"]!!.jsonObjectKeys().contains("preview")
            assertEquals(spec.name in writeTools, hasPreview, "preview on ${spec.name}")
        }
        fixture.close()
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectKeys(): Set<String> = (this as kotlinx.serialization.json.JsonObject).keys
}
