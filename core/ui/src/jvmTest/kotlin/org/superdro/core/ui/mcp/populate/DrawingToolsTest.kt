package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.GateTexts
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.ShapeType

class DrawingToolsTest {

    @Test
    fun setDrawingFromAnInlineDocumentReplacesTheDrawing() = runTest {
        val fixture = PopulateFixture(this).start()
        val workspace = fixture.millWorkspace()
        val result = fixture.ok("set_drawing", args { put("machine", "Mill"); put("workspace", workspace.id); put("document", LINE_AND_CIRCLE) })
        assertEquals("Drawing set: 2 shapes on 1 layer", result.text)
        assertEquals(listOf("Outline"), result.obj.list("layers").map { it.toString().trim('"') })
        assertEquals(2, result.obj.int("shapeCount"))
        assertEquals(0.0, result.obj["extents"]!!.num("minX"))
        assertEquals(10.0, result.obj["extents"]!!.num("maxX"))
        assertEquals("mm", result.obj["extents"]!!.str("units"))
        val drawing = assertNotNull(fixture.drawings.loadDrawing(workspace.id))
        assertEquals(listOf(ShapeType.Line, ShapeType.Circle), drawing.shapes.map { shape -> shape.type })
        assertEquals(DrawingFormat.Wireframe, drawing.source?.format)
        assertEquals("inline.wireframe.json", drawing.source?.fileName)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.close()
    }

    @Test
    fun setDrawingFromAnUploadUsesTheFileName() = runTest {
        val fixture = PopulateFixture(this).start()
        val fileId = fixture.upload("application/json", LINE_AND_CIRCLE.toString().encodeToByteArray())
        val result = fixture.ok("set_drawing", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("fileId", fileId); put("fileName", "part.json"); put("units", "inch") })
        assertTrue(result.text.startsWith("Drawing set"))
        assertEquals(10.0 / 25.4, result.obj["extents"]!!.num("maxX"), 1e-9)
        assertEquals("part.json", fixture.drawings.loadDrawing(fixture.millWorkspace().id)?.source?.fileName)
        fixture.close()
    }

    @Test
    fun setDrawingPreviewGivesTheSummaryAndWritesNothing() = runTest {
        val fixture = PopulateFixture(this).start()
        val result = fixture.ok("set_drawing", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("document", LINE_AND_CIRCLE); put("preview", true) })
        assertEquals("Preview: 2 shapes on 1 layer", result.text)
        assertNull(fixture.drawings.loadDrawing(fixture.millWorkspace().id))
        fixture.close()
    }

    @Test
    fun setDrawingRefusesTwoSourcesAndABadFile() = runTest {
        val fixture = PopulateFixture(this).start()
        val both = fixture.error("set_drawing", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("document", LINE_AND_CIRCLE); put("fileId", "x") })
        assertEquals("Give either fileId or document, not both and not none.", both.message)
        val none = fixture.error("set_drawing", args { put("machine", "Mill"); put("workspace", "Default Workspace") })
        assertEquals("Give either fileId or document, not both and not none.", none.message)
        val missing = fixture.error("set_drawing", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("fileId", "gone") })
        assertEquals("The upload \"gone\" is not there or expired. Upload the file again.", missing.message)
        val pdf = fixture.upload("application/pdf", "%PDF-1.4".encodeToByteArray())
        val notADrawing = fixture.error("set_drawing", args { put("machine", "Mill"); put("workspace", "Default Workspace"); put("fileId", pdf) })
        assertEquals("This file isn't a drawing SuperDRO can open.", notADrawing.message)
        fixture.close()
    }

    @Test
    fun clearDrawingAsksTheGateAndRemovesTheDrawing() = runTest {
        val fixture = PopulateFixture(this).start()
        val workspace = fixture.millWorkspace()
        val empty = fixture.ok("clear_drawing", args { put("machine", "Mill"); put("workspace", workspace.id) })
        assertEquals("The workspace \"Default Workspace\" has no drawing", empty.text)
        assertEquals(emptyList(), fixture.gate.requests)
        fixture.ok("set_drawing", args { put("machine", "Mill"); put("workspace", workspace.id); put("document", LINE_AND_CIRCLE) })
        val preview = fixture.ok("clear_drawing", args { put("machine", "Mill"); put("workspace", workspace.id); put("preview", true) })
        assertEquals("Preview: the drawing (2 shapes on 1 layer) would be removed", preview.text)
        fixture.gate.decision = GateDecision.Refused
        assertEquals(GateTexts.REFUSED, fixture.error("clear_drawing", args { put("machine", "Mill"); put("workspace", workspace.id) }).message)
        assertNotNull(fixture.drawings.loadDrawing(workspace.id))
        fixture.gate.decision = GateDecision.Allowed
        val result = fixture.ok("clear_drawing", args { put("machine", "Mill"); put("workspace", workspace.id) })
        assertEquals("Drawing removed (2 shapes on 1 layer)", result.text)
        assertNull(fixture.drawings.loadDrawing(workspace.id))
        assertEquals("Remove the drawing of \"Default Workspace\" on Mill", fixture.gate.requests.last().summary)
        fixture.close()
    }
}
