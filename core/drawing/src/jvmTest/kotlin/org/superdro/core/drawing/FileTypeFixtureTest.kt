package org.superdro.core.drawing

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Random
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.drawing.codec.Gzip
import org.superdro.core.drawing.dxf.DxfImportSettings
import org.superdro.core.drawing.text.DxfCharset

/** The type test with the sample files, the wireframe files, the gzip reader, and the code page tables. */
class FileTypeFixtureTest {

    @Test
    fun dwgSvgPdfPngAndGcodeAreNotDrawings() {
        for (name in listOf("sample.dwg", "sample.svg", "sample.pdf", "sample.png", "sample.nc")) {
            val bytes = Fixtures.bytes("other/$name")
            assertEquals(DrawingFileType.Unrecognized, DrawingTypeTest.detect(bytes), name)
            val result = DrawingImporter.import(bytes)
            assertEquals(DrawingImportResult.NotADrawing("This file isn't a drawing SuperDRO can open."), result, name)
        }
    }

    @Test
    fun theDrawingFilesHaveTheirTypes() {
        for (name in Fixtures.TWINS) {
            assertEquals(DrawingFileType.AsciiDxf, DrawingTypeTest.detect(Fixtures.bytes("dxf/$name.dxf")), name)
            assertEquals(DrawingFileType.BinaryDxf, DrawingTypeTest.detect(Fixtures.bytes("dxf/$name.dxb")), name)
        }
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect(Fixtures.bytes("other/example_result.json")))
        assertEquals(DrawingFileType.Wireframe, DrawingTypeTest.detect(Fixtures.bytes("other/example_result.json.gz")))
    }

    @Test
    fun theExampleWireframeReadsAsJsonAndAsGzip() {
        val plain = DrawingImporter.import(Fixtures.bytes("other/example_result.json")) as DrawingImportResult.Imported
        val gzip = DrawingImporter.import(Fixtures.bytes("other/example_result.json.gz")) as DrawingImportResult.Imported
        assertEquals(plain.document, gzip.document)
        assertEquals(listOf("0", "HOLES"), plain.document.layers.map { layer -> layer.name })
        assertEquals(WireframeCircle(NmPoint(10_000_000, 10_000_000), 3_175_000, layer = 1), plain.document.entities[1])
    }

    @Test
    fun aCutterGeometryCanHoldBase64GzipData() {
        val base64 = java.util.Base64.getEncoder().encodeToString(Fixtures.bytes("other/example_result.json.gz"))
        val decoded = org.superdro.core.drawing.tool.ToolGeometryCodec.decode("{\"v\":1,\"drawnIn\":25.4,\"document\":\"$base64\"}")
        assertEquals(emptyList(), decoded.problems)
        assertEquals(25.4, decoded.geometry!!.drawnIn)
        assertEquals(2, decoded.geometry.shapes.size)
    }

    @Test
    fun aGzipFileOfOtherDataGivesAProblemList() {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { zip -> zip.write("  0\nSECTION\n".toByteArray()) } }.toByteArray()
        val result = DrawingImporter.import(bytes) as DrawingImportResult.NotImported
        assertTrue(result.problems.single().message.startsWith("not valid JSON"))
    }

    @Test
    fun anEmptyDrawingHasTheNoShapesText() {
        val text = "  0\nSECTION\n  2\nENTITIES\n  0\nTEXT\n  8\n0\n  1\nA\n  0\nENDSEC\n  0\nEOF\n"
        val result = DrawingImporter.import(text.toByteArray()) as DrawingImportResult.NoShapes
        assertEquals("SuperDRO didn't find any lines, arcs or curves to import in this drawing.", result.message)
        assertEquals(listOf("DXF \$INSUNITS not set and no unit assumption supplied; defaulting to mm", "Dropped 1 TEXT entities (not geometry)"), result.warnings.map { warning -> warning.message })
        assertEquals(DrawingImportResult.TooLarge("This drawing is too large for SuperDRO (over 2 MB)."), DrawingImporter.import(ByteArray(2_162_689)))
    }

    @Test
    fun aLargerSourceLimitOfTheSettingsPermitsALargerFile() {
        // Each limit is a setting. A source limit of 3 000 000 bytes lets a file of 2 500 000 bytes
        // through the size test; the content test then refuses the zero bytes as no drawing.
        val settings = DxfImportSettings(limits = DxfLimits(maxSourceBytes = 3_000_000L))
        val result = DrawingImporter.import(ByteArray(2_500_000), settings)
        assertTrue(result !is DrawingImportResult.TooLarge, "the result was $result")
        assertEquals(DrawingImportResult.TooLarge(), DrawingImporter.import(ByteArray(3_000_001), settings))
    }

    @Test
    fun theGzipReaderGivesTheBytesOfJavaZip() {
        val random = Random(42)
        val samples = listOf(
            ByteArray(0),
            Fixtures.bytes("dxf/entities_r2000_mm.dxf"),
            ByteArray(70_000).also { bytes -> random.nextBytes(bytes) },
            ByteArray(200_000) { index -> (index % 7 + 'a'.code).toByte() },
        )
        for (sample in samples) {
            for (level in listOf(Deflater.NO_COMPRESSION, Deflater.BEST_SPEED, Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION)) {
                val out = ByteArrayOutputStream()
                object : GZIPOutputStream(out) {
                    init {
                        def.setLevel(level)
                    }
                }.use { zip -> zip.write(sample) }
                assertTrue(Gzip.decompress(out.toByteArray()).contentEquals(sample), "size ${sample.size}, level $level")
            }
        }
    }

    @Test
    fun theDoubleByteTablesDecodeTheTextsOfJava() {
        val texts = mapOf(
            "ANSI_932" to ("windows-31j" to "図面の寸法と穴の位置、外形線 ｶﾀｶﾅ ABC 123"),
            "ANSI_936" to ("GBK" to "图纸尺寸和孔的位置，外形线 ABC 123"),
            "ANSI_949" to ("x-windows-949" to "도면 치수와 구멍 위치, 외형선 ABC 123"),
            "ANSI_950" to ("x-windows-950" to "圖紙尺寸和孔的位置，外形線 ABC 123"),
            "ANSI_1361" to ("x-Johab" to "도면 치수와 구멍 위치 ABC 123"),
        )
        for ((dxfName, pair) in texts) {
            val (javaName, text) = pair
            val bytes = text.toByteArray(Charset.forName(javaName))
            assertEquals(text, DxfCharset.forCodePage(dxfName)!!.decode(bytes), dxfName)
        }
    }

    @Test
    fun theCodePageTablesAgreeWithTheJavaCharsets() {
        val javaNames = mapOf(
            "ANSI_874" to "x-windows-874", "ANSI_1250" to "windows-1250", "ANSI_1251" to "windows-1251", "ANSI_1252" to "windows-1252",
            "ANSI_1253" to "windows-1253", "ANSI_1254" to "windows-1254", "ANSI_1255" to "windows-1255", "ANSI_1256" to "windows-1256",
            "ANSI_1257" to "windows-1257", "ANSI_1258" to "windows-1258", "DOS437" to "IBM437", "DOS850" to "IBM850", "DOS852" to "IBM852",
            "DOS855" to "IBM855", "DOS857" to "IBM857", "DOS866" to "IBM866", "ISO8859-1" to "ISO-8859-1", "ISO8859-15" to "ISO-8859-15",
        )
        val bytes = ByteArray(128) { index -> (index + 0x80).toByte() }
        for ((dxfName, javaName) in javaNames) {
            val ours = DxfCharset.forCodePage(dxfName)!!.decode(bytes)
            val theirs = String(bytes, Charset.forName(javaName))
            for (index in 0 until 128) {
                // Java maps some undefined bytes to C1 controls or U+FFFD; the tables give U+FFFD.
                if (ours[index] == '�') continue
                assertEquals(theirs[index], ours[index], "$dxfName byte ${(index + 0x80).toString(16)}")
            }
        }
    }
}
