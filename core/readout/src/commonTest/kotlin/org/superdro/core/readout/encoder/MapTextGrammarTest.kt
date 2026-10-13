package org.superdro.core.readout.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.MapPoint

/**
 * The one parser of the map text. The map editor import and the file checks of `core:store` use
 * it. A comment comes with no `#`.
 */
class MapTextGrammarTest {

    @Test
    fun aNullOrBlankTextGivesNoPointsAndNoProblems() {
        assertEquals(MapParseResult(), MapText.parse(null))
        assertEquals(MapParseResult(), MapText.parse("  \n\t\n"))
    }

    @Test
    fun theParserKeepsPointsCommentsAndMalformedLineNumbers() {
        val text = "# c1\nposition,correction\n0,0\n10000, 4\n20000\t9\n30000  6\nfoo bar baz\n1.5,2\n"
        val result = MapText.parse(text)
        assertEquals(
            listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9), MapPoint(30000, 6)),
            result.points,
        )
        assertEquals(listOf("c1"), result.comments)
        assertEquals(listOf(7, 8), result.malformedLines)
        assertTrue(result.sawDecimalValues)
        assertFalse(result.exceededLimit)
    }

    @Test
    fun blankLinesCountInTheLineNumbersAndEachLineEndWorks() {
        val result = MapText.parse("\r\n\rbad\n5,5\r\n6,7")
        assertEquals(listOf(MapPoint(5, 5), MapPoint(6, 7)), result.points)
        // Line 1 is empty (CR LF), line 2 is empty (CR), line 3 is "bad".
        assertEquals(listOf(3), result.malformedLines)
        assertFalse(result.sawDecimalValues)
    }

    @Test
    fun theParserSkipsAtMostFiveHeaderLinesAndOnlyBeforeTheFirstPoint() {
        val headers = (1..6).joinToString("\n") { "head$it,col$it" }
        val result = MapText.parse("$headers\n1,2\nname,value\n")
        assertEquals(listOf(MapPoint(1, 2)), result.points)
        assertEquals(listOf(6, 8), result.malformedLines)
    }

    @Test
    fun aHeaderTokenThatIsANumberMakesTheLineMalformed() {
        val result = MapText.parse("position,1.5\n1,2\n")
        assertEquals(listOf(1), result.malformedLines)
        assertTrue(result.sawDecimalValues)
    }

    @Test
    fun theParserKeepsTheFirst50Comments() {
        val text = (1..60).joinToString("\n") { "# note $it" } + "\n1,1"
        val result = MapText.parse(text)
        assertEquals(50, result.comments.size)
        assertEquals("note 50", result.comments.last())
    }

    @Test
    fun morePointsThanTheLimitGiveNoPointsAndTheLimitFlag() {
        val text = "# kept\n" + (0..MapText.MAX_POINTS).joinToString("\n") { "$it,0" }
        val result = MapText.parse(text)
        assertTrue(result.exceededLimit)
        assertTrue(result.points.isEmpty())
        assertTrue(result.malformedLines.isEmpty())
        assertEquals(listOf("kept"), result.comments)
        val atLimit = MapText.parse((1..MapText.MAX_POINTS).joinToString("\n") { "$it,0" })
        assertFalse(atLimit.exceededLimit)
        assertEquals(MapText.MAX_POINTS, atLimit.points.size)
    }

    @Test
    fun theLimitResultKeepsTheDecimalFlagOfTheLinesBeforeTheLimit() {
        val text = "1.5,2\n" + (0..MapText.MAX_POINTS).joinToString("\n") { "$it,0" }
        val result = MapText.parse(text)
        assertTrue(result.exceededLimit)
        assertTrue(result.sawDecimalValues)
        assertTrue(result.malformedLines.isEmpty())
    }

    @Test
    fun aValueOutsideTheIntRangeIsMalformed() {
        val result = MapText.parse("1,2\n3000000000,1\n")
        assertEquals(listOf(MapPoint(1, 2)), result.points)
        assertEquals(listOf(2), result.malformedLines)
    }
}
