package org.superdro.core.readout.encoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint

/** The error map grammar, validation, and lookup. */
class ErrorMapTest {

    private val runTimeMap = listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9), MapPoint(30000, 6))

    @Test
    fun theParseReadsPointsCommentsAndHeaders() {
        val text = "# TouchDRO error correction map\r\nposition,correction\n0,0\n\n10000\t4\r20000  9\n# end\n30000, 6\n"
        val result = MapText.parse(text)
        assertEquals(runTimeMap, result.points)
        assertEquals(emptyList(), result.malformedLines)
        assertEquals(listOf("TouchDRO error correction map", "end"), result.comments)
        assertFalse(result.exceededLimit)
    }

    @Test
    fun aLineThatIsNotAPointIsAMalformedLine() {
        val result = MapText.parse("position,correction\n0,0\n100, 5, x\n200,1.5\nabc def\n300,3\n")
        assertEquals(listOf(MapPoint(0, 0), MapPoint(300, 3)), result.points)
        assertEquals(listOf(3, 4, 5), result.malformedLines)
        assertTrue(result.sawDecimalValues)
    }

    @Test
    fun aBlankTextGivesNoPointsAndNoProblems() {
        assertEquals(MapParseResult(), MapText.parse(null))
        assertEquals(MapParseResult(), MapText.parse("  \n "))
        assertTrue(MapValidation.checkErrorMapText("").valid)
    }

    @Test
    fun moreThan20000PointsGiveNoPoints() {
        val text = (0..20000).joinToString("\n") { index -> "$index,0" }
        val result = MapText.parse(text)
        assertTrue(result.exceededLimit)
        assertEquals(emptyList(), result.points)
        val check = MapValidation.checkErrorMapText(text)
        assertEquals(listOf<MapProblem>(MapProblem.TooManyPoints()), check.problems)
        assertEquals("The map has more than 20000 points.", check.problems.single().message.english)
        assertTrue(MapTable.of((0..20000).map { index -> MapPoint(index.toLong(), 1) }).isEmpty)
    }

    @Test
    fun theValidationGivesAProblemList() {
        assertEquals(
            listOf<MapProblem>(MapProblem.DuplicatePosition(100)),
            MapValidation.errorMapProblems(listOf(MapPoint(100, 0), MapPoint(200, 0), MapPoint(100, 5))),
        )
        assertEquals(
            listOf<MapProblem>(MapProblem.Reversal(100, 200), MapProblem.DeadBand(200, 300)),
            MapValidation.errorMapProblems(listOf(MapPoint(100, 0), MapPoint(200, -150), MapPoint(300, -250))),
        )
        assertEquals(emptyList(), MapValidation.errorMapProblems(runTimeMap))
        val check = MapValidation.checkErrorMapText("0,0\nbad\n")
        assertEquals(listOf<MapProblem>(MapProblem.MalformedLine(2)), check.problems)
        assertEquals(listOf<MapProblem>(MapProblem.NoPoints), MapValidation.checkErrorMapText("# only a comment\n").problems)
    }

    @Test
    fun theProblemTexts() {
        assertEquals("Position 100 occurs more than one time.", MapProblem.DuplicatePosition(100).message.english)
        assertEquals("The corrected position goes back between positions 100 and 200.", MapProblem.Reversal(100, 200).message.english)
        assertEquals("The corrected position does not move between positions 200 and 300.", MapProblem.DeadBand(200, 300).message.english)
        assertEquals("Line 2 is not a valid point.", MapProblem.MalformedLine(2).message.english)
        assertEquals("The backlash at position 5 is negative (-3).", MapProblem.NegativeBacklash(5, -3).message.english)
    }

    @Test
    fun theBacklashMapValidationAndTheProfileCheck() {
        val problems = MapValidation.backlashMapProblems(listOf(MapPoint(0, -1), MapPoint(100, 400)), countsPerScrewRev = 400.0)
        assertEquals(listOf<MapProblem>(MapProblem.NegativeBacklash(0, -1), MapProblem.BacklashExceedsScrewTurn(100, 400, 400)), problems)
        val scaleWithMap = EncoderProfile(type = EncoderType.Scale, backlashMode = BacklashMode.Map, backlashMap = listOf(MapPoint(0, 5)))
        assertEquals(listOf<EncoderProfileProblem>(EncoderProfileProblem.BacklashMapNotAllowed), EncoderProfileCheck.problems(scaleWithMap))
        assertEquals(
            "A backlash map is available only for a leadscrew encoder.",
            EncoderProfileProblem.BacklashMapNotAllowed.message.english,
        )
        assertEquals(emptyList(), EncoderProfileCheck.problems(EncoderProfile(errorMap = runTimeMap)))
    }

    @Test
    fun theLookupInterpolatesAndRoundsHalfToEven() {
        val table = MapTable.of(runTimeMap)
        assertEquals(2L + 4L, table.lookup(15000L), "2.5 rounds to 2")
        assertEquals(4L, MapTable.of(listOf(MapPoint(0, 0), MapPoint(10, 7))).lookup(5L), "3.5 rounds to 4")
        assertEquals(5002L, ErrorCorrection(MapTable.of(listOf(MapPoint(0, 0), MapPoint(10000, 4)))).apply(5000L))
        assertEquals(12004L, ErrorCorrection(MapTable.of(listOf(MapPoint(0, 0), MapPoint(10000, 4)))).apply(12000L))
    }

    @Test
    fun theCorrectionIsFlatOutsideTheMap() {
        val table = MapTable.of(runTimeMap)
        assertEquals(0L, table.lookup(-500L))
        assertEquals(6L, table.lookup(35000L))
        assertEquals(0L, MapTable.EMPTY.lookup(123L))
        assertEquals(7L, MapTable.of(listOf(MapPoint(50, 7))).lookup(-10L), "one point gives a constant offset")
    }

    @Test
    fun theTableSortsThePointsAndKeepsTheFirstPointOfAPosition() {
        val table = MapTable.of(listOf(MapPoint(10000, 4), MapPoint(0, 0), MapPoint(10000, 99)))
        assertEquals(2, table.size)
        assertEquals(4L, table.lookup(10000L))
        assertEquals(2L, table.lookup(5000L))
    }

    @Test
    fun aCyclicMapOnAnAngularInputWrapsThePosition() {
        val n = 3600.0
        val table = MapTable.of(listOf(MapPoint(0, 0), MapPoint(100, 10), MapPoint(3500, -10), MapPoint(3599, 0)))
        val cyclic = ErrorCorrection(table, cyclicCounts = n)
        assertEquals(cyclic.correction(10L), cyclic.correction(3610L), "N + 10 gives the correction of 10")
        assertEquals(cyclic.correction(3590L), cyclic.correction(-10L), "-10 gives the correction of N - 10")
        assertEquals(1L, cyclic.correction(3610L))
        assertEquals(-1L, cyclic.correction(-10L))
        val notCyclic = ErrorCorrection(table)
        assertEquals(0L, notCyclic.correction(3610L))
        assertEquals(0L, notCyclic.correction(-10L))
    }

    @Test
    fun theStagesWrapOnlyAnAngularInputWithTheCyclicSwitchOn() {
        val map = listOf(MapPoint(0, 0), MapPoint(100, 10), MapPoint(3599, 0))
        val angular = EncoderProfile(kind = org.superdro.core.model.EncoderKind.Angular, resolution = 3600.0, errorMap = map)
        assertEquals(3600.0, EncoderStages(angular).errorCorrection.cyclicCounts)
        assertEquals(null, EncoderStages(angular.copy(errorMapCyclic = false)).errorCorrection.cyclicCounts)
        assertEquals(null, EncoderStages(EncoderProfile(errorMap = map)).errorCorrection.cyclicCounts)
    }

    @Test
    fun theWriterGivesATextThatTheParseReadsBack() {
        assertEquals(runTimeMap, MapText.parse(MapText.write(runTimeMap)).points)
    }
}
