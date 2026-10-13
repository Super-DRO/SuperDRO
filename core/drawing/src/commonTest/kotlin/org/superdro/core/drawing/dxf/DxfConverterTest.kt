package org.superdro.core.drawing.dxf

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.drawing.ConvertResult
import org.superdro.core.drawing.ConvertStatus
import org.superdro.core.drawing.DrawingProblemCodes
import org.superdro.core.drawing.DxfLimits
import org.superdro.core.drawing.NmPoint
import org.superdro.core.drawing.ProblemSeverity
import org.superdro.core.drawing.WireframeArc
import org.superdro.core.drawing.WireframeBezier
import org.superdro.core.drawing.WireframeCircle
import org.superdro.core.drawing.WireframeDocument
import org.superdro.core.drawing.WireframeLayer
import org.superdro.core.drawing.WireframeLine
import org.superdro.core.drawing.WireframePolyline
import org.superdro.core.drawing.dxf.TestDxf.file
import org.superdro.core.drawing.dxf.TestDxf.layer
import org.superdro.core.drawing.dxf.TestDxf.line
import org.superdro.core.util.Clock

class DxfConverterTest {

    private fun convert(bytes: ByteArray, settings: DxfImportSettings = DxfImportSettings(), clock: Clock = Clock { 0L }): ConvertResult =
        DxfConverter.convert(bytes, settings, clock, TestDxf.NOW)

    private fun document(bytes: ByteArray, settings: DxfImportSettings = DxfImportSettings()): WireframeDocument {
        val result = convert(bytes, settings)
        assertEquals(ConvertStatus.Ready, result.status, result.errors.toString())
        return result.wireframe!!
    }

    private fun failure(bytes: ByteArray, settings: DxfImportSettings = DxfImportSettings(), clock: Clock = Clock { 0L }): Pair<String, String> {
        val result = convert(bytes, settings, clock)
        assertEquals(ConvertStatus.Failed, result.status)
        return result.errors.single().code to result.errors.single().message
    }

    private fun units(code: String?, measurement: String? = null) = buildList {
        if (code != null) addAll(listOf("9|\$INSUNITS", "70|$code"))
        if (measurement != null) addAll(listOf("9|\$MEASUREMENT", "70|$measurement"))
    }

    @Test
    fun theUnitsGiveIntegerNanometers() {
        val expected = mapOf("1" to 25_400_000L, "2" to 304_800_000L, "4" to 1_000_000L, "5" to 10_000_000L, "6" to 1_000_000_000L, "9" to 25_400L, "13" to 1000L)
        for ((code, nm) in expected) {
            val document = document(file(line(1.0, 0.0, 0.0, 2.0), header = units(code)))
            assertEquals(WireframeLine(NmPoint(nm, 0), NmPoint(0, 2 * nm)), document.entities.single(), "INSUNITS $code")
        }
    }

    @Test
    fun noUnitGivesMmAndTheWarning() {
        // 18 (astronomical unit) is a code that SuperDRO does not use.
        for (header in listOf(units(null), units("0"), units("18"))) {
            val result = convert(file(line(1.0, 0.0, 0.0, 0.0), header = header))
            assertEquals(1_000_000L, (result.wireframe!!.entities.single() as WireframeLine).start.x)
            val warning = result.warnings.single()
            assertEquals(DrawingProblemCodes.UNITS_ASSUMED, warning.code)
            assertEquals(ProblemSeverity.Warning, warning.severity)
            assertEquals("DXF \$INSUNITS not set and no unit assumption supplied; defaulting to mm", warning.message)
        }
    }

    @Test
    fun measurementZeroGivesInch() {
        val result = convert(file(line(1.0, 0.0, 0.0, 0.0), header = units(null, measurement = "0")))
        assertEquals(25_400_000L, (result.wireframe!!.entities.single() as WireframeLine).start.x)
        assertEquals(
            "DXF \$INSUNITS not set and no unit assumption supplied; \$MEASUREMENT is 0 (imperial), defaulting to inch",
            result.warnings.single().message,
        )
    }

    @Test
    fun aUnitAssumptionGivesNoWarning() {
        val result = convert(file(line(1.0, 0.0, 0.0, 0.0), header = units(null)), DxfImportSettings(assumedUnit = DrawingUnit.Inch))
        assertEquals(25_400_000L, (result.wireframe!!.entities.single() as WireframeLine).start.x)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun arcsGiveRadiansAndAZeroSweepGivesACircle() {
        val entities = listOf(
            "0|ARC", "8|0", "10|1.0", "20|2.0", "30|0.0", "40|3.0", "50|30.0", "51|120.0",
            "0|ARC", "8|0", "10|1.0", "20|2.0", "30|0.0", "40|3.0", "50|45.0", "51|45.0",
            "0|ARC", "8|0", "10|0.0", "20|0.0", "30|0.0", "40|1.0", "50|350.0", "51|10.0",
        )
        val shapes = document(file(entities)).entities
        assertEquals(WireframeArc(NmPoint(1_000_000, 2_000_000), 3_000_000, 30 * PI / 180, 90 * PI / 180), shapes[0])
        assertEquals(WireframeCircle(NmPoint(1_000_000, 2_000_000), 3_000_000), shapes[1])
        val wrap = shapes[2] as WireframeArc
        assertEquals(350 * PI / 180, wrap.start, 1e-12)
        assertEquals(20 * PI / 180, wrap.sweep, 1e-12)
    }

    @Test
    fun theExtrusionMinusZMirrorsArcsCirclesAndBulges() {
        val entities = listOf(
            "0|ARC", "8|0", "10|-40.0", "20|15.0", "30|0.0", "40|4.0", "50|10.0", "51|100.0", "210|0.0", "220|0.0", "230|-1.0",
            "0|CIRCLE", "8|0", "10|5.0", "20|6.0", "30|0.0", "40|1.0", "210|0.0", "220|0.0", "230|-1.0",
            "0|LWPOLYLINE", "8|0", "90|2", "70|0", "10|0.0", "20|0.0", "42|0.5", "10|10.0", "20|0.0", "210|0.0", "220|0.0", "230|-1.0",
        )
        val shapes = document(file(entities)).entities
        val arc = shapes[0] as WireframeArc
        assertEquals(NmPoint(40_000_000, 15_000_000), arc.center)
        assertEquals(80 * PI / 180, arc.start, 1e-12)
        assertEquals(PI / 2, arc.sweep, 1e-12)
        assertEquals(WireframeCircle(NmPoint(-5_000_000, 6_000_000), 1_000_000), shapes[1])
        assertEquals(WireframePolyline(listOf(NmPoint(0, 0), NmPoint(-10_000_000, 0)), false, mapOf(0 to -0.5)), shapes[2])
    }

    @Test
    fun polylinesKeepTheirBulgesAndAnOpenPolylineLosesTheLastOne() {
        val entities = listOf(
            "0|LWPOLYLINE", "8|0", "90|3", "70|1", "10|0.0", "20|0.0", "42|0.25", "10|10.0", "20|0.0", "10|10.0", "20|10.0", "42|-1.0",
            "0|LWPOLYLINE", "8|0", "90|2", "70|0", "10|0.0", "20|0.0", "42|0.25", "10|10.0", "20|0.0", "42|0.75",
            "0|LWPOLYLINE", "8|0", "90|1", "70|0", "10|0.0", "20|0.0",
        )
        val result = convert(file(entities))
        val shapes = result.wireframe!!.entities
        assertEquals(
            WireframePolyline(listOf(NmPoint(0, 0), NmPoint(10_000_000, 0), NmPoint(10_000_000, 10_000_000)), true, mapOf(0 to 0.25, 2 to -1.0)),
            shapes[0],
        )
        assertEquals(WireframePolyline(listOf(NmPoint(0, 0), NmPoint(10_000_000, 0)), false, mapOf(0 to 0.25)), shapes[1])
        assertEquals(2, shapes.size)
        assertEquals("Dropped 1 polylines with fewer than 2 points", result.warnings.single { warning -> warning.code == DrawingProblemCodes.INVALID_GEOMETRY }.message)
    }

    @Test
    fun aCircleWithNoRadiusGoesAway() {
        val result = convert(file(listOf("0|CIRCLE", "8|0", "10|0.0", "20|0.0", "40|0.0") + line(0.0, 0.0, 1.0, 0.0)))
        assertEquals(1, result.wireframe!!.entities.size)
        assertEquals("Dropped 1 CIRCLE with a radius of 0 or less", result.warnings.single().message)
    }

    @Test
    fun aSplineWithFourControlPointsIsOneExactBezierSegment() {
        val entities = listOf(
            "0|SPLINE", "8|0", "70|8", "71|3", "72|8", "73|4", "74|0",
            "40|0.0", "40|0.0", "40|0.0", "40|0.0", "40|1.0", "40|1.0", "40|1.0", "40|1.0",
            "10|0.0", "20|0.0", "30|0.0", "10|1.0", "20|2.0", "30|0.0", "10|3.0", "20|2.0", "30|0.0", "10|4.0", "20|0.0", "30|0.0",
        )
        val bezier = document(file(entities)).entities.single() as WireframeBezier
        assertEquals(
            listOf(NmPoint(0, 0), NmPoint(1_000_000, 2_000_000), NmPoint(3_000_000, 2_000_000), NmPoint(4_000_000, 0)),
            bezier.points,
        )
    }

    @Test
    fun aFullEllipseIsAClosedBezierChainWithinTheTolerance() {
        val entities = listOf("0|ELLIPSE", "8|0", "10|0.0", "20|0.0", "30|0.0", "11|20.0", "21|0.0", "31|0.0", "40|0.5", "41|0.0", "42|${2 * PI}")
        val bezier = document(file(entities)).entities.single() as WireframeBezier
        assertTrue(bezier.closed)
        assertEquals(bezier.points.first(), bezier.points.last())
        assertEquals(NmPoint(20_000_000, 0), bezier.points.first())
        assertTrue(bezier.segmentCount >= 4)
    }

    @Test
    fun theLineTypesGiveTheStrokeStyles() {
        for (name in listOf("CENTER", "CENTER2", "CENTERX2", "PHANTOM", "PHANTOM2", "PHANTOMX2")) assertEquals(4, DxfConverter.styleOfLineType(name))
        for (name in listOf("HIDDEN", "HIDDEN2", "HIDDENX2", "DASHED", "DASHED2", "DASHEDX2")) assertEquals(5, DxfConverter.styleOfLineType(name))
        for (name in listOf("Continuous", "DOT", "BORDER", "")) assertEquals(1, DxfConverter.styleOfLineType(name))
    }

    @Test
    fun theLayersComeFromTheLayerTable() {
        val layers = layer("B", lineType = "CENTER") + layer("A", color = -3) + layer("F", flags = 1, lineType = "HIDDEN") + layer("UNUSED")
        val entities = line(0.0, 0.0, 1.0, 0.0, "A") + line(0.0, 0.0, 1.0, 0.0, "B") + line(0.0, 0.0, 1.0, 0.0, "F") +
            line(0.0, 0.0, 1.0, 0.0, "NEW") + line(0.0, 0.0, 1.0, 0.0, "B", extra = listOf("6|DASHED")) +
            line(0.0, 0.0, 1.0, 0.0, "A", extra = listOf("6|ByLayer"))
        val document = document(file(entities, layers = layers))
        // The table order, then the layers that the table does not have. HIDDEN is not in the LTYPE table.
        assertEquals(
            listOf(WireframeLayer("B", true, 4), WireframeLayer("A", false, 1), WireframeLayer("F", false, 1), WireframeLayer("NEW", true, 1)),
            document.layers,
        )
        assertEquals(listOf(1, 0, 2, 3, 0, 1), document.entities.map { entity -> entity.layer })
        assertEquals(listOf(null, null, null, null, 5, null), document.entities.map { entity -> entity.style })
    }

    @Test
    fun paperSpaceEntitiesAndOtherTypesGoAwayWithWarnings() {
        val entities = line(0.0, 0.0, 1.0, 0.0, extra = listOf("67|1")) + line(0.0, 0.0, 2.0, 0.0) +
            listOf("0|TEXT", "8|0", "1|A", "0|TEXT", "8|0", "1|B", "0|XLINE", "8|0", "0|ATTDEF", "8|0", "0|3DFACE", "8|0")
        val result = convert(file(entities))
        assertEquals(listOf(WireframeLine(NmPoint(0, 0), NmPoint(2_000_000, 0))), result.wireframe!!.entities)
        assertEquals(
            listOf(
                "Dropped 2 TEXT entities (not geometry)",
                "Dropped 1 3DFACE entities (not geometry)",
                "Dropped 1 XLINE entities (not supported)",
            ),
            result.warnings.map { warning -> warning.message },
        )
    }

    @Test
    fun blocksExpandWithScaleRotationAndArrays() {
        val blocks = listOf("0|BLOCK", "8|0", "2|B", "70|0", "10|1.0", "20|1.0", "30|0.0") + line(1.0, 1.0, 2.0, 1.0) + listOf("0|ENDBLK", "8|0")
        val entities = listOf(
            "0|INSERT", "8|PARTS", "2|B", "10|10.0", "20|0.0", "30|0.0", "41|2.0", "42|2.0", "50|90.0",
            "0|INSERT", "8|0", "2|B", "10|0.0", "20|0.0", "30|0.0", "70|2", "71|2", "44|5.0", "45|3.0",
            "0|INSERT", "8|0", "2|NOPE", "10|0.0", "20|0.0",
        )
        val result = convert(file(entities, blocks = blocks))
        val lines = result.wireframe!!.entities.map { entity -> entity as WireframeLine }
        assertEquals(WireframeLine(NmPoint(10_000_000, 0), NmPoint(10_000_000, 2_000_000), layer = 0), lines[0])
        assertEquals(listOf(NmPoint(0, 0), NmPoint(5_000_000, 0), NmPoint(0, 3_000_000), NmPoint(5_000_000, 3_000_000)), lines.drop(1).map { line -> line.start })
        assertEquals("PARTS", result.wireframe.layers[0].name)
        assertEquals("INSERT names blocks that the file does not have: NOPE", result.warnings.single().message)
    }

    @Test
    fun aNonUniformScaleMakesACircleABezierChain() {
        val blocks = listOf("0|BLOCK", "8|0", "2|C", "70|0", "10|0.0", "20|0.0", "0|CIRCLE", "8|0", "10|0.0", "20|0.0", "40|1.0", "0|ENDBLK", "8|0")
        val entities = listOf("0|INSERT", "8|0", "2|C", "10|0.0", "20|0.0", "41|2.0", "42|1.0")
        val bezier = document(file(entities, blocks = blocks)).entities.single() as WireframeBezier
        assertTrue(bezier.closed)
        assertEquals(NmPoint(2_000_000, 0), bezier.points.first())
        assertTrue(bezier.points.any { point -> point == NmPoint(0, 1_000_000) })
    }

    @Test
    fun theLimitsGiveTheirCodes() {
        val three = file(line(0.0, 0.0, 1.0, 0.0) + line(0.0, 0.0, 2.0, 0.0) + line(0.0, 0.0, 3.0, 0.0))
        assertEquals(
            DrawingProblemCodes.ENTITY_LIMIT_EXCEEDED to "3 entities exceeds the 2 cap",
            failure(three, DxfImportSettings(DxfLimits(maxEntities = 2))),
        )
        assertEquals(
            DrawingProblemCodes.SOURCE_TOO_LARGE to "the file has ${three.size} bytes, more than the limit of 100 bytes",
            failure(three, DxfImportSettings(DxfLimits(maxSourceBytes = 100))),
        )
        assertEquals(
            DrawingProblemCodes.INVALID_CURVE_TOLERANCE to "the curve tolerance must be more than 0 nm, got 0 nm",
            failure(three, DxfImportSettings(DxfLimits(curveToleranceNm = 0))),
        )
        val polyline = file(listOf("0|LWPOLYLINE", "8|0", "90|3", "70|0", "10|0.0", "20|0.0", "10|1.0", "20|0.0", "10|2.0", "20|0.0"))
        assertEquals(
            DrawingProblemCodes.POLYLINE_POINTS_EXCEEDED to "a polyline with 3 points exceeds the 2 cap",
            failure(polyline, DxfImportSettings(DxfLimits(maxPolylinePoints = 2))),
        )
        val warned = convert(polyline, DxfImportSettings(DxfLimits(polylineWarningPoints = 2)))
        assertEquals(DrawingProblemCodes.POLYLINE_POINTS_WARNING, warned.warnings.single().code)
        assertEquals("1 polylines have more than 2 points (the largest has 3)", warned.warnings.single().message)
        val ellipse = file(listOf("0|ELLIPSE", "8|0", "10|0.0", "20|0.0", "11|20.0", "21|0.0", "40|0.5", "41|0.0", "42|${2 * PI}"))
        assertEquals(
            DrawingProblemCodes.BEZIER_SEGMENTS_EXCEEDED to "more than 3 Bézier segments in the document",
            failure(ellipse, DxfImportSettings(DxfLimits(maxBezierSegments = 3))),
        )
        val tooComplex = convert(ellipse, DxfImportSettings(DxfLimits(maxEllipseReferencePoints = 10)))
        assertEquals(DrawingProblemCodes.CURVE_TOO_COMPLEX, tooComplex.warnings.single().code)
        assertEquals("Dropped 1 ELLIPSE entities that need more than 10 reference points (check units)", tooComplex.warnings.single().message)
    }

    @Test
    fun coordinatesMustFitAndBeFinite() {
        val huge = file(line(1.0e13, 0.0, 0.0, 0.0), header = units("6"))
        assertEquals(DrawingProblemCodes.COORDINATE_OVERFLOW, failure(huge).first)
        val infinite = file(line(0.0, 0.0, 0.0, 0.0)).decodeToString().replaceFirst(" 10\n0.0", " 10\n1e400").encodeToByteArray()
        assertEquals(DrawingProblemCodes.NON_FINITE_COORDINATE, failure(infinite).first)
    }

    @Test
    fun theParseTimeLimitGivesItsCode() {
        var now = 0L
        val clock = Clock { now.also { now += 3000 } }
        val (code, message) = failure(file(line(0.0, 0.0, 1.0, 0.0)), clock = clock)
        assertEquals(DrawingProblemCodes.PARSE_TIME_EXCEEDED, code)
        assertEquals("the conversion took more than the 5000 ms limit", message)
    }

    @Test
    fun theNestingLimitGivesItsCode() {
        val blocks = listOf("0|BLOCK", "8|0", "2|A", "10|0.0", "20|0.0", "0|INSERT", "8|0", "2|B", "10|0.0", "20|0.0", "0|ENDBLK", "8|0") +
            listOf("0|BLOCK", "8|0", "2|B", "10|0.0", "20|0.0") + line(0.0, 0.0, 1.0, 0.0) + listOf("0|ENDBLK", "8|0")
        val entities = listOf("0|INSERT", "8|0", "2|A", "10|0.0", "20|0.0")
        assertEquals(1, document(file(entities, blocks = blocks), DxfImportSettings(DxfLimits(maxBlockLevels = 2))).entities.size)
        assertEquals(
            DrawingProblemCodes.NESTING_DEPTH_EXCEEDED to "the block B is nested more than 1 levels deep",
            failure(file(entities, blocks = blocks), DxfImportSettings(DxfLimits(maxBlockLevels = 1))),
        )
        // A block that inserts itself.
        val loop = listOf("0|BLOCK", "8|0", "2|L", "10|0.0", "20|0.0", "0|INSERT", "8|0", "2|L", "10|1.0", "20|0.0", "0|ENDBLK", "8|0")
        assertEquals(DrawingProblemCodes.NESTING_DEPTH_EXCEEDED, failure(file(listOf("0|INSERT", "8|0", "2|L", "10|0.0", "20|0.0"), blocks = loop)).first)
    }

    @Test
    fun structureErrorsAreParseErrors() {
        val cases = mapOf(
            file(line(0.0, 0.0, 1.0, 0.0, extra = listOf("5|1A")) + line(0.0, 0.0, 1.0, 0.0, extra = listOf("5|1a"))) to "duplicate entity handle 1a (LINE)",
            file(line(0.0, 0.0, 1.0, 0.0, extra = listOf("5|0"))) to "LINE has the handle 0",
            file(emptyList(), blocks = listOf("0|ENDBLK", "8|0")) to "ENDBLK without a previous BLOCK",
            file(listOf("0|POLYLINE", "8|0", "66|1", "0|VERTEX", "8|0", "10|0.0", "20|0.0", "0|LINE", "8|0")) to "POLYLINE without SEQEND in ENTITIES",
            file(line(0.0, 0.0, 1.0, 0.0, extra = listOf("102|{ACAD_REACTORS", "330|1F"))) to "bad APP DATA structure in LINE: a group has no end",
            file(line(0.0, 0.0, 1.0, 0.0) + listOf("1000|x")) to "bad XDATA structure in LINE: the XDATA does not start with 1001",
            file(line(0.0, 0.0, 1.0, 0.0) + listOf("1001|APP", "1002|{", "1000|x")) to "bad XDATA structure in LINE: a 1002 group has no end",
        )
        for ((bytes, message) in cases) assertEquals(DrawingProblemCodes.PARSE_ERROR to message, failure(bytes))
    }

    @Test
    fun appDataAndXdataStayOutOfTheGeometry() {
        val entities = listOf("0|LINE", "5|2A", "102|{ACAD_REACTORS", "330|99", "102|}", "330|1F", "8|0", "10|1.0", "20|0.0", "11|2.0", "21|0.0", "1001|APP", "1010|5.0", "1020|5.0")
        assertEquals(WireframeLine(NmPoint(1_000_000, 0), NmPoint(2_000_000, 0)), document(file(entities)).entities.single())
    }

    @Test
    fun otherContentIsNotADxfFile() {
        assertEquals(DrawingProblemCodes.UNSUPPORTED_FORMAT to "not a DXF file", failure("%PDF-1.4".encodeToByteArray()))
    }

    @Test
    fun theDocumentHasTheTimeAndTheExtents() {
        val document = document(file(line(-1.0, 2.0, 3.0, -4.0) + listOf("0|CIRCLE", "8|0", "10|10.0", "20|10.0", "40|1.0")))
        assertEquals("2026-10-12T08:30:00Z", document.createdAt)
        assertEquals(NmPoint(-1_000_000, -4_000_000), document.extents!!.min)
        assertEquals(NmPoint(11_000_000, 11_000_000), document.extents.max)
        assertNull(document.entities.first().style)
    }
}
