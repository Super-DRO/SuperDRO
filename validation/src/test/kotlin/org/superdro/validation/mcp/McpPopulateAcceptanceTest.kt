package org.superdro.validation.mcp

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.GateTexts
import org.superdro.core.model.MachineType
import org.superdro.core.model.McpScope
import org.superdro.core.model.Workspace

/**
 * The acceptance tests of the populate tools of issue 59 through the full DI graph and the real
 * server ([McpAcceptanceGraph]): the upload route of the issue comment section 2, the preview
 * and the write of section 3, the confirmation on the tablet of section 4, the decisions 1
 * and 2 of the plan section 7, and the audit log of the proposal section 6.5.
 */
class McpPopulateAcceptanceTest {

    private fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Issue comment section 2: a file goes through `POST /upload`, not through a tool argument.
     * `set_drawing` with the `fileId` imports the DXF, and `get_workspace` then shows the
     * drawing summary.
     */
    @Test
    fun anUploadedDxfBecomesTheDrawingOfTheWorkspace() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn(McpScope.Populate)
            val mill = graph.profile("Mill")

            val upload = graph.upload(SmallDxf.BYTES, "application/dxf")
            assertEquals(200, upload.status, upload.text)
            val receipt = upload.json
            val fileId = receipt.text("fileId")
            assertEquals(SmallDxf.BYTES.size, receipt.text("bytes").toInt())
            assertEquals(hex(SmallDxf.BYTES), receipt.text("sha256Hex"))
            assertEquals("application/dxf", receipt.text("contentType"))

            val set = graph.callModern("set_drawing", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("fileId", fileId); put("fileName", "part.dxf") })
            assertEquals(200, set.status, set.text)
            assertFalse(set.isError, set.content)
            assertEquals("Drawing set: 2 shapes on 1 layer", set.content)
            assertEquals("AsciiDxf", set.structured.text("format"))
            assertEquals(listOf("0"), set.structured.array("layers").map { layer -> (layer as JsonPrimitive).content })
            val extents = set.structured.getValue("extents").jsonObject
            assertEquals(0.0, extents.text("minX").toDouble())
            assertEquals(10.0, extents.text("maxX").toDouble())
            assertEquals("mm", extents.text("units"))

            val workspace = graph.call("get_workspace", json { put("machine", mill.id); put("workspace", Workspace.DEFAULT_NAME) })
            assertEquals(200, workspace.status, workspace.text)
            assertFalse(workspace.isError, workspace.content)
            val drawing = workspace.structured.getValue("drawing").jsonObject
            assertEquals(2, drawing.text("shapeCount").toInt())
            assertEquals(listOf("0"), drawing.array("layers").map { layer -> (layer as JsonPrimitive).content })
            assertTrue("part.dxf" in drawing.text("source"), drawing.text("source"))
            assertTrue("a drawing with 2 shape(s)" in workspace.content, workspace.content)

            val missing = graph.call("set_drawing", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("fileId", "0123456789abcdef0123456789abcdef") })
            assertTrue(missing.isError)
            assertTrue("not there or expired" in missing.content, missing.content)
        }
    }

    /**
     * Issue comment section 3: each write tool has `preview`. The preview gives the plan and
     * writes nothing. The write gives the counts, and `list_tools` then shows the tools.
     */
    @Test
    fun putToolsPreviewWritesNothingAndTheWriteAddsTheTools() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn(McpScope.Populate)
            graph.profile("Mill")
            val rows = JsonArray(
                listOf(
                    json { put("name", "6 mm end mill"); put("diameter", 6.0); put("toothCount", 4) },
                    json { put("name", "10 mm end mill"); put("diameter", 10.0); put("toothCount", 2); put("zOffset", 1.5) },
                ),
            )

            val preview = graph.call("put_tools", json { put("machine", "Mill"); put("tools", rows); put("preview", true) })
            assertFalse(preview.isError, preview.content)
            assertEquals("Preview: 2 tools added, 0 tools updated, 0 rows skipped", preview.content)
            assertEquals(2, preview.structured.array("toImport").size)
            assertEquals(0, preview.structured.array("skipped").size)
            assertEquals(0, runBlocking { graph.tools.loadLibrary(graph.machines.profiles().first().single().id) }.size, "The preview writes nothing.")
            val empty = graph.call("list_tools", json { put("machine", "Mill") })
            assertEquals(0, empty.structured.array("tools").size)

            val write = graph.call("put_tools", json { put("machine", "Mill"); put("tools", rows) })
            assertFalse(write.isError, write.content)
            assertEquals("2 tools added, 0 tools updated, 0 rows skipped", write.content)
            assertEquals(emptyList(), graph.gateRequests, "A new tool on an offline machine needs no confirmation.")

            val list = graph.call("list_tools", json { put("machine", "Mill"); put("units", "mm") })
            // The library comes in the order of the names.
            val tools = list.structured.array("tools").map { tool -> tool.jsonObject }
            assertEquals(listOf("10 mm end mill", "6 mm end mill"), tools.map { tool -> tool.text("name") })
            assertEquals(listOf(10.0, 6.0), tools.map { tool -> tool.text("diameter").toDouble() })
            assertEquals(listOf(2, 4), tools.map { tool -> tool.text("toothCount").toInt() })
            assertEquals(1.5, tools[0].text("zOffset").toDouble())

            // An update by name with inch units: the stored value is in µm, the result is in the unit of the call.
            val update = graph.call("put_tools", json { put("machine", "Mill"); put("units", "inch"); put("tools", JsonArray(listOf(json { put("name", "6 mm end mill"); put("diameter", 0.25); put("toothCount", 3) }))) })
            assertFalse(update.isError, update.content)
            assertEquals("0 tools added, 1 tool updated, 0 rows skipped", update.content)
            val again = graph.call("list_tools", json { put("machine", "Mill"); put("units", "mm") })
            assertEquals(6.35, again.structured.array("tools").map { it.jsonObject }.single { it.text("name") == "6 mm end mill" }.text("diameter").toDouble(), 1e-6)
        }
    }

    /**
     * Issue comment section 4 and plan decision 1: `REPLACE_ALL` always goes through the gate.
     * A refused gate gives the error "The operator refused the call" and writes nothing. An
     * allowed gate writes. A plain `UPDATE_MATCHING` on an offline machine needs no gate.
     */
    @Test
    fun replaceAllGoesThroughTheGateAndARefusalWritesNothing() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn(McpScope.Populate)
            graph.profile("Mill")
            val points = JsonArray(
                listOf(
                    json { put("name", "Hole 1"); put("x", 10.0); put("y", 20.0) },
                    json { put("name", "Hole 2"); put("x", 30.0); put("y", 40.0); put("z", -1.0) },
                ),
            )
            val add = graph.call("put_sub_datums", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("points", points) })
            assertFalse(add.isError, add.content)
            assertEquals("2 points added, 0 points updated, 0 guides added, 0 rows skipped", add.content)
            assertEquals(emptyList(), graph.gateRequests, "UPDATE_MATCHING on an offline machine needs no gate.")

            fun storedNames(): List<String> = graph.call("get_workspace", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME) })
                .structured.array("subDatums").map { point -> point.jsonObject.text("name") }
            assertEquals(listOf("Hole 1", "Hole 2"), storedNames())

            val replacement = JsonArray(listOf(json { put("name", "Origin"); put("x", 0.0); put("y", 0.0) }))
            val refusing = graph.answerGate(GateDecision.Refused)
            val refused = graph.call("put_sub_datums", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("points", replacement); put("operation", "REPLACE_ALL") })
            refusing.cancel()
            assertEquals(200, refused.status, refused.text)
            assertTrue(refused.isError)
            assertEquals(GateTexts.REFUSED, refused.content)
            assertEquals("The operator refused the call", refused.content)
            assertEquals(1, graph.gateRequests.size)
            val request = graph.gateRequests.single()
            assertEquals("Put sub-datums", request.toolTitle)
            assertEquals("REPLACE_ALL on \"${Workspace.DEFAULT_NAME}\" of Mill", request.summary)
            assertTrue(request.details.any { line -> "Deletes all 2 points" in line }, request.details.toString())
            assertTrue(request.details.any { line -> line.startsWith("Client 127.0.0.1") }, request.details.toString())
            assertEquals(listOf("Hole 1", "Hole 2"), storedNames(), "A refusal writes nothing.")

            val allowing = graph.answerGate(GateDecision.Allowed)
            val allowed = graph.call("put_sub_datums", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("points", replacement); put("operation", "REPLACE_ALL") })
            allowing.cancel()
            assertFalse(allowed.isError, allowed.content)
            assertEquals("1 point added, 0 points updated, 0 guides added, 0 rows skipped", allowed.content)
            assertEquals(2, graph.gateRequests.size)
            assertEquals(listOf("Origin"), storedNames())

            // The preview of a REPLACE_ALL asks nobody.
            val preview = graph.call("put_sub_datums", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("points", points); put("operation", "REPLACE_ALL"); put("preview", true) })
            assertFalse(preview.isError, preview.content)
            assertEquals(2, graph.gateRequests.size)
            assertEquals(listOf("Origin"), storedNames())
        }
    }

    /**
     * Plan decision 2: `put_profile_settings` is refused while the machine is online, and the
     * error names the reason. The same call on an offline machine gives the preview and the
     * write (through the gate).
     */
    @Test
    fun putProfileSettingsIsRefusedWhileTheMachineIsOnline() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn(McpScope.Populate)
            graph.profile("Mill")
            graph.connectFake("lathe", "Lathe", MachineType.Lathe)

            val machines = graph.call("list_machines")
            val online = machines.structured.array("machines").map { machine -> machine.jsonObject }.associate { machine -> machine.text("name") to machine.text("online") }
            assertEquals(mapOf("Mill" to "false", "Lathe" to "true"), online)

            val settings = graph.call("get_profile_settings", json { put("machine", "Lathe") }).structured.getValue("settings").jsonObject
            val refused = graph.call("put_profile_settings", json { put("machine", "Lathe"); put("settings", settings) })
            assertTrue(refused.isError)
            assertTrue("is online" in refused.content, refused.content)
            assertTrue("Disconnect it" in refused.content, refused.content)
            assertEquals(emptyList(), graph.gateRequests, "The refusal comes before the gate.")

            val millSettings = graph.call("get_profile_settings", json { put("machine", "Mill") }).structured.getValue("settings").jsonObject
            val changed = json { millSettings.forEach { (key, value) -> put(key, value) }; put("metricDefault", false) }
            val preview = graph.call("put_profile_settings", json { put("machine", "Mill"); put("settings", changed); put("preview", true) })
            assertFalse(preview.isError, preview.content)
            assertEquals(1, preview.structured.text("changed").toInt())
            val allowing = graph.answerGate(GateDecision.Allowed)
            val write = graph.call("put_profile_settings", json { put("machine", "Mill"); put("settings", changed) })
            allowing.cancel()
            assertFalse(write.isError, write.content)
            assertEquals("Put profile settings", graph.gateRequests.single().toolTitle)
            assertEquals("false", graph.call("list_machines").structured.array("machines").map { it.jsonObject }.single { it.text("name") == "Mill" }.text("metricDefault"))
        }
    }

    /**
     * Proposal 6.5: the audit log. Each call has a row with the client, the era, the tool, the
     * method, the result class, and the duration. A read tool is logged with its name only. A
     * write tool is logged with its arguments. A request with no valid token is logged as
     * unauthorized with its method and path. The log has no token.
     */
    @Test
    fun theAuditLogHasARowForEachCall() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn(McpScope.Populate)
            graph.profile("Mill")
            val token = graph.token()
            graph.legacy("ping", token = null)
            graph.call("list_machines")
            graph.callModern("get_app_info")
            graph.upload(SmallDxf.BYTES, "application/dxf")
            graph.call("put_tools", json { put("machine", "Mill"); put("tools", JsonArray(listOf(json { put("name", "Drill"); put("diameter", 3.0) }))) })
            val refusing = graph.answerGate(GateDecision.Refused)
            graph.call("put_tools", json { put("machine", "Mill"); put("tools", JsonArray(listOf(json { put("name", "Drill"); put("diameter", 3.0) }))); put("operation", "REPLACE_ALL") })
            refusing.cancel()

            graph.waitUntil("The audit rows are there") { graph.auditRows().size >= 6 }
            val rows = graph.auditRows().reversed()
            assertEquals(listOf("POST /mcp", "tools/call", "tools/call", "POST /upload", "tools/call", "tools/call"), rows.map { row -> row.method })
            assertEquals(listOf(null, "list_machines", "get_app_info", null, "put_tools", "put_tools"), rows.map { row -> row.tool })
            assertEquals(listOf("unauthorized", "ok", "ok", "ok", "ok", "refused"), rows.map { row -> row.result })
            assertEquals(listOf("Legacy", "Legacy", "Modern", "Legacy", "Legacy", "Legacy"), rows.map { row -> row.era })
            assertTrue(rows.all { row -> row.client == "127.0.0.1" }, rows.map { row -> row.client }.toString())
            assertTrue(rows.all { row -> row.durationMs >= 0 })
            assertNull(rows[1].argumentsJson, "A read tool is logged with its name only.")
            assertNotNull(rows[4].argumentsJson, "A write tool is logged with its arguments.")
            assertTrue("\"Drill\"" in rows[4].argumentsJson!!, rows[4].argumentsJson)
            assertTrue("REPLACE_ALL" in rows[5].argumentsJson!!, rows[5].argumentsJson)
            val now = System.currentTimeMillis()
            assertTrue(rows.all { row -> now - row.atMs < 60_000 }, "The rows have the wall clock time.")
            assertTrue(rows.none { row -> token in (row.argumentsJson ?: "") || token in row.method }, "The log has no token.")

            // The settings screen shows the same rows.
            val presenter = graph.presenter()
            graph.waitUntil("The screen shows the log") { presenter.uiState.value.audit.size >= 6 }
            assertEquals(listOf("put_tools", "put_tools", "POST /upload"), presenter.uiState.value.audit.take(3).map { line -> line.name })
            assertEquals(listOf("refused", "ok", "ok"), presenter.uiState.value.audit.take(3).map { line -> line.result })
        }
    }

    /** Issue comment section 3: a tool answers in the `units` of the call, and the default is the unit of the profile. */
    @Test
    fun theUnitsOfTheCallApplyToTheArgumentsAndTheResults() {
        McpAcceptanceGraph().use { graph ->
            graph.turnOn(McpScope.Populate)
            graph.profile("Mill")
            val inch = graph.call("put_sub_datums", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("units", "inch"); put("points", JsonArray(listOf(json { put("name", "P"); put("x", 1.0); put("y", 0.5) }))) })
            assertFalse(inch.isError, inch.content)
            val mm = graph.call("get_workspace", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME) }).structured
            assertEquals("mm", mm.text("units"))
            val point = mm.array("subDatums").single().jsonObject
            assertEquals(25.4, point.text("x").toDouble(), 1e-6)
            assertEquals(12.7, point.text("y").toDouble(), 1e-6)
            assertEquals(JsonNull, point.getValue("z"))
            val um = graph.call("get_workspace", json { put("machine", "Mill"); put("workspace", Workspace.DEFAULT_NAME); put("units", "um") }).structured
            assertEquals(25400.0, um.array("subDatums").single().jsonObject.text("x").toDouble(), 1e-6)
        }
    }
}
