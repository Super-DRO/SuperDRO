package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.superdro.core.mcp.McpClient
import org.superdro.core.mcp.McpEra
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.ToolCall
import org.superdro.core.mcp.ToolResult
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineType
import org.superdro.core.model.McpScope
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.valueOrNull

/** The read tools and the read resources on a database in memory. */
class ReadToolSetTest {

    private val client = McpClient("192.168.1.30", McpEra.Modern)

    private suspend fun McpTool.call(arguments: JsonObject = JsonObject(emptyMap())): ToolResult =
        handler.call(ToolCall(spec.name, arguments, client) { _, _, _ -> })

    private fun McpHarness.tool(name: String): McpTool = toolSets.single().tools.first { tool -> tool.spec.name == name }

    private fun ok(result: ToolResult): JsonObject = assertIs<ToolResult.Ok>(result).structured!!.jsonObject

    @Test
    fun theToolsAreReadOnlyWithTheScopeReadAndTheNamesOfThePlan() = runTest {
        val harness = McpHarness(this)
        val tools = harness.toolSets.single().tools
        assertEquals(listOf("list_machines", "list_workspaces", "get_workspace", "list_tools", "get_profile_settings", "get_app_info"), tools.map { tool -> tool.spec.name })
        tools.forEach { tool ->
            assertEquals(McpScope.Read, tool.spec.scope, tool.spec.name)
            assertTrue(tool.spec.annotations.readOnlyHint, tool.spec.name)
            assertTrue(!tool.spec.annotations.destructiveHint, tool.spec.name)
        }
        assertEquals(
            listOf(ReadResourceUris.MACHINES, ReadResourceUris.PROFILE_SETTINGS, ReadResourceUris.WORKSPACES, ReadResourceUris.WORKSPACE, ReadResourceUris.TOOLS),
            harness.toolSets.single().resources.map { (spec, _) -> spec.uri },
        )
        harness.close()
    }

    @Test
    fun listMachinesGivesTheProfilesWithTheOnlineStateAndTheSelectedTab() = runTest {
        val harness = McpHarness(this)
        val mill = harness.profile(name = "Mill")
        val lathe = harness.profile(MachineType.Lathe, "Lathe") { profile -> profile.copy(metricDefault = true) }
        harness.online = setOf(mill.id)
        harness.selected = lathe.id
        val result = ok(harness.tool("list_machines").call())
        val machines = result["machines"]!!.jsonArray.map { element -> element.jsonObject }
        assertEquals(listOf("Lathe", "Mill"), machines.map { machine -> machine["name"]!!.jsonPrimitive.content })
        val millJson = machines.first { machine -> machine["id"]!!.jsonPrimitive.content == mill.id }
        assertEquals("VERTICAL_MILL", millJson["type"]!!.jsonPrimitive.content)
        assertTrue(millJson["online"]!!.jsonPrimitive.boolean)
        assertTrue(!millJson["selected"]!!.jsonPrimitive.boolean)
        val latheJson = machines.first { machine -> machine["id"]!!.jsonPrimitive.content == lathe.id }
        assertTrue(latheJson["selected"]!!.jsonPrimitive.boolean)
        assertTrue(latheJson["metricDefault"]!!.jsonPrimitive.boolean)
        harness.close()
    }

    @Test
    fun getWorkspaceConvertsTheLengthsToTheUnitsOfTheCallAndTheAnglesToDegrees() = runTest {
        val harness = McpHarness(this)
        val profile = harness.profile(name = "Mill")
        val workspace = harness.store.machines.loadFullProfile(profile.id)!!.workspaces.single()
        harness.store.machines.setAxisState(workspace.id, AxisId.X, AxisState(absoluteOffset = 25_400.0))
        harness.store.machines.setAxisState(workspace.id, AxisId.Angular, AxisState(absoluteOffset = 7_200.0))
        harness.store.machines.addSubDatum(workspace.id, SubDatum(name = "Hole 1", x = 12_700.0, y = -2_540.0, a = 3_600.0)).valueOrNull() ?: error("no point")
        val tool = harness.tool("get_workspace")

        val inch = ok(tool.call(buildJsonObject { put("machine", "mill"); put("workspace", "default workspace") }))
        assertEquals("inch", inch["units"]!!.jsonPrimitive.content)
        assertEquals(workspace.id, inch["id"]!!.jsonPrimitive.content)
        assertTrue(inch["active"]!!.jsonPrimitive.boolean)
        val axes = inch["axisStates"]!!.jsonArray.map { element -> element.jsonObject }
        assertEquals(1.0, axes.first { axis -> axis["axis"]!!.jsonPrimitive.content == "X" }["absoluteOffset"]!!.jsonPrimitive.double)
        assertEquals(2.0, axes.first { axis -> axis["axis"]!!.jsonPrimitive.content == "Angular" }["absoluteOffset"]!!.jsonPrimitive.double)
        val point = inch["subDatums"]!!.jsonArray.single().jsonObject
        assertEquals(0.5, point["x"]!!.jsonPrimitive.double)
        assertEquals(-0.1, point["y"]!!.jsonPrimitive.double)
        assertEquals(1.0, point["a"]!!.jsonPrimitive.double)
        assertEquals(JsonNull, point["z"])

        val mm = ok(tool.call(buildJsonObject { put("machine", profile.id); put("workspace", workspace.id); put("units", "mm") }))
        assertEquals(12.7, mm["subDatums"]!!.jsonArray.single().jsonObject["x"]!!.jsonPrimitive.double)
        val um = ok(tool.call(buildJsonObject { put("machine", profile.id); put("workspace", workspace.id); put("units", "um") }))
        assertEquals(12_700.0, um["subDatums"]!!.jsonArray.single().jsonObject["x"]!!.jsonPrimitive.double)

        val bad = tool.call(buildJsonObject { put("machine", profile.id); put("workspace", workspace.id); put("units", "cm") })
        assertEquals("The units \"cm\" are not mm, inch, or um", assertIs<ToolResult.Error>(bad).message)
        val noWorkspace = tool.call(buildJsonObject { put("machine", profile.id); put("workspace", "Other") })
        assertIs<ToolResult.Error>(noWorkspace)
        val noMachine = tool.call(buildJsonObject { put("machine", "Nope"); put("workspace", workspace.id) })
        assertEquals("No machine with the ID or the name \"Nope\"", assertIs<ToolResult.Error>(noMachine).message)
        harness.close()
    }

    @Test
    fun listWorkspacesAndListToolsGiveTheCountsAndTheLibrary() = runTest {
        val harness = McpHarness(this)
        val profile = harness.profile(name = "Mill")
        harness.store.machines.createWorkspace(profile.id, "Second").valueOrNull() ?: error("no workspace")
        harness.store.tools.addTool(profile.id, Tool(kind = ToolKind.Mill, name = "End mill 6", diameterUm = 6_000.0, toothCount = 4, metric = true)).valueOrNull() ?: error("no tool")

        val workspaces = ok(harness.tool("list_workspaces").call(buildJsonObject { put("machine", "Mill") }))["workspaces"]!!.jsonArray.map { element -> element.jsonObject }
        assertEquals(2, workspaces.size)
        assertEquals(1, workspaces.count { workspace -> workspace["active"]!!.jsonPrimitive.boolean })
        assertTrue(workspaces.all { workspace -> !workspace["hasDrawing"]!!.jsonPrimitive.boolean })

        val tools = ok(harness.tool("list_tools").call(buildJsonObject { put("machine", "Mill"); put("units", "mm") }))
        assertTrue(!tools["shared"]!!.jsonPrimitive.boolean)
        val tool = tools["tools"]!!.jsonArray.single().jsonObject
        assertEquals("End mill 6", tool["name"]!!.jsonPrimitive.content)
        assertEquals(6.0, tool["diameter"]!!.jsonPrimitive.double)
        assertEquals(4, tool["toothCount"]!!.jsonPrimitive.content.toInt())
        assertTrue(!tool["hasOutline"]!!.jsonPrimitive.boolean)
        harness.close()
    }

    @Test
    fun getProfileSettingsGivesTheSettingsOfTheProfileFileAndGetAppInfoTheScopes() = runTest {
        val harness = McpHarness(this)
        val profile = harness.profile(name = "Mill")
        val settings = ok(harness.tool("get_profile_settings").call(buildJsonObject { put("machine", profile.id) }))["settings"]!!.jsonObject
        assertEquals("VerticalMill", settings["machineType"]!!.jsonPrimitive.content)
        assertNull(settings["id"])
        assertNull(settings["workspaces"])
        assertNull(settings["tools"])

        harness.configure { config -> config.copy(scopes = setOf(McpScope.Read, McpScope.Populate)) }
        val info = ok(harness.tool("get_app_info").call())
        assertEquals("26.43.1", info["version"]!!.jsonPrimitive.content)
        assertEquals(listOf("Read", "Populate"), info["scopes"]!!.jsonArray.map { element -> element.jsonPrimitive.content })
        assertEquals("Risky", info["confirmation"]!!.jsonPrimitive.content)
        harness.close()
    }

    @Test
    fun theResourcesGiveTheSameJsonAsTheToolsAndNullForAnUnknownMachine() = runTest {
        val harness = McpHarness(this)
        val profile = harness.profile(name = "Mill")
        val resources = harness.toolSets.single().resources.toMap()
        val machines = assertNotNull(resources.entries.first { (spec, _) -> spec.uri == ReadResourceUris.MACHINES }.value.read(ReadResourceUris.MACHINES))
        assertEquals("application/json", machines.mimeType)
        assertTrue(profile.id in machines.text)
        val workspaceReader = resources.entries.first { (spec, _) -> spec.uri == ReadResourceUris.WORKSPACE }.value
        val byName = assertNotNull(workspaceReader.read("superdro://machines/Mill/workspaces/Default Workspace"))
        assertTrue(harness.store.machines.loadFullProfile(profile.id)!!.workspaces.single().id in byName.text)
        assertNull(workspaceReader.read("superdro://machines/Nope/workspaces/Default Workspace"))
        assertNull(workspaceReader.read("superdro://machines/Mill/tools"), "A reader answers only its own template.")
        val tools = resources.entries.first { (spec, _) -> spec.uri == ReadResourceUris.TOOLS }.value
        assertNotNull(tools.read("superdro://machines/${profile.id}/tools"))
        harness.close()
    }
}
