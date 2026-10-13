package org.superdro.core.drawing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireframeJsonTest {

    /** The example: a 50 mm × 30 mm rectangle with one 6.35 mm hole. */
    private val example = """
        {"schema":"touchdro.convert.result","v":"1","status":"ready","format":"dxf",
         "converterBuild":"local/dxf-converter","errors":[],"warnings":[],
         "wireframe":{"schema":"touchdro.wireframe","v":"3.0","createdAt":"2026-10-11T20:00:00Z",
          "axes":["X","Y"],"ext":{"min":[0,0],"max":[50000000,30000000]},
          "layers":[{"n":"0"},{"n":"HOLES","st":1}],
          "entities":[
           {"t":"P","l":0,"pts":[[0,0],[50000000,0],[50000000,30000000],[0,30000000]],"cl":1},
           {"t":"C","l":1,"c":[10000000,10000000],"r":3175000}]}}
    """.trimIndent()

    private val exampleDocument = WireframeDocument(
        version = "3.0",
        createdAt = "2026-10-11T20:00:00Z",
        extents = WireframeExtents(NmPoint(0, 0), NmPoint(50_000_000, 30_000_000)),
        layers = listOf(WireframeLayer("0"), WireframeLayer("HOLES", style = 1)),
        entities = listOf(
            WireframePolyline(listOf(NmPoint(0, 0), NmPoint(50_000_000, 0), NmPoint(50_000_000, 30_000_000), NmPoint(0, 30_000_000)), closed = true, layer = 0),
            WireframeCircle(NmPoint(10_000_000, 10_000_000), 3_175_000, layer = 1),
        ),
    )

    private fun problems(text: String): List<String> = WireframeReader.readText(text).problems.map { problem -> problem.message }

    private fun document(entities: String, layers: String = """[{"n":"0"}]""", extra: String = ""): String =
        """{"schema":"touchdro.wireframe","v":"3.0"$extra,"layers":$layers,"entities":$entities}"""

    @Test
    fun theWireframeExampleReads() {
        val read = WireframeReader.readText(example)
        assertEquals(emptyList(), read.problems)
        assertEquals(exampleDocument, read.document)
        assertEquals(ConvertStatus.Ready, read.result!!.status)
        assertEquals("local/dxf-converter", read.result.converterBuild)
    }

    @Test
    fun aBareWireframeDocumentReads() {
        val read = WireframeReader.readText(WireframeWriter.write(exampleDocument))
        assertEquals(exampleDocument, read.document)
    }

    @Test
    fun theWriterUsesTheKeyOrderOfTheFormat() {
        val text = WireframeWriter.write(exampleDocument)
        assertEquals(
            """{"schema":"touchdro.wireframe","v":"3.0","createdAt":"2026-10-11T20:00:00Z","axes":["X","Y"],""" +
                """"ext":{"min":[0,0],"max":[50000000,30000000]},"layers":[{"n":"0","v":1,"st":1},{"n":"HOLES","v":1,"st":1}],""" +
                """"entities":[{"t":"P","l":0,"pts":[[0,0],[50000000,0],[50000000,30000000],[0,30000000]],"cl":1},""" +
                """{"t":"C","l":1,"c":[10000000,10000000],"r":3175000}]}""",
            text,
        )
    }

    @Test
    fun allEntityTypesGoBothWays() {
        val document = WireframeDocument(
            createdAt = "2026-10-12T08:30:00Z",
            extents = WireframeExtents(NmPoint(-1, -2), NmPoint(3, 4)),
            layers = listOf(WireframeLayer("A", visible = false, style = 4), WireframeLayer("B", style = 0)),
            entities = listOf(
                WireframeLine(NmPoint(1, 2), NmPoint(3, 4), layer = 1, id = "x1", style = 2, group = "g"),
                WireframePoint(NmPoint(5, 6), name = "Datum A"),
                WireframeArc(NmPoint(0, 0), 100, 0.5, -1.25, layer = 1),
                WireframePolyline(listOf(NmPoint(0, 0), NmPoint(10, 0), NmPoint(10, 10)), closed = false, bulges = mapOf(1 to 0.41421356237309503)),
                WireframeBezier(listOf(NmPoint(0, 0), NmPoint(1, 1), NmPoint(2, 1), NmPoint(3, 0)), closed = false, style = 5),
            ),
        )
        assertEquals(document, WireframeReader.readText(WireframeWriter.write(document)).document)
        val result = ConvertResult(ConvertStatus.Ready, document, listOf(DrawingProblem("UNITS_ASSUMED", "mm", ProblemSeverity.Warning)))
        val read = WireframeReader.readText(WireframeWriter.writeResult(result))
        assertEquals(result.copy(converterBuild = ConvertResult.CONVERTER_BUILD), read.result)
    }

    @Test
    fun theProblemListHasTheTexts() {
        assertEquals(listOf("missing \"entities\""), problems("""{"schema":"touchdro.wireframe","v":"3.0","layers":[{"n":"0"}]}"""))
        val many = (1..12000).joinToString(",", "[", "]") { """{"t":"PT","l":0,"p":[0,0]}""" }
        assertEquals(listOf("12000 entities exceeds the 10000 cap"), problems(document(many)))
        val circles = """[{"t":"L","l":0,"p":[[0,0],[1,1]]},{"t":"L","l":0,"p":[[0,0],[1,1]]},{"t":"L","l":0,"p":[[0,0],[1,1]]},""" +
            """{"t":"L","l":0,"p":[[0,0],[1,1]]},{"t":"L","l":0,"p":[[0,0],[1,1]]},{"t":"C","l":0,"c":[0,0],"r":0}]"""
        assertEquals(listOf("entity 5: circle radius must be > 0, got 0 nm"), problems(document(circles)))
        assertTrue(problems("{not json").single().startsWith("not valid JSON: "))
        assertEquals(listOf("not a wireframe document"), problems("""{"schema":"other"}"""))
        assertEquals(listOf("file exceeds the 2 MB limit"), WireframeReader.read(ByteArray(WireframeReader.MAX_RESULT_BYTES + 1) { ' '.code.toByte() }).problems.map { problem -> problem.message })
    }

    @Test
    fun theReaderStopsAfter50Problems() {
        val bad = (1..80).joinToString(",", "[", "]") { """{"t":"C","l":0,"c":[0,0],"r":-1}""" }
        val list = problems(document(bad))
        assertEquals(50, list.size)
        assertEquals("stopped after 50 problems; the rest of the file was not checked", list.last())
    }

    @Test
    fun theEntityRulesGiveProblems() {
        assertEquals(listOf("entity 0: missing \"l\""), problems(document("""[{"t":"L","p":[[0,0],[1,1]]}]""")))
        assertEquals(listOf("entity 0: layer index 3 is out of range"), problems(document("""[{"t":"L","l":3,"p":[[0,0],[1,1]]}]""")))
        assertEquals(listOf("entity 0: unknown type \"X\""), problems(document("""[{"t":"X","l":0}]""")))
        assertEquals(listOf("entity 0: polyline needs 2 to 10000 points, got 1"), problems(document("""[{"t":"P","l":0,"pts":[[0,0]]}]""")))
        assertEquals(listOf("entity 0: Bézier chain needs 1 + 3·N points, got 5"), problems(document("""[{"t":"B","l":0,"p":[[0,0],[1,1],[2,2],[3,3],[4,4]]}]""")))
        assertEquals(
            listOf("entity 0: arc sweep must be between 1e-6 and 2π − 1e-6 rad, got 0.0"),
            problems(document("""[{"t":"A","l":0,"c":[0,0],"r":5,"a":[1.0,0.0]}]""")),
        )
        assertEquals(listOf("entity 1: duplicate id \"a\""), problems(document("""[{"t":"PT","l":0,"p":[0,0],"id":"a"},{"t":"PT","l":0,"p":[0,0],"id":"a"}]""")))
        assertEquals(listOf("layer 0: empty name"), problems(document("[]", layers = """[{"n":""}]""")))
        assertEquals(listOf("unsupported version \"2.1\""), problems("""{"schema":"touchdro.wireframe","v":"2.1","layers":[],"entities":[]}"""))
        val segments = (1..10001).joinToString(",", "[", "]") { "[0,0],[1,1],[2,2]" }.replaceFirst("[[", "[[0,0],[")
        assertEquals(
            listOf("10001 Bézier segments exceeds the 10000 cap"),
            problems(document("""[{"t":"B","l":0,"p":$segments}]""")),
        )
    }

    @Test
    fun namesAreCutAndOtherVersionsAndAxesGiveWarnings() {
        val longName = "N".repeat(250)
        val text = """{"schema":"touchdro.wireframe","v":"3.1","axes":["Z","X"],"layers":[{"n":"$longName","v":0}],""" +
            """"entities":[{"t":"PT","l":0,"p":[1,2],"name":"${"p".repeat(70)}"}]}"""
        val read = WireframeReader.readText(text)
        val document = read.document!!
        assertEquals(200, document.layers.single().name.length)
        assertEquals(false, document.layers.single().visible)
        assertEquals(60, (document.entities.single() as WireframePoint).name!!.length)
        assertEquals(
            listOf("version 3.1; SuperDRO reads it as version 3.0", "axes Z/X shown on the X/Y plane"),
            read.warnings.map { warning -> warning.message },
        )
        assertNull(document.extents)
    }

    @Test
    fun aFailedResultGivesItsErrors() {
        val read = WireframeReader.readText("""{"schema":"touchdro.convert.result","status":"failed","errors":[{"code":"PARSE_ERROR","message":"bad"}]}""")
        assertNull(read.document)
        assertEquals(listOf(DrawingProblem("PARSE_ERROR", "bad")), read.problems)
    }
}
