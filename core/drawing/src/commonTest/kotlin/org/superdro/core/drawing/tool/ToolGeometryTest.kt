package org.superdro.core.drawing.tool

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.dxf.TestDxf
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.ShapeType
import org.superdro.core.model.ToolGeometry

class ToolGeometryTest {

    private val created = Instant.parse("2026-10-12T08:30:00Z")

    private fun line(x1: Long, y1: Long, x2: Long, y2: Long) = DrawingShape(ShapeType.Line, x1 = x1, y1 = y1, x2 = x2, y2 = y2)

    /** A triangle insert of 10 mm with the tip at the origin, as three lines. */
    private val triangle = listOf(
        line(0, 0, 10_000_000, 2_000_000),
        line(10_000_000, 2_000_000, 10_000_000, -2_000_000),
        line(10_000_000, -2_000_000, 0, 0),
    )

    @Test
    fun theTextHasTheVersionTheFactorAndTheDocument() {
        val geometry = ToolGeometry(shapes = triangle, drawnIn = 25.4, layers = listOf(DrawingLayer(0, "0")))
        val text = ToolGeometryCodec.encode(geometry, created)
        assertTrue(text.startsWith("{\"v\":1,\"drawnIn\":25.4,\"document\":\"{\\\"schema\\\":\\\"touchdro.wireframe\\\""))
        val decoded = ToolGeometryCodec.decode(text)
        assertEquals(emptyList(), decoded.problems)
        assertEquals(25.4, decoded.geometry!!.drawnIn)
        assertEquals(triangle, decoded.geometry.shapes)
        assertEquals(listOf(DrawingLayer(0, "0")), decoded.geometry.layers)
    }

    @Test
    fun theDocumentCanBeAnObjectAndABadFactorGivesOne() {
        val text = """{"v":1,"drawnIn":"NaN","document":{"schema":"touchdro.wireframe","v":"3.0","layers":[{"n":"0"}],""" +
            """"entities":[{"t":"L","l":0,"p":[[0,0],[1000,0]]}]}}"""
        val decoded = ToolGeometryCodec.decode(text)
        assertEquals(1.0, decoded.geometry!!.drawnIn)
        assertEquals(listOf(line(0, 0, 1000, 0)), decoded.geometry.shapes)
    }

    @Test
    fun aBadTextGivesProblems() {
        assertNull(ToolGeometryCodec.decode("not json").geometry)
        val missing = ToolGeometryCodec.decode("""{"v":1,"drawnIn":1.0}""")
        assertEquals("the cutter geometry has no \"document\"", missing.problems.single().message)
        val bad = ToolGeometryCodec.decode("""{"v":1,"drawnIn":1.0,"document":"{\"schema\":\"touchdro.wireframe\",\"v\":\"3.0\",\"layers\":[]}"}""")
        assertEquals("missing \"entities\"", bad.problems.single().message)
        assertEquals("This drawing couldn't be read. Try importing it again.", ToolGeometryCodec.UNREADABLE_MESSAGE)
    }

    @Test
    fun aClosedOutlineWithTheOriginOnTheEdgeHasNoChecks() {
        val report = ToolGeometryImport.check(ToolGeometry(shapes = triangle))
        assertEquals(emptyList(), report.checks)
        assertEquals(1, report.closedOutlines)
        assertEquals(0, report.openChains)
        assertEquals(10_000.0, report.widthUm)
        assertEquals(4_000.0, report.heightUm)
    }

    @Test
    fun theGeometryChecks() {
        val open = ToolGeometryImport.check(ToolGeometry(shapes = triangle.take(2)))
        assertEquals(listOf(ToolGeometryCheck.NotClosed), open.checks)
        val partial = ToolGeometryImport.check(ToolGeometry(shapes = triangle + line(0, 5_000_000, 3_000_000, 5_000_000)))
        assertEquals(listOf(ToolGeometryCheck.OpenSegments), partial.checks)
        val tiny = ToolGeometryImport.check(ToolGeometry(shapes = triangle, drawnIn = 0.05))
        assertEquals(listOf(ToolGeometryCheck.UnlikelySize), tiny.checks)
        val inch = ToolGeometryImport.check(ToolGeometry(shapes = triangle, drawnIn = 25.4))
        assertEquals(emptyList(), inch.checks)
        assertEquals(254_000.0, inch.widthUm, 1e-6)
        val away = ToolGeometryImport.check(ToolGeometry(shapes = triangle.map { shape -> shape.copy(x1 = shape.x1!! + 5_000_000, x2 = shape.x2!! + 5_000_000) }))
        assertEquals(listOf(ToolGeometryCheck.OriginOffEdge), away.checks)
        val near = ToolGeometryImport.check(ToolGeometry(shapes = triangle.map { shape -> shape.copy(x1 = shape.x1!! + 900_000, x2 = shape.x2!! + 900_000) }))
        assertEquals(emptyList(), near.checks)
        val inside = ToolGeometryImport.check(ToolGeometry(shapes = triangle.map { shape -> shape.copy(x1 = shape.x1!! - 5_000_000, x2 = shape.x2!! - 5_000_000) }))
        assertEquals(emptyList(), inside.checks)
        val many = ToolGeometryImport.check(ToolGeometry(shapes = triangle + List(500) { DrawingShape(ShapeType.Point, x1 = 1, y1 = 1) }))
        assertEquals(listOf(ToolGeometryCheck.TooManyItems), many.checks)
        val noUnits = ToolGeometryImport.check(ToolGeometry(shapes = triangle), unitsAssumed = true)
        assertEquals(listOf(ToolGeometryCheck.NoUnits), noUnits.checks)
    }

    @Test
    fun curvedOutlinesClose() {
        val circle = ToolGeometryImport.check(ToolGeometry(shapes = listOf(DrawingShape(ShapeType.Circle, x1 = 0, y1 = 0, radius = 3_000_000))))
        assertEquals(emptyList(), circle.checks)
        val rounded = listOf(
            DrawingShape(ShapeType.Arc, x1 = 0, y1 = 0, radius = 2_000_000, startAngle = 0.0, sweepAngle = kotlin.math.PI),
            line(-2_000_000, 0, 2_000_000, 0),
        )
        assertEquals(1, ToolGeometryImport.check(ToolGeometry(shapes = rounded)).closedOutlines)
    }

    @Test
    fun theImportOfADxfFileGivesTheGeometryAndTheChecks() {
        val pairs = listOf(
            "0|LINE", "8|0", "10|0.0", "20|0.0", "11|10.0", "21|2.0",
            "0|LINE", "8|0", "10|10.0", "20|2.0", "11|10.0", "21|-2.0",
            "0|LINE", "8|0", "10|10.0", "20|-2.0", "11|0.0", "21|0.0",
        )
        val bytes = TestDxf.file(pairs, header = emptyList())
        val result = ToolGeometryImport.import(bytes, "insert.dxf", now = created) as ToolGeometryImportResult.Imported
        assertEquals(DrawingSource("insert.dxf", DrawingFormat.AsciiDxf), result.geometry.source)
        assertEquals(triangle, result.geometry.shapes.map { shape -> shape.copy(style = 1) })
        assertEquals(listOf(ToolGeometryCheck.NoUnits), result.report.checks)
        assertEquals(DrawingProblemCodes.UNITS_ASSUMED, result.warnings.single().code)
        val failed = ToolGeometryImport.import("%PDF".encodeToByteArray(), "x.pdf") as ToolGeometryImportResult.Failed
        assertEquals("This file isn't a drawing SuperDRO can open.", failed.message)
        val broken = ToolGeometryImport.import("  0\nSECTION\n  2\nENTITIES\n  0\nLINE\n  5\n0\n".encodeToByteArray(), "x.dxf") as ToolGeometryImportResult.Failed
        assertEquals(ToolGeometryCodec.UNREADABLE_MESSAGE, broken.message)
        assertEquals(DrawingProblemCodes.PARSE_ERROR, broken.problems.first().code)
    }

    @Test
    fun theCheckTextsAreTheExpectedTexts() {
        assertEquals(
            listOf(
                "This drawing has far too much in it to be a tool. It may be a drawing of the part rather than of the cutter.",
                "The size below doesn't look like a cutting tool's. Check which units the drawing was made in.",
                "The outline isn't closed, so the tool will show as a line drawing instead of a solid shape.",
                "Part of this outline doesn't join up. The solid shape is missing that part of the tool.",
                "The origin doesn't look like it's on the cutting edge. The X and Z offsets are measured from it.",
                "The drawing doesn't say which units it uses — check the size below.",
            ),
            ToolGeometryCheck.entries.map { check -> check.message },
        )
    }
}
