package org.superdro.validation.functions

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.drawing.ConvertResult
import org.superdro.core.drawing.ConvertStatus
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.drawing.DrawingFileType
import org.superdro.core.drawing.DrawingImportResult
import org.superdro.core.drawing.DrawingImporter
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.DrawingTypeTest
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.drawing.NmPoint
import org.superdro.core.drawing.WireframeArc
import org.superdro.core.drawing.WireframeBezier
import org.superdro.core.drawing.WireframeCircle
import org.superdro.core.drawing.WireframeDocument
import org.superdro.core.drawing.WireframeEntity
import org.superdro.core.drawing.WireframeLayer
import org.superdro.core.drawing.WireframeLine
import org.superdro.core.drawing.WireframePoint
import org.superdro.core.drawing.WireframePolyline
import org.superdro.core.drawing.dxf.DxfConverter
import org.superdro.core.drawing.dxf.DxfImportSettings
import org.superdro.core.drawing.export.DxfExportRequest
import org.superdro.core.drawing.export.DxfExporter
import org.superdro.core.model.AxisId
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingLayer
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.Guide
import org.superdro.core.model.ShapeType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.util.Clock
import org.superdro.validation.Traceability

/**
 * The DXF rules.
 *
 * The fixtures are in `src/test/resources/org/superdro/validation/functions/dxf`. The tool ezdxf
 * 1.4.4 made each drawing as an ASCII DXF file and as a binary DXF file (`make_dxf.py`). Both
 * files of a drawing must give the same wireframe document.
 */
class DxfValidationTest {

    private val now = Instant.parse("2026-10-12T00:00:00Z")
    private val fixedClock = Clock { 0L }

    private fun fixture(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/org/superdro/validation/functions/dxf/$name"), "fixture $name").use { it.readBytes() }

    private fun convert(name: String, settings: DxfImportSettings = DxfImportSettings(), clock: Clock = fixedClock): ConvertResult =
        DxfConverter.convert(fixture(name), settings, clock, now)

    private fun ready(name: String, settings: DxfImportSettings = DxfImportSettings()): WireframeDocument {
        val result = convert(name, settings)
        assertEquals(ConvertStatus.Ready, result.status, "$name: ${result.errors}")
        return assertNotNull(result.wireframe)
    }

    private fun failedCode(name: String, settings: DxfImportSettings = DxfImportSettings(), clock: Clock = fixedClock): String {
        val result = convert(name, settings, clock)
        assertEquals(ConvertStatus.Failed, result.status, name)
        return result.errors.single().code
    }

    private fun WireframeDocument.layerOf(entity: WireframeEntity): WireframeLayer = layers[entity.layer]

    private val drawings = listOf(
        "basic_r2000", "cp1252_r2000", "utf8_r2018", "nounits_r2000", "old_r12", "inch_r2000",
        "nested8", "lines12", "poly2000", "poly2001", "spline1000", "spline1001",
    )

    @Test
    fun each_ascii_fixture_and_its_binary_twin_give_the_same_wireframe() {
        for (name in drawings) {
            val ascii = convert("${name}_ascii.dxf")
            val binary = convert("${name}_binary.dxf")
            assertEquals(ConvertStatus.Ready, ascii.status, "$name ASCII: ${ascii.errors}")
            assertEquals(ascii.wireframe, binary.wireframe, "$name: the same wireframe")
            assertEquals(ascii.warnings.map { it.code }, binary.warnings.map { it.code }, "$name: the same warnings")
        }
        for (name in listOf("nested9")) {
            assertEquals(convert("${name}_ascii.dxf").errors.map { it.code }, convert("${name}_binary.dxf").errors.map { it.code }, "$name errors")
        }
    }

    @Test
    fun basic_drawing_entities_in_integer_nm() {
        val doc = ready("basic_r2000_binary.dxf")
        val lines = doc.entities.filterIsInstance<WireframeLine>()
        val line = lines.single { it.start == NmPoint(0, 0) }
        assertEquals(NmPoint(100_000_000, 0), line.end, "100 mm = 100 000 000 nm")
        assertEquals("Outline", doc.layerOf(line).name)
        val circle = doc.entities.filterIsInstance<WireframeCircle>().single()
        assertEquals(NmPoint(50_000_000, 25_000_000), circle.center)
        assertEquals(10_000_000L, circle.radius)
        assertEquals(4, doc.styleOf(circle), "line type CENTER gives the style 4")
        val arc = doc.entities.filterIsInstance<WireframeArc>().single()
        assertEquals(NmPoint(20_000_000, 20_000_000), arc.center)
        assertEquals(5_000_000L, arc.radius)
        assertEquals(0.0, arc.start, 1e-9)
        assertEquals(PI / 2, arc.sweep, 1e-9, "the angles are radians")
        assertEquals(5, doc.styleOf(arc), "line type DASHED gives the style 5")
        val unknownType = lines.single { it.start == NmPoint(0, 10_000_000) }
        assertEquals(1, doc.styleOf(unknownType), "a line type that is not in the LTYPE table becomes Continuous")
        val polyline = doc.entities.filterIsInstance<WireframePolyline>().single()
        assertEquals(
            listOf(NmPoint(0, 0), NmPoint(40_000_000, 0), NmPoint(40_000_000, 30_000_000), NmPoint(0, 30_000_000)),
            polyline.points,
        )
        assertTrue(polyline.closed)
        assertEquals(0.5, polyline.bulges[1]!!, 1e-12)
        val point = doc.entities.filterIsInstance<WireframePoint>().single()
        assertEquals(NmPoint(5_000_000, 5_000_000), point.point)
        assertFalse(doc.layerOf(point).visible, "the layer is off")
        assertEquals(2, doc.entities.filterIsInstance<WireframeBezier>().size, "the ellipse and the spline")
        // The block line on layer 0 takes the layer of the INSERT.
        val blockLine = lines.single { it.start == NmPoint(200_000_000, 0) }
        assertEquals(NmPoint(202_000_000, 0), blockLine.end)
        assertEquals("Outline", doc.layerOf(blockLine).name)
        assertTrue(convert("basic_r2000_binary.dxf").warnings.any { it.code == DrawingProblemCodes.ENTITY_DROPPED }, "TEXT is dropped")
    }

    @Test
    fun character_set_dwgcodepage_before_ac1021_and_utf8_after() {
        for (name in listOf("cp1252_r2000_ascii.dxf", "cp1252_r2000_binary.dxf", "utf8_r2018_ascii.dxf", "utf8_r2018_binary.dxf")) {
            assertTrue(ready(name).layers.any { it.name == "Ébauche" }, "$name: ${ready(name).layers.map { it.name }}")
        }
    }

    @Test
    fun units_to_nm() {
        val inch = ready("inch_r2000_ascii.dxf").entities.filterIsInstance<WireframeLine>().single()
        assertEquals(NmPoint(25_400_000, 50_800_000), inch.end, "1 in = 25 400 000 nm")
        val noUnits = convert("nounits_r2000_ascii.dxf")
        assertTrue(noUnits.warnings.any { it.code == DrawingProblemCodes.UNITS_ASSUMED })
        val line = noUnits.wireframe!!.entities.filterIsInstance<WireframeLine>().single { it.start == NmPoint(0, 0) }
        assertEquals(NmPoint(100_000_000, 0), line.end, "mm is assumed")
        val r12 = ready("old_r12_binary.dxf")
        assertEquals(NmPoint(1_000_000, 2_000_000), r12.entities.filterIsInstance<WireframeLine>().single().start, "R12 binary with 1-byte group codes")
        val polyline = r12.entities.filterIsInstance<WireframePolyline>().single()
        assertTrue(polyline.closed)
        assertEquals(3, polyline.points.size)
    }

    @Test
    fun default_limits_are_the_3_32_00_limits() {
        val limits = DxfLimits()
        assertEquals(2_000_000L, limits.maxSourceBytes)
        assertEquals(10_000, limits.maxEntities)
        assertEquals(10_000, limits.maxBezierSegments)
        assertEquals(8, limits.maxBlockLevels)
        assertEquals(5_000L, limits.maxParseTimeMs)
        assertEquals(1_000L, limits.curveToleranceNm, "1 µm")
        assertEquals(2_000, limits.polylineWarningPoints)
        assertEquals(1_000, limits.maxSplineControlPoints)
    }

    @Test
    fun the_limits_give_the_codes() {
        assertEquals(DrawingProblemCodes.SOURCE_TOO_LARGE, failedCode("basic_r2000_ascii.dxf", DxfImportSettings(DxfLimits(maxSourceBytes = 1_000L))))
        assertEquals(DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED, failedCode("lines12_binary.dxf", DxfImportSettings(DxfLimits(maxEntities = 11))))
        assertEquals(12, ready("lines12_binary.dxf", DxfImportSettings(DxfLimits(maxEntities = 12))).entities.size)
        assertEquals(DrawingProblemCodes.BEZIER_SEGMENTS_EXCEEDED, failedCode("basic_r2000_ascii.dxf", DxfImportSettings(DxfLimits(maxBezierSegments = 1))))
        assertEquals(DrawingProblemCodes.NESTING_DEPTH_EXCEEDED, failedCode("nested9_ascii.dxf"))
        assertEquals(DrawingProblemCodes.NESTING_DEPTH_EXCEEDED, failedCode("nested9_binary.dxf"))
        assertEquals(1, ready("nested8_binary.dxf").entities.size, "8 block levels are permitted")
        var calls = 0L
        val slow = Clock { 6_000L * calls++ }
        assertEquals(DrawingProblemCodes.PARSE_TIME_EXCEEDED, failedCode("basic_r2000_ascii.dxf", clock = slow))
    }

    @Test
    fun polyline_warning_above_2000_points_and_splines_above_1000_control_points_are_dropped() {
        assertTrue(convert("poly2001_ascii.dxf").warnings.any { it.code == DrawingProblemCodes.POLYLINE_POINTS_WARNING })
        assertFalse(convert("poly2000_ascii.dxf").warnings.any { it.code == DrawingProblemCodes.POLYLINE_POINTS_WARNING })
        val dropped = convert("spline1001_binary.dxf")
        assertTrue(dropped.warnings.any { it.code == DrawingProblemCodes.CURVE_TOO_COMPLEX })
        assertTrue(dropped.wireframe!!.entities.none { it is WireframeBezier }, "the spline is dropped")
        assertTrue(ready("spline1000_binary.dxf").entities.any { it is WireframeBezier }, "1000 control points are permitted")
    }

    @Test
    fun type_test_by_content() {
        assertEquals(DrawingFileType.AsciiDxf, DrawingTypeTest.detect(fixture("basic_r2000_ascii.dxf")))
        assertEquals(DrawingFileType.BinaryDxf, DrawingTypeTest.detect(fixture("basic_r2000_binary.dxf")))
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect("""  {"schema":"touchdro.wireframe","v":"3.0"}""".encodeToByteArray()))
        val others = listOf(
            "%PDF-1.4\n%âãÏÓ\n".encodeToByteArray(),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            "AC1015\u0000\u0000\u0000".encodeToByteArray(),
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray(),
            "G21\nG90\nG0 X0 Y0\n".encodeToByteArray(),
            "{\"type\":\"FeatureCollection\"}".encodeToByteArray(),
        )
        for (bytes in others) assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect(bytes), bytes.decodeToString())
        val notADrawing = assertIs<DrawingImportResult.NotADrawing>(DrawingImporter.import("%PDF-1.4\n".encodeToByteArray()))
        assertEquals("This file isn't a drawing SuperDRO can open.", notADrawing.message)
    }

    @Test
    fun ascii_test_looks_at_the_first_64_pairs_and_codes_0_to_999() {
        fun pairs(count: Int) = buildString {
            repeat(count) { append("  5\nABC\n") }
            append("  0\nSECTION\n")
        }.encodeToByteArray()
        assertTrue(DrawingTypeTest.isAsciiDxf(pairs(63)))
        assertFalse(DrawingTypeTest.isAsciiDxf(pairs(64)))
        assertTrue(DrawingTypeTest.isAsciiDxf("999\na comment\n999\nmore\n  0\nSECTION\n".encodeToByteArray()), "code 999 is skipped")
        assertFalse(DrawingTypeTest.isAsciiDxf("1000\nX\n  0\nSECTION\n".encodeToByteArray()), "a code of 1000 or more")
        assertTrue(DrawingTypeTest.isAsciiDxf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "  0\r\nSECTION\r\n".encodeToByteArray()), "a UTF-8 BOM")
    }

    private val exampleJson = """
        {"schema":"touchdro.convert.result","v":"1","status":"ready","format":"dxf",
         "converterBuild":"local/dxf-converter","errors":[],"warnings":[],
         "wireframe":{"schema":"touchdro.wireframe","v":"3.0","createdAt":"2026-10-11T20:00:00Z",
          "axes":["X","Y"],"ext":{"min":[0,0],"max":[50000000,30000000]},
          "layers":[{"n":"0"},{"n":"HOLES","st":1}],
          "entities":[
           {"t":"P","l":0,"pts":[[0,0],[50000000,0],[50000000,30000000],[0,30000000]],"cl":1},
           {"t":"C","l":1,"c":[10000000,10000000],"r":3175000}]}}
    """.trimIndent()

    @Test
    fun wireframe_document_json_and_gzip() {
        val json = assertIs<DrawingImportResult.Imported>(DrawingImporter.import(exampleJson.encodeToByteArray()))
        assertEquals(DrawingFormat.Wireframe, json.format)
        val circle = json.document.entities.filterIsInstance<WireframeCircle>().single()
        assertEquals(3_175_000L, circle.radius, "a 6.35 mm hole")
        assertEquals("HOLES", json.document.layerOf(circle).name)
        val polyline = json.document.entities.filterIsInstance<WireframePolyline>().single()
        assertTrue(polyline.closed)
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(exampleJson.encodeToByteArray()) } }.toByteArray()
        val gzip = assertIs<DrawingImportResult.Imported>(DrawingImporter.import(gz))
        assertEquals(json.document.entities, gzip.document.entities)
    }

    @Test
    fun binary_dxf_converts_on_the_device_with_no_network_code() {
        val imported = assertIs<DrawingImportResult.Imported>(DrawingImporter.import(fixture("basic_r2000_binary.dxf")))
        assertEquals(DrawingFormat.BinaryDxf, imported.format)
        val sources = Traceability.rootDir.resolve("core/drawing/src/commonMain").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val network = listOf("java.net", "HttpURLConnection", "okhttp", "io.ktor", "resultUrl")
        for (file in sources) {
            val text = file.readText()
            for (word in network) assertFalse(word in text, "${file.name} has $word")
        }
    }

    private fun headerValue(text: String, variable: String): String {
        val lines = text.split("\r\n")
        val index = (0 until lines.size - 3 step 1).first { i -> lines[i].trim() == "9" && lines[i + 1].trim() == variable }
        return lines[index + 3].trim()
    }

    private fun exportWorkspace() = Workspace(
        subDatums = listOf(SubDatum(name = "Point 1", tags = listOf("Holes"), x = 10_000.0, y = 5_000.0)),
        guides = listOf(Guide(axis = AxisId.X, valueUm = 20_000.0)),
    )

    private fun exportDrawing() = Drawing(
        layers = listOf(DrawingLayer(0, "Outline", hidden = false), DrawingLayer(1, "Secret", hidden = true)),
        shapes = listOf(
            DrawingShape(type = ShapeType.Line, layerIndex = 0, x1 = 0L, y1 = 0L, x2 = 20_000_000L, y2 = 5_000_000L),
            DrawingShape(type = ShapeType.Line, layerIndex = 1, x1 = 0L, y1 = 0L, x2 = 1_000_000L, y2 = 1_000_000L),
        ),
    )

    @Test
    fun measurement_follows_the_export_units() {
        val inch = DxfExporter.exportText(DxfExportRequest(exportWorkspace(), exportDrawing(), metric = false))
        assertEquals("1", headerValue(inch, "\$INSUNITS"))
        assertEquals("0", headerValue(inch, "\$MEASUREMENT"))
        val mm = DxfExporter.exportText(DxfExportRequest(exportWorkspace(), exportDrawing(), metric = true))
        assertEquals("4", headerValue(mm, "\$INSUNITS"))
        assertEquals("1", headerValue(mm, "\$MEASUREMENT"))
        // Keep the two files for the check with ezdxf (see the report).
        val out = File(System.getProperty("superdro.rootDir"), "validation/build/functions-export").apply { mkdirs() }
        out.resolve("export_inch.dxf").writeBytes(DxfExporter.export(DxfExportRequest(exportWorkspace(), exportDrawing(), metric = false)))
        out.resolve("export_mm.dxf").writeBytes(DxfExporter.export(DxfExportRequest(exportWorkspace(), exportDrawing(), metric = true)))
    }

    @Test
    fun hidden_layers_only_when_selected_and_point_names_are_kept() {
        val without = DxfExporter.exportText(DxfExportRequest(exportWorkspace(), exportDrawing(), includeHiddenLayers = false))
        assertFalse("Secret" in without, "a hidden layer stays out")
        val with = DxfExporter.exportText(DxfExportRequest(exportWorkspace(), exportDrawing(), includeHiddenLayers = true))
        assertTrue("Secret" in with, "the user selects the hidden layers")
        val document = WireframeDocument(layers = listOf(WireframeLayer("0")), entities = listOf(WireframePoint(NmPoint(1, 2), name = "H1")))
        assertEquals("H1", DrawingConversion.toShapes(document).single().name, "the point name stays after Apply")
    }

    @Test
    fun export_template_ac1015_crlf_windows_1252_and_tags_as_layers() {
        val workspace = exportWorkspace().copy(subDatums = listOf(SubDatum(name = "Point 1", tags = listOf("Trous_é"), x = 10_000.0, y = 5_000.0)))
        val text = DxfExporter.exportText(DxfExportRequest(workspace, exportDrawing()))
        assertEquals("AC1015", headerValue(text, "\$ACADVER"))
        assertFalse(Regex("[^\r]\n").containsMatchIn(text), "each line ends with CR LF")
        assertTrue("TAG_Trous_é" in text, "the tag is a layer")
        val bytes = DxfExporter.export(DxfExportRequest(workspace, exportDrawing()))
        val e9 = bytes.indexOfFirst { it == 0xE9.toByte() }
        assertTrue(e9 >= 0, "é is the windows-1252 byte E9")
        assertFalse(bytes.toList().windowed(2).any { it[0] == 0xC3.toByte() && it[1] == 0xA9.toByte() }, "no UTF-8 é")
        // The export is a valid DXF for the import: the drawing line comes back in nm.
        val back = assertIs<DrawingImportResult.Imported>(DrawingImporter.import(bytes))
        assertTrue(back.document.entities.filterIsInstance<WireframeLine>().any { it.start == NmPoint(0, 0) && it.end == NmPoint(20_000_000, 5_000_000) })
    }
}
