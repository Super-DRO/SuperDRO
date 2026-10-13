package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.MapText as MapGrammar
import org.superdro.core.store.files.adapter.MapText

/**
 * The map text checks of the store.
 * The grammar is the one parser of `core:readout` ([MapGrammar]). It gives a comment with no `#`.
 */
class MapTextTest {

    private fun texts(messages: List<ImportMessage>): List<String> = messages.map { ImportTexts.English.text(it) }

    @Test
    fun theGrammarReadsPointsCommentsAndHeaderLines() {
        val result = MapGrammar.parse("# made by hand\nposition,correction\n\n0,0\n100\t5\n200  7\n1.5,2\nabc def ghi")
        assertEquals(listOf(MapPoint(0, 0), MapPoint(100, 5), MapPoint(200, 7)), result.points)
        // The line counter counts the blank line.
        assertEquals(listOf(7, 8), result.malformedLines)
        assertEquals(listOf("made by hand"), result.comments)
        assertTrue(result.sawDecimalValues)
        assertFalse(result.exceededLimit)
    }

    @Test
    fun aHeaderLineIsOnlyPermittedBeforeTheFirstPointAndFiveTimes() {
        val sixHeaders = MapGrammar.parse((1..6).joinToString("\n") { "a,b" } + "\n0,0")
        assertEquals(listOf(6), sixHeaders.malformedLines)
        val late = MapGrammar.parse("0,0\nposition,correction\n100,1")
        assertEquals(listOf(2), late.malformedLines)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(100, 1)), late.points)
    }

    @Test
    fun moreThan20000PointsGiveNoPoints() {
        val text = (0..20_000).joinToString("\n") { index -> "${index * 10},0" }
        val result = MapGrammar.parse(text)
        assertTrue(result.exceededLimit)
        assertTrue(result.points.isEmpty())
        assertEquals(listOf("The map has more than 20000 points."), texts(MapText.checkErrorMap(text).problems))
        assertEquals(20_000, MapGrammar.parse((0 until 20_000).joinToString("\n") { index -> "${index * 10},0" }).points.size)
    }

    @Test
    fun theValidationFindsDuplicatesReversalsAndDeadBands() {
        assertEquals(listOf("Position 0 is in the map more than one time."), texts(MapText.validateErrorMap(listOf(MapPoint(0, 0), MapPoint(0, 1), MapPoint(5, 0)))))
        assertEquals(listOf("The corrected position does not move between positions 0 and 10."), texts(MapText.validateErrorMap(listOf(MapPoint(10, 0), MapPoint(0, 10)))))
        assertEquals(listOf("The corrected position moves back between positions 0 and 100."), texts(MapText.validateErrorMap(listOf(MapPoint(0, 0), MapPoint(100, -150), MapPoint(200, 10)))))
        assertTrue(MapText.validateErrorMap(listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9))).isEmpty())
    }

    @Test
    fun theChecksOfTheTwoLayers() {
        assertTrue(MapText.checkErrorMap(null).valid)
        assertTrue(MapText.checkErrorMap("  ").points.isEmpty())
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10, 1)), MapText.checkErrorMap("position,correction\n0,0\n10,1").points)
        assertEquals(listOf("Line 2 is not a pair of whole numbers."), texts(MapText.checkErrorMap("0,0\n100, 5, x\n200,7").problems))
        assertEquals(listOf("The map has no points."), texts(MapText.checkErrorMap("# only a comment").problems))
        assertEquals(
            listOf("Line 2 is not a pair of whole numbers.", "The map has decimal values. Use whole counts."),
            texts(MapText.checkErrorMap("0,0\n1.5,2\n3,4").problems),
        )
        assertEquals(
            listOf("The backlash at position 0 is less than 0.", "The backlash at position 10 is not less than one leadscrew turn."),
            texts(MapText.checkBacklashMap("0,-1\n10,2000\n20,5", countsPerScrewRev = 2000.0).problems),
        )
        assertTrue(MapText.checkBacklashMap("0,30\n5000,40", countsPerScrewRev = 2000.0).valid)
    }

    @Test
    fun aTextOverTheLimitWithADecimalLineGivesBothMessages() {
        val text = "1.5,2\n" + (0..20_000).joinToString("\n") { index -> "${index * 10},0" }
        assertEquals(
            listOf("The map has more than 20000 points.", "The map has decimal values. Use whole counts."),
            texts(MapText.checkErrorMap(text).problems),
        )
    }

    @Test
    fun theCommentsOfAMapGoIntoTheTextAndComeBackFromTheCheck() {
        val comments = listOf("# generated-by: SuperDRO Error Map Wizard", "passes: 3")
        val text = MapText.write(listOf(MapPoint(0, 0), MapPoint(10, 1)), comments = comments)
        assertEquals("# generated-by: SuperDRO Error Map Wizard\n# passes: 3\nposition,correction\n0,0\n10,1", text)
        assertEquals(listOf("# generated-by: SuperDRO Error Map Wizard", "# passes: 3"), MapText.checkErrorMap(text).comments)
        assertEquals(emptyList(), MapText.checkErrorMap("# note\n0,0\n0,1").comments, "a map with problems gives no comments")
    }

    @Test
    fun theWriterGivesAHeaderAndOnePointOnEachLine() {
        assertEquals("position,correction\n0,0\n10,1", MapText.write(listOf(MapPoint(0, 0), MapPoint(10, 1))))
        assertEquals("position,backlash\n0,30", MapText.write(listOf(MapPoint(0, 30)), MapText.BACKLASH_MAP_HEADER))
    }
}
