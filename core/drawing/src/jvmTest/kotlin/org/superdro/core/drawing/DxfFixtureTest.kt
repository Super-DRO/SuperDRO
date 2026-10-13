package org.superdro.core.drawing

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.drawing.dxf.DxfConverter
import org.superdro.core.drawing.dxf.DxfEncoding
import org.superdro.core.drawing.dxf.DxfImportSettings
import org.superdro.core.drawing.dxf.DxfTokenizer
import org.superdro.core.model.DrawingFormat

/**
 * The fixtures of `fixtures/make_fixtures.py`: each ASCII DXF file and its binary twin (made with
 * ezdxf) give the same wireframe.
 */
class DxfFixtureTest {

    private fun convert(path: String, settings: DxfImportSettings = DxfImportSettings()): ConvertResult =
        DxfConverter.convert(Fixtures.bytes(path), settings, now = Fixtures.NOW)

    private fun ready(path: String): ConvertResult {
        val result = convert(path)
        assertEquals(ConvertStatus.Ready, result.status, "$path: ${result.errors}")
        return result
    }

    private fun mm(value: Double): Long = (value * 1_000_000).toLong()

    private fun inch(value: Double): Long = kotlin.math.floor(value * 25_400_000 + 0.5).toLong()

    @Test
    fun eachAsciiFixtureAndItsBinaryTwinGiveTheSameWireframe() {
        for (name in Fixtures.TWINS) {
            val ascii = convert("dxf/$name.dxf")
            val binary = convert("dxf/$name.dxb")
            assertEquals(ascii, binary, name)
            assertEquals(WireframeWriter.writeResult(ascii), WireframeWriter.writeResult(binary), name)
        }
    }

    @Test
    fun theTwoTokenizersGiveTheSameTagStream() {
        for (name in Fixtures.TWINS) {
            val ascii = DxfTokenizer.tokenize(Fixtures.bytes("dxf/$name.dxf"))
            val binary = DxfTokenizer.tokenize(Fixtures.bytes("dxf/$name.dxb"))
            assertEquals(ascii.tags, binary.tags, name)
            assertEquals(DxfEncoding.Ascii, ascii.encoding)
            val expected = if (name == "r12_mm") DxfEncoding.BinaryOneByteCodes else DxfEncoding.BinaryTwoByteCodes
            assertEquals(expected, binary.encoding, name)
        }
    }

    @Test
    fun theImporterDetectsTheTwoFormats() {
        val ascii = DrawingImporter.import(Fixtures.bytes("dxf/r12_mm.dxf"), now = Fixtures.NOW) as DrawingImportResult.Imported
        val binary = DrawingImporter.import(Fixtures.bytes("dxf/r12_mm.dxb"), now = Fixtures.NOW) as DrawingImportResult.Imported
        assertEquals(DrawingFormat.AsciiDxf, ascii.format)
        assertEquals(DrawingFormat.BinaryDxf, binary.format)
        assertEquals(ascii.document, binary.document)
    }

    @Test
    fun theEntitiesFixtureGivesEachEntityType() {
        val result = ready("dxf/entities_r2000_mm.dxf")
        val document = result.wireframe!!
        assertEquals(
            listOf(
                WireframeLayer("OUTLINE", true, 1),
                WireframeLayer("HOLES", true, 1),
                WireframeLayer("CENTERLINES", true, 4),
                // The LTYPE table of the fixture has no HIDDEN: the layer gets Continuous.
                WireframeLayer("HIDDEN_EDGES", true, 1),
                WireframeLayer("DASHED_EDGES", true, 5),
                WireframeLayer("OFF_LAYER", false, 1),
                WireframeLayer("FROZEN", false, 1),
            ),
            document.layers,
        )
        val e = document.entities
        assertEquals(20, e.size)
        assertEquals(WireframeLine(NmPoint(0, 0), NmPoint(mm(50.0), 0), layer = 0), e[0])
        assertEquals(WireframeLine(NmPoint(0, 0), NmPoint(0, mm(30.0)), layer = 0, style = 5), e[1])
        assertEquals(WireframeLine(NmPoint(mm(25.0), mm(-5.0)), NmPoint(mm(25.0), mm(35.0)), layer = 2), e[2])
        assertEquals(WireframeCircle(NmPoint(mm(10.0), mm(10.0)), 3_175_000, layer = 1), e[5])
        assertEquals(WireframeArc(NmPoint(mm(25.0), mm(15.0)), mm(5.0), 0.0, PI / 2, layer = 0), e[6])
        val wrap = e[7] as WireframeArc
        assertEquals(270 * PI / 180, wrap.start, 1e-12)
        assertEquals(135 * PI / 180, wrap.sweep, 1e-12)
        // The extrusion (0, 0, −1): center (−40, 15) in the OCS is (40, 15); 10°…100° becomes 80°…170°.
        val mirrored = e[8] as WireframeArc
        assertEquals(NmPoint(mm(40.0), mm(15.0)), mirrored.center)
        assertEquals(80 * PI / 180, mirrored.start, 1e-12)
        assertEquals(PI / 2, mirrored.sweep, 1e-12)
        assertEquals(WireframePoint(NmPoint(mm(5.0), mm(5.0)), layer = 0), e[9])
        assertEquals(
            WireframePolyline(listOf(NmPoint(0, mm(40.0)), NmPoint(mm(50.0), mm(40.0)), NmPoint(mm(60.0), mm(50.0)), NmPoint(0, mm(50.0))), true, mapOf(1 to 0.41421356237309503)),
            e[10],
        )
        // The open polyline loses the bulge of its last vertex.
        assertEquals(WireframePolyline(listOf(NmPoint(mm(70.0), 0), NmPoint(mm(80.0), 0), NmPoint(mm(80.0), mm(10.0))), false, mapOf(0 to -0.5)), e[11])
        assertEquals(WireframePolyline(listOf(NmPoint(0, mm(60.0)), NmPoint(mm(20.0), mm(60.0)), NmPoint(mm(20.0), mm(70.0))), true, mapOf(0 to 0.25)), e[12])
        assertEquals(WireframePolyline(listOf(NmPoint(mm(30.0), mm(60.0)), NmPoint(mm(40.0), mm(65.0)), NmPoint(mm(50.0), mm(60.0))), false), e[13])
        val ellipse = e[14] as WireframeBezier
        assertTrue(ellipse.closed)
        assertEquals(NmPoint(mm(40.0), mm(40.0)), ellipse.points.first())
        val ellipseArc = e[15] as WireframeBezier
        assertEquals(NmPoint(mm(65.0), mm(45.0)), ellipseArc.points.first())
        assertEquals(NmPoint(mm(58.0), mm(42.0)), ellipseArc.points.last())
        // The clamped cubic spline with 5 control points: 2 exact Bézier segments.
        val spline = e[16] as WireframeBezier
        assertEquals(
            listOf(NmPoint(0, mm(80.0)), NmPoint(mm(10.0), mm(90.0)), NmPoint(mm(15.0), mm(85.0)), NmPoint(mm(20.0), mm(85.0)), NmPoint(mm(25.0), mm(85.0)), NmPoint(mm(30.0), mm(90.0)), NmPoint(mm(40.0), mm(80.0))),
            spline.points,
        )
        // The polyline boundary of the HATCH; the edge boundary goes away.
        assertEquals(
            WireframePolyline(listOf(NmPoint(mm(100.0), 0), NmPoint(mm(110.0), 0), NmPoint(mm(110.0), mm(10.0)), NmPoint(mm(100.0), mm(10.0))), true, mapOf(1 to 0.5)),
            e[17],
        )
        assertEquals(5, e[18].layer)
        assertEquals(6, e[19].layer)
        // The paper space line (0, 0)…(100, 100) is not in the result.
        assertTrue(e.none { entity -> entity is WireframeLine && entity.end == NmPoint(mm(100.0), mm(100.0)) })
        assertEquals(
            listOf(
                "Dropped 1 TEXT entities (not geometry)",
                "Dropped 1 MTEXT entities (not geometry)",
                "Dropped 1 DIMENSION entities (not geometry)",
                "Dropped 1 HATCH edge boundaries",
            ),
            result.warnings.map { warning -> warning.message },
        )
        assertEquals(WireframeExtents(NmPoint(0, mm(-5.0)), NmPoint(mm(112.5), mm(110.0))), document.extents)
        assertEquals("2026-10-12T08:30:00Z", document.createdAt)
    }

    @Test
    fun theBlocksFixtureExpandsTheNestedInserts() {
        val document = ready("dxf/blocks_r2000_inch.dxb").wireframe!!
        assertEquals(listOf(WireframeLayer("0", true, 1), WireframeLayer("PARTS", true, 4), WireframeLayer("BLOCKLINES", true, 1)), document.layers)
        val e = document.entities
        assertEquals(26, e.size)
        // PLATE in ASSEMBLY (scale 2, rotation 30°) at (10, 10) in; the entities on layer 0 take the layer PARTS.
        val plate = e[0] as WireframePolyline
        assertEquals(1, plate.layer)
        assertEquals(listOf(NmPoint(inch(10.0), inch(10.0)), NmPoint(429_976_362, inch(14.0)), NmPoint(379_176_362, 443_588_181), NmPoint(inch(8.0), 341_988_181)), plate.points)
        // The first BOLT of PLATE: (10, 10) + R(30°)·(1, 1) in, radius 2 × 0.125 in.
        assertEquals(WireframeCircle(NmPoint(263_297_045, 288_697_045), inch(0.25), layer = 1), e[1])
        // BYBLOCK takes the line type of the INSERT: CENTER from the layer PARTS.
        assertEquals(WireframeLine(NmPoint(252_298_523, 282_347_045), NmPoint(274_295_568, 295_047_045), layer = 2, style = 4), e[2])
        assertEquals(WireframeLine(NmPoint(inch(10.0), inch(10.0)), NmPoint(inch(11.0), inch(10.0)), layer = 1), e[9])
        // A non-uniform scale (2, 1) makes the circle a Bézier chain.
        val stretched = e[10] as WireframeBezier
        assertTrue(stretched.closed)
        assertEquals(NmPoint(inch(20.25), 0), stretched.points.first())
        assertEquals(WireframeLine(NmPoint(inch(19.5), 0), NmPoint(inch(20.5), 0), layer = 2), e[11])
        // The mirror (−1, 1) keeps the circle and turns the line.
        assertEquals(WireframeCircle(NmPoint(inch(25.0), 0), inch(0.125), layer = 0), e[12])
        assertEquals(WireframeLine(NmPoint(inch(25.25), 0), NmPoint(inch(24.75), 0), layer = 2), e[13])
        // The array of 2 rows (0.5 in) and 3 columns (1.0 in), with the line type DASHED2 of the INSERT.
        val centers = e.drop(14).filterIsInstance<WireframeCircle>().map { circle -> circle.center }
        assertEquals(
            listOf(0.0 to 30.0, 1.0 to 30.0, 2.0 to 30.0, 0.0 to 30.5, 1.0 to 30.5, 2.0 to 30.5).map { (x, y) -> NmPoint(inch(x), inch(y)) },
            centers,
        )
        assertTrue(e.drop(14).filterIsInstance<WireframeLine>().all { line -> line.style == 5 })
    }

    @Test
    fun theNestingFixturesGiveTheLevelLimit() {
        assertEquals(8, ready("dxf/nesting_8_levels.dxf").wireframe!!.entities.size)
        val failed = convert("dxf/nesting_9_levels.dxb")
        assertEquals(ConvertStatus.Failed, failed.status)
        assertEquals(DrawingProblem(DrawingProblemCodes.NESTING_DEPTH_EXCEEDED, "the block L9 is nested more than 8 levels deep"), failed.errors.single())
        val notImported = DrawingImporter.import(Fixtures.bytes("dxf/nesting_9_levels.dxf"), now = Fixtures.NOW) as DrawingImportResult.NotImported
        assertEquals(DrawingProblemCodes.NESTING_DEPTH_EXCEEDED, notImported.problems.single().code)
    }

    @Test
    fun theR12FixtureHasNoUnits() {
        val result = ready("dxf/r12_mm.dxb")
        assertEquals(DrawingProblemCodes.UNITS_ASSUMED, result.warnings.first().code)
        assertEquals("DXF \$INSUNITS not set and no unit assumption supplied; defaulting to mm", result.warnings.first().message)
        val e = result.wireframe!!.entities
        assertEquals(7, e.size)
        assertEquals(WireframeCircle(NmPoint(mm(30.0), mm(30.0)), mm(1.0), layer = 0), e[5])
        assertEquals(WireframeLine(NmPoint(29_292_893, 29_292_893), NmPoint(30_707_107, 30_707_107), layer = 0), e[6])
    }

    @Test
    fun theR2018FixtureHasUtf8NamesAndMeters() {
        val document = ready("dxf/r2018_utf8_m.dxb").wireframe!!
        assertEquals(listOf("Bohrungen_äöü", "Ø_holes", "Слой"), document.layers.map { layer -> layer.name })
        assertEquals(WireframeLine(NmPoint(0, 0), NmPoint(mm(50.0), 0), layer = 0), document.entities[0])
        assertEquals(WireframeCircle(NmPoint(mm(10.0), mm(10.0)), 3_175_000, layer = 1), document.entities[1])
    }

    @Test
    fun theCodePageFixturesHaveTheirNames() {
        for (path in listOf("dxf/codepage_1251_r2000.dxf", "dxf/codepage_1251_r2000.dxb")) {
            val document = ready(path).wireframe!!
            assertEquals(listOf(WireframeLayer("Отверстия")), document.layers)
            assertEquals(WireframeCircle(NmPoint(mm(10.0), mm(10.0)), mm(5.0), layer = 0), document.entities[0])
        }
        assertEquals("ANSI_1251", DxfTokenizer.tokenize(Fixtures.bytes("dxf/codepage_1251_r2000.dxf")).charset.name)
        for (path in listOf("dxf/codepage_932_r2000.dxf", "dxf/codepage_932_r2000.dxb")) {
            val document = ready(path).wireframe!!
            assertEquals(listOf(WireframeLayer("図面_穴")), document.layers)
            assertEquals(WireframeCircle(NmPoint(mm(10.0), mm(10.0)), mm(3.0), layer = 0), document.entities.single())
        }
    }

    @Test
    fun aSplineWith1001ControlPointsGoesAway() {
        val result = ready("dxf/spline_1001_points.dxf")
        assertEquals(1, result.wireframe!!.entities.size)
        assertEquals(DrawingProblemCodes.CURVE_TOO_COMPLEX, result.warnings.single().code)
        assertEquals("Dropped 1 SPLINE entities with more than 1000 control points or fit points", result.warnings.single().message)
    }

    @Test
    fun theFitPointSplinePassesThroughItsFitPoints() {
        val chain = ready("dxf/fit_spline_r2000_mm.dxf").wireframe!!.entities.single() as WireframeBezier
        assertEquals(
            listOf(NmPoint(0, 0), NmPoint(mm(10.0), mm(15.0)), NmPoint(mm(25.0), mm(5.0)), NmPoint(mm(40.0), mm(20.0))),
            chain.points.filterIndexed { index, _ -> index % 3 == 0 },
        )
    }

    @Test
    fun theLimitsOfTheFixturesGiveTheirCodes() {
        fun code(path: String, limits: DxfLimits) = convert(path, DxfImportSettings(limits)).errors.single().code
        assertEquals(DrawingProblemCodes.SOURCE_TOO_LARGE, code("dxf/entities_r2000_mm.dxb", DxfLimits(maxSourceBytes = 10_000)))
        assertEquals(DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED, code("dxf/entities_r2000_mm.dxf", DxfLimits(maxEntities = 19)))
        assertEquals(DrawingProblemCodes.BEZIER_SEGMENTS_EXCEEDED, code("dxf/curves_r2000_mm.dxb", DxfLimits(maxBezierSegments = 10)))
        assertEquals(DrawingProblemCodes.NESTING_DEPTH_EXCEEDED, code("dxf/blocks_r2000_inch.dxf", DxfLimits(maxBlockLevels = 2)))
        assertEquals(DrawingProblemCodes.POLYLINE_POINTS_EXCEEDED, code("dxf/entities_r2000_mm.dxb", DxfLimits(maxPolylinePoints = 3)))
        val warned = convert("dxf/entities_r2000_mm.dxf", DxfImportSettings(DxfLimits(polylineWarningPoints = 3)))
        // The LWPOLYLINE and the HATCH boundary have 4 points.
        assertEquals("2 polylines have more than 3 points (the largest has 4)", warned.warnings.single { warning -> warning.code == DrawingProblemCodes.POLYLINE_POINTS_WARNING }.message)
        var now = 0L
        val slow = DxfConverter.convert(Fixtures.bytes("dxf/entities_r2000_mm.dxf"), clock = { now.also { now += 1000 } }, now = Fixtures.NOW)
        assertEquals(DrawingProblemCodes.PARSE_TIME_EXCEEDED, slow.errors.single().code)
        // The default limits: the default size limit is 2 000 000 bytes.
        val big = ByteArray(2_000_001).also { bytes -> Fixtures.bytes("dxf/r12_mm.dxf").copyInto(bytes) }
        assertEquals(DrawingProblemCodes.SOURCE_TOO_LARGE, DxfConverter.convert(big).errors.single().code)
    }

    @Test
    fun theDefaultEntityLimitIs10000() {
        val many = buildString {
            append("  0\nSECTION\n  2\nENTITIES\n")
            repeat(10_001) { index -> append("  0\nPOINT\n  8\n0\n 10\n$index.0\n 20\n0.0\n") }
            append("  0\nENDSEC\n  0\nEOF\n")
        }.encodeToByteArray()
        val failed = DrawingImporter.import(many, now = Fixtures.NOW) as DrawingImportResult.NotImported
        assertEquals(DrawingProblem(DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED, "10001 entities exceeds the 10000 cap"), failed.problems.first())
    }
}
