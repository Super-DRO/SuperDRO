package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.GateTexts
import org.superdro.core.model.ToolKind

class ToolLibraryToolsTest {

    private fun millTool(name: String, diameter: Double, teeth: Int = 2, id: String? = null) = buildJsonObject {
        if (id != null) put("id", id)
        put("name", name)
        put("diameter", diameter)
        put("toothCount", teeth)
    }

    @Test
    fun putToolsAddsAndUpdatesMillTools() = runTest {
        val fixture = PopulateFixture(this).start()
        val first = fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("End mill 10", 10.0, 4), millTool("Drill 6.8", 6.8))) })
        assertEquals("2 tools added, 0 tools updated, 0 rows skipped", first.text)
        val library = fixture.tools.loadLibrary(fixture.mill.id).associateBy { tool -> tool.name }
        assertEquals(10_000.0, library.getValue("End mill 10").diameterUm)
        assertEquals(4, library.getValue("End mill 10").toothCount)
        assertEquals(true, library.getValue("End mill 10").metric)
        assertEquals(ToolKind.Mill, library.getValue("Drill 6.8").kind)
        val second = fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("End mill 10", 9.95, 4), millTool("Face mill", 50.0, 5))) })
        assertEquals("1 tool added, 1 tool updated, 0 rows skipped", second.text)
        val updated = fixture.tools.loadLibrary(fixture.mill.id).associateBy { tool -> tool.name }
        assertEquals(3, updated.size)
        assertEquals(9_950.0, updated.getValue("End mill 10").diameterUm, 1e-6)
        assertEquals(library.getValue("End mill 10").id, updated.getValue("End mill 10").id)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun putToolsReadsInchRowsAndLatheTools() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok(
            "put_tools",
            args {
                put("machine", "Mill")
                put("units", "inch")
                put("tools", jsonList(buildJsonObject { put("name", "Half inch"); put("diameter", 0.5) }, buildJsonObject { put("name", "Metric row"); put("metric", true); put("diameter", 1.0) }))
            },
        )
        val mill = fixture.tools.loadLibrary(fixture.mill.id).associateBy { tool -> tool.name }
        assertEquals(12_700.0, mill.getValue("Half inch").diameterUm, 1e-6)
        assertEquals(false, mill.getValue("Half inch").metric)
        assertEquals(25_400.0, mill.getValue("Metric row").diameterUm, 1e-6)
        assertEquals(true, mill.getValue("Metric row").metric)
        val lathe = fixture.ok(
            "put_tools",
            args { put("machine", "Lathe"); put("tools", jsonList(buildJsonObject { put("name", "Boring bar"); put("internal", true); put("xOffset", 1.5); put("zOffset", -2.0) })) },
        )
        assertEquals("1 tool added, 0 tools updated, 0 rows skipped", lathe.text)
        val bar = fixture.tools.loadLibrary(fixture.lathe.id).single()
        assertEquals(ToolKind.Lathe, bar.kind)
        assertEquals(true, bar.internal)
        assertEquals(1_500.0, bar.xOffsetUm)
        assertEquals(-2_000.0, bar.zOffsetUm)
        assertEquals("Boring bar", lathe.obj.list("toImport").single().str("name"))
        assertEquals(1.5, lathe.obj.list("toImport").single().num("xOffset"))
        fixture.close()
    }

    @Test
    fun putToolsPreviewAndValidation() = runTest {
        val fixture = PopulateFixture(this).start()
        val preview = fixture.ok("put_tools", args { put("machine", "Mill"); put("preview", true); put("tools", jsonList(millTool("Good", 3.0), buildJsonObject { put("name", "No diameter") })) })
        assertEquals("Preview: 1 tool added, 0 tools updated, 1 row skipped", preview.text)
        assertEquals("Row 2: The value of “diameter” is blank.", preview.obj.list("skipped").single().list("problems").single().str("message"))
        assertEquals(emptyList(), fixture.tools.loadLibrary(fixture.mill.id))
        val bad = fixture.error("put_tools", args { put("machine", "Mill"); put("tools", jsonList(buildJsonObject { put("name", "Zero"); put("diameter", 0.0) })) })
        assertEquals("No row of the file can be imported.", bad.message)
        val longName = fixture.error("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("x".repeat(51), 1.0))) })
        assertEquals("No row of the file can be imported.", longName.message)
        fixture.close()
    }

    @Test
    fun putToolsReplaceAllAsksTheGateAndDeletesTheLibrary() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("Old", 1.0))) })
        val arguments = args { put("machine", "Mill"); put("operation", "REPLACE_ALL"); put("tools", jsonList(millTool("New", 2.0))) }
        fixture.gate.decision = GateDecision.Refused
        assertEquals(GateTexts.REFUSED, fixture.error("put_tools", arguments).message)
        assertEquals(listOf("Old"), fixture.tools.loadLibrary(fixture.mill.id).map { tool -> tool.name })
        fixture.gate.decision = GateDecision.Allowed
        val result = fixture.ok("put_tools", arguments)
        assertEquals("1 tool added, 0 tools updated, 0 rows skipped, 1 tool deleted", result.text)
        assertEquals(listOf("New"), fixture.tools.loadLibrary(fixture.mill.id).map { tool -> tool.name })
        assertEquals("REPLACE_ALL tools on Mill", fixture.gate.requests.last().summary)
        fixture.close()
    }

    @Test
    fun putToolsOnTheActiveToolOfAnOnlineMachineIsRisky() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("Active", 1.0), millTool("Other", 2.0))) })
        val active = fixture.tools.loadLibrary(fixture.mill.id).first { tool -> tool.name == "Active" }
        fixture.machines.updateWorkspace(fixture.millWorkspace().id) { workspace -> workspace.copy(activeToolId = active.id) }
        fixture.online += fixture.mill.id
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("Other", 3.0))) })
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("Active", 1.1))) })
        assertEquals(1, fixture.gate.requests.size)
        fixture.close()
    }

    @Test
    fun deleteToolsDeletesByIdAndAsksTheGate() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("A", 1.0), millTool("B", 2.0))) })
        val ids = fixture.tools.loadLibrary(fixture.mill.id).associate { tool -> tool.name to tool.id }
        val preview = fixture.ok("delete_tools", args { put("machine", "Mill"); put("ids", jsonList(ids.getValue("A"), "nope")); put("preview", true) })
        assertEquals("Preview: 1 tool deleted, 1 ID not found", preview.text)
        assertEquals(2, fixture.tools.loadLibrary(fixture.mill.id).size)
        val result = fixture.ok("delete_tools", args { put("machine", "Mill"); put("ids", jsonList(ids.getValue("A"), "nope")) })
        assertEquals("1 tool deleted, 1 ID not found", result.text)
        assertEquals(listOf("B"), fixture.tools.loadLibrary(fixture.mill.id).map { tool -> tool.name })
        assertEquals("Delete 1 tool from Mill", fixture.gate.requests.single().summary)
        assertEquals("No tool of the library has one of the IDs.", fixture.error("delete_tools", args { put("machine", "Mill"); put("ids", jsonList("nope")) }).message)
        fixture.close()
    }

    @Test
    fun setToolOutlineAttachesTheGeometryWithTheReport() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Lathe"); put("tools", jsonList(buildJsonObject { put("name", "Insert") })) })
        val preview = fixture.ok("set_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert"); put("document", TRIANGLE); put("preview", true) })
        assertEquals("Preview: 1 shape, 0 checks", preview.text)
        assertEquals(10.0, preview.obj["report"]!!.num("width"))
        assertEquals(1, preview.obj["report"]!!.int("closedOutlines"))
        assertNull(fixture.tools.loadLibrary(fixture.lathe.id).single().geometry)
        val result = fixture.ok("set_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert"); put("document", TRIANGLE); put("drawnIn", "inch") })
        assertEquals("Outline set on \"Insert\": 1 shape, 0 checks", result.text)
        val geometry = assertNotNull(fixture.tools.loadLibrary(fixture.lathe.id).single().geometry)
        assertEquals(25.4, geometry.drawnIn)
        assertEquals(1, geometry.shapes.size)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun setToolOutlineRefusesAMillToolAndABadDrawing() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("End mill", 10.0))) })
        val mill = fixture.error("set_tool_outline", args { put("machine", "Mill"); put("tool", "End mill"); put("document", TRIANGLE) })
        assertEquals("The tool \"End mill\" is a mill tool. Only a lathe tool has an outline.", mill.message)
        fixture.ok("put_tools", args { put("machine", "Lathe"); put("tools", jsonList(buildJsonObject { put("name", "Insert") })) })
        val pdf = fixture.upload("application/pdf", "%PDF".encodeToByteArray())
        val bad = fixture.error("set_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert"); put("fileId", pdf) })
        assertEquals("This file isn't a drawing SuperDRO can open.", bad.message)
        fixture.close()
    }

    @Test
    fun clearToolOutlineAsksTheGateAndRemovesTheGeometry() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Lathe"); put("tools", jsonList(buildJsonObject { put("name", "Insert") })) })
        val none = fixture.ok("clear_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert") })
        assertEquals("The tool \"Insert\" has no outline", none.text)
        fixture.ok("set_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert"); put("document", TRIANGLE) })
        fixture.gate.decision = GateDecision.Timeout
        assertEquals(GateTexts.TIMEOUT, fixture.error("clear_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert") }).message)
        assertNotNull(fixture.tools.loadLibrary(fixture.lathe.id).single().geometry)
        fixture.gate.decision = GateDecision.Allowed
        val result = fixture.ok("clear_tool_outline", args { put("machine", "Lathe"); put("tool", "Insert") })
        assertEquals("Outline removed from \"Insert\"", result.text)
        assertNull(fixture.tools.loadLibrary(fixture.lathe.id).single().geometry)
        fixture.close()
    }

    @Test
    fun exportToolsGivesADownload() = runTest {
        val fixture = PopulateFixture(this).start()
        fixture.ok("put_tools", args { put("machine", "Mill"); put("tools", jsonList(millTool("End mill 10", 10.0, 4))) })
        val result = fixture.ok("export_tools", args { put("machine", "Mill") })
        val fileId = result.obj.str("fileId")
        assertEquals("1 tool exported to /download/$fileId", result.text)
        assertEquals("/download/$fileId", result.obj.str("path"))
        val file = assertNotNull(fixture.files.download(fileId))
        assertEquals("text/csv", file.contentType)
        val text = file.bytes.decodeToString()
        assertTrue(text.contains("End mill 10"), text)
        assertTrue(text.contains("id,name,description,metric,diameter,tooth_count,z_offset"), text)
        assertTrue(fixture.files.downloadNames.getValue(fileId).startsWith("superdro-tools-Mill-mill-"))
        fixture.close()
    }
}
