package org.superdro.core.drawing.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineType
import org.superdro.core.model.Plane
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace

class DxfExporterTest {

    /** The workspace of the example. */
    private val example = Workspace(
        subDatums = listOf(SubDatum(name = "Point 1", tags = listOf("Holes"), x = 10_000.0, y = 5_000.0)),
        guides = listOf(Guide(axis = AxisId.X, valueUm = 20_000.0)),
    )
    private val exampleDrawing = Drawing(
        layers = listOf(DrawingLayer(0, "PART")),
        shapes = listOf(DrawingShape(ShapeType.Line, x1 = 0, y1 = 0, x2 = 20_000_000, y2 = 5_000_000)),
    )

    private fun pairs(text: String): List<Pair<Int, String>> {
        val lines = text.split("\r\n")
        return (0 until lines.size - 1 step 2).map { index -> lines[index].trim().toInt() to lines[index + 1] }
    }

    /** The tags of the section [name]. */
    private fun section(text: String, name: String): List<Pair<Int, String>> {
        val all = pairs(text)
        val start = all.indices.first { index -> all[index] == (0 to "SECTION") && all[index + 1] == (2 to name) }
        val end = (start until all.size).first { index -> all[index] == (0 to "ENDSEC") }
        return all.subList(start + 2, end)
    }

    private fun header(text: String, variable: String): List<Pair<Int, String>> {
        val tags = section(text, "HEADER")
        val start = tags.indexOf(9 to variable)
        return tags.drop(start + 1).takeWhile { tag -> tag.first != 9 }
    }

    private fun entities(text: String): List<List<Pair<Int, String>>> {
        val result = ArrayList<MutableList<Pair<Int, String>>>()
        for (tag in section(text, "ENTITIES")) {
            if (tag.first == 0) result += mutableListOf(tag) else result.last() += tag
        }
        return result
    }

    private fun layerRecords(text: String): List<List<Pair<Int, String>>> {
        val tables = section(text, "TABLES")
        val records = ArrayList<MutableList<Pair<Int, String>>>()
        var inLayerTable = false
        for (tag in tables) {
            if (tag == (2 to "LAYER") && records.isEmpty()) inLayerTable = true
            if (tag.first == 0) {
                if (tag.second == "ENDTAB") inLayerTable = false
                if (inLayerTable && tag.second == "LAYER") records += mutableListOf(tag)
                continue
            }
            if (inLayerTable && records.isNotEmpty()) records.last() += tag
        }
        return records
    }

    private fun layerColors(text: String): Map<String, String> =
        layerRecords(text).associate { record -> record.first { tag -> tag.first == 2 }.second to record.first { tag -> tag.first == 62 }.second }

    @Test
    fun theExampleWorkspaceGivesTheExpectedEntities() {
        val text = DxfExporter.exportText(DxfExportRequest(example, exampleDrawing))
        fun entity(type: String, handle: String, layer: String, vararg data: Pair<Int, String>) =
            listOf(0 to type, 5 to handle, 330 to "1F", 100 to "AcDbEntity", 8 to layer) + data.toList()
        assertEquals(
            listOf(
                entity("POINT", "206", "DATUM", 100 to "AcDbPoint", 10 to "0.0", 20 to "0.0", 30 to "0.0"),
                entity("LINE", "207", "DATUM", 100 to "AcDbLine", 10 to "-2.5", 20 to "0.0", 30 to "0.0", 11 to "2.5", 21 to "0.0", 31 to "0.0"),
                entity("LINE", "208", "DATUM", 100 to "AcDbLine", 10 to "0.0", 20 to "-2.5", 30 to "0.0", 11 to "0.0", 21 to "2.5", 31 to "0.0"),
                entity("LINE", "209", "PART", 100 to "AcDbLine", 10 to "0.0", 20 to "0.0", 30 to "0.0", 11 to "20.0", 21 to "5.0", 31 to "0.0"),
                entity("POINT", "20A", "TAG_Holes", 100 to "AcDbPoint", 10 to "10.0", 20 to "5.0", 30 to "0.0"),
                entity("XLINE", "20B", "GUIDES", 100 to "AcDbXline", 10 to "20.0", 20 to "0.0", 30 to "0.0", 11 to "0.0", 21 to "1.0", 31 to "0.0"),
                entity(
                    "TEXT", "20C", "DATUM_LABELS", 100 to "AcDbText", 10 to "11.5", 20 to "6.5", 30 to "0.0", 40 to "2.5",
                    1 to "Point 1", 7 to "Standard", 100 to "AcDbText",
                ),
            ),
            entities(text),
        )
        assertEquals(
            mapOf("0" to "7", "DATUM" to "7", "TAG_Holes" to "140", "DATUM_LABELS" to "7", "GUIDES" to "7", "PART" to "7"),
            layerColors(text),
        )
        assertEquals(listOf(5 to "20D"), header(text, "\$HANDSEED"))
        assertEquals(listOf(10 to "0.0", 20 to "0.0", 30 to "0.0"), header(text, "\$EXTMIN"))
        assertEquals(listOf(10 to "20.0", 20 to "5.0", 30 to "0.0"), header(text, "\$EXTMAX"))
    }

    @Test
    fun theTemplateIsAnAc1015FileWithAnsi1252() {
        val text = DxfExporter.exportText(DxfExportRequest(example, exampleDrawing))
        assertEquals(listOf(1 to "AC1015"), header(text, "\$ACADVER"))
        assertEquals(listOf(3 to "ANSI_1252"), header(text, "\$DWGCODEPAGE"))
        assertEquals(listOf(70 to "34"), header(text, "\$PDMODE"))
        assertEquals(listOf(40 to "-3.0"), header(text, "\$PDSIZE"))
        assertEquals(listOf("HEADER", "CLASSES", "TABLES", "BLOCKS", "ENTITIES", "OBJECTS"), pairs(text).zipWithNext().filter { (a, _) -> a == (0 to "SECTION") }.map { (_, b) -> b.second })
        assertTrue(text.endsWith("  0\r\nEOF\r\n"))
        // CR LF only: no LF without a CR before it.
        assertFalse(Regex("[^\r]\n").containsMatchIn(text))
        // The group codes are right-aligned in 3 characters.
        assertTrue(text.startsWith("  0\r\nSECTION\r\n  2\r\nHEADER\r\n  9\r\n\$ACADVER\r\n"))
        // Each handle is unique.
        val handles = pairs(text).filter { (code, _) -> code == 5 || code == 105 }.map { (_, value) -> value }
        assertEquals(handles.size, handles.toSet().size)
    }

    @Test
    fun theUnitsOfTheExportGiveInsunitsAndMeasurement() {
        val metric = DxfExporter.exportText(DxfExportRequest(example, exampleDrawing, metric = true))
        assertEquals(listOf(70 to "4"), header(metric, "\$INSUNITS"))
        assertEquals(listOf(70 to "1"), header(metric, "\$MEASUREMENT"))
        val inch = DxfExporter.exportText(DxfExportRequest(example, exampleDrawing, metric = false))
        assertEquals(listOf(70 to "1"), header(inch, "\$INSUNITS"))
        assertEquals(listOf(70 to "0"), header(inch, "\$MEASUREMENT"))
        val inchEntities = entities(inch)
        // s = max(0.02 × 0.787401575, 0.0984252) = 0.0984252 in.
        assertEquals(10 to "-0.0984252", inchEntities[1][6])
        assertEquals(11 to "0.787401575", inchEntities[3][9])
        assertEquals(10 to "0.393700787", inchEntities[4][6])
    }

    @Test
    fun theOriginMarkGrowsWithTheExtents() {
        val big = example.copy(subDatums = listOf(SubDatum(name = "", tags = emptyList(), x = 500_000.0, y = 100_000.0)))
        val mark = entities(DxfExporter.exportText(DxfExportRequest(big)))[1]
        assertEquals(10 to "-10.0", mark[6])
        assertEquals(11 to "10.0", mark[9])
    }

    @Test
    fun hiddenLayersStayOutUnlessTheUserSelectsThem() {
        val drawing = Drawing(
            layers = listOf(DrawingLayer(0, "SHOWN"), DrawingLayer(1, "HIDDEN_LAYER", hidden = true)),
            shapes = listOf(
                DrawingShape(ShapeType.Line, layerIndex = 0, x1 = 0, y1 = 0, x2 = 1_000_000, y2 = 0),
                DrawingShape(ShapeType.Line, layerIndex = 1, x1 = 0, y1 = 0, x2 = 0, y2 = 1_000_000),
            ),
        )
        val normal = DxfExporter.exportText(DxfExportRequest(Workspace(), drawing))
        assertEquals(listOf("0", "DATUM", "SHOWN"), layerColors(normal).keys.toList())
        assertEquals(3 + 1, entities(normal).size)
        val all = DxfExporter.exportText(DxfExportRequest(Workspace(), drawing, includeHiddenLayers = true))
        assertEquals(listOf("0", "DATUM", "SHOWN", "HIDDEN_LAYER"), layerColors(all).keys.toList())
        assertEquals(3 + 2, entities(all).size)
    }

    @Test
    fun theShapesGiveTheirDxfEntities() {
        val drawing = Drawing(
            layers = listOf(DrawingLayer(0, ""), DrawingLayer(1, "Measured")),
            shapes = listOf(
                DrawingShape(ShapeType.Arc, x1 = 0, y1 = 0, radius = 2_000_000, startAngle = 0.5, sweepAngle = -1.0, style = 4),
                DrawingShape(ShapeType.Circle, layerIndex = 1, x1 = 1_000_000, y1 = 2_000_000, radius = 3_175_000, style = 5),
                DrawingShape(ShapeType.Polyline, closed = true, points = listOf(0, 0, 10_000_000, 0, 10_000_000, 5_000_000), bulges = mapOf(1 to 0.41421356237309503)),
                DrawingShape(ShapeType.Bezier, points = listOf(0, 0, 1_000_000, 1_000_000, 2_000_000, 1_000_000, 3_000_000, 0, 4_000_000, -1_000_000, 5_000_000, -1_000_000, 6_000_000, 0)),
                DrawingShape(ShapeType.Point, x1 = 7_000_000, y1 = 8_000_000),
                DrawingShape(ShapeType.Line, layerIndex = 9, x1 = 0, y1 = 0, x2 = 1, y2 = 0),
            ),
        )
        val text = DxfExporter.exportText(DxfExportRequest(Workspace(), drawing))
        val list = entities(text).drop(3)
        val arc = list[0].toMap()
        assertEquals("ARC", arc[0])
        assertEquals("LAYER_0", arc[8])
        assertEquals("CENTER", arc[6])
        assertEquals("331.352110243", arc[50])
        assertEquals("28.647889757", arc[51])
        val circle = list[1].toMap()
        assertEquals("MEASURED", circle[8])
        assertEquals("HIDDEN", circle[6])
        assertEquals("3.175", circle[40])
        assertEquals(
            listOf(90 to "3", 70 to "1", 10 to "0.0", 20 to "0.0", 10 to "10.0", 20 to "0.0", 42 to "0.41421356237309503", 10 to "10.0", 20 to "5.0"),
            list[2].dropWhile { tag -> tag != (100 to "AcDbPolyline") }.drop(1),
        )
        val spline = list[3]
        assertEquals(listOf("0.0", "0.0", "0.0", "0.0", "1.0", "1.0", "1.0", "2.0", "2.0", "2.0", "2.0"), spline.filter { tag -> tag.first == 40 }.map { tag -> tag.second })
        assertEquals(listOf(70 to "8", 71 to "3", 72 to "11", 73 to "7", 74 to "0"), spline.filter { tag -> tag.first in 70..74 })
        assertEquals(7, spline.count { tag -> tag.first == 10 })
        assertEquals("POINT", list[4].toMap()[0])
        assertEquals("LAYER_9", list[5].toMap()[8])
        // CENTER and HIDDEN are in the LTYPE table because a shape uses the style 4 and 5.
        val lineTypes = section(text, "TABLES").filter { tag -> tag.first == 2 }.map { tag -> tag.second }
        assertTrue(lineTypes.containsAll(listOf("ByBlock", "ByLayer", "Continuous", "CENTER", "HIDDEN")))
        val plain = DxfExporter.exportText(DxfExportRequest(example, exampleDrawing))
        assertFalse(section(plain, "TABLES").any { tag -> tag == (2 to "CENTER") || tag == (2 to "HIDDEN") })
    }

    @Test
    fun theTagsBecomeLayersWithThePaletteColors() {
        val tags = (0 until 10).map { index -> "T$index" }
        val workspace = Workspace(subDatums = tags.map { tag -> SubDatum(name = "", tags = listOf(tag), x = 0.0, y = 0.0) } + SubDatum(name = "u", x = 1.0, y = 1.0))
        val colors = layerColors(DxfExporter.exportText(DxfExportRequest(workspace)))
        assertEquals(listOf("140", "30", "101", "231", "40", "161", "11", "121", "61", "140"), tags.map { tag -> colors.getValue("TAG_$tag") })
        assertEquals("7", colors["TAG_UNTAGGED"])
        assertEquals("7", colors["DATUM_LABELS"])
    }

    @Test
    fun layerNamesAreValidAndUnique() {
        assertEquals("a_b_c_", DxfExporter.layerName("a:b<c>"))
        assertEquals("Tab here", DxfExporter.layerName("Tab\there"))
        assertEquals("many spaces", DxfExporter.layerName("  many   spaces "))
        assertEquals("Bohrung ä _", DxfExporter.layerName("Bohrung ä Ж"))
        assertEquals("q_u_e_s_t_i_o_n_", DxfExporter.layerName("q?u*e|s=t\"i/o\\n`"))
        val workspace = Workspace(
            subDatums = listOf(SubDatum(tags = listOf("Datum"), x = 0.0, y = 0.0)),
        )
        val drawing = Drawing(layers = listOf(DrawingLayer(0, "tag_datum"), DrawingLayer(1, "0")), shapes = emptyList())
        val names = layerColors(DxfExporter.exportText(DxfExportRequest(workspace, drawing))).keys.toList()
        assertEquals(listOf("0", "DATUM", "TAG_Datum", "tag_datum_2", "0_2"), names)
    }

    @Test
    fun theFileIsWindows1252AndTheLabelsUseUnicodeEscapes() {
        val workspace = Workspace(subDatums = listOf(SubDatum(name = "Bohrung ä ⌀6", tags = listOf("Löcher"), x = 0.0, y = 0.0)))
        val bytes = DxfExporter.export(DxfExportRequest(workspace))
        val text = DxfExporter.exportText(DxfExportRequest(workspace))
        assertTrue(text.contains("\r\nTAG_Löcher\r\n"))
        assertTrue(text.contains("\r\nBohrung \\U+00E4 \\U+23006\r\n"))
        // ö is the byte 0xF6 in windows-1252.
        val index = bytes.indices.first { i -> bytes[i] == 0xF6.toByte() }
        assertEquals("TAG_L", bytes.copyOfRange(index - 5, index).decodeToString())
        assertEquals(text.length, bytes.size)
    }

    @Test
    fun theLathePlaneUsesZAndMinusX() {
        val workspace = Workspace(
            plane = Plane.ZX,
            subDatums = listOf(SubDatum(name = "", x = 5_000.0, z = -20_000.0)),
            guides = listOf(Guide(axis = AxisId.Z, valueUm = -10_000.0), Guide(axis = AxisId.X, valueUm = 3_000.0), Guide(axis = AxisId.Y, valueUm = 1.0)),
        )
        val list = entities(DxfExporter.exportText(DxfExportRequest(workspace)))
        assertEquals(listOf(10 to "-20.0", 20 to "-5.0", 30 to "0.0"), list[3].filter { tag -> tag.first in listOf(10, 20, 30) })
        assertEquals(listOf(10 to "-10.0", 20 to "0.0", 30 to "0.0", 11 to "0.0", 21 to "1.0", 31 to "0.0"), list[4].drop(6))
        assertEquals(listOf(10 to "0.0", 20 to "-3.0", 30 to "0.0", 11 to "1.0", 21 to "0.0", 31 to "0.0"), list[5].drop(6))
        assertEquals(6, list.size)
    }

    @Test
    fun theFileNameHasTheCleanParts() {
        assertEquals(
            "superdro-workspace-My_Shop_1-Bridgeport_Mill-mill-20261012_083000.dxf",
            DxfExporter.fileName("My Shop #1", "Bridgeport  Mill!", MachineType.VerticalMill, "20261012_083000"),
        )
        assertEquals("superdro-workspace-lathe-20261012_083000.dxf", DxfExporter.fileName("***", "", MachineType.Lathe, "20261012_083000"))
    }
}
