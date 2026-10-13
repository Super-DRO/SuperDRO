package org.superdro.core.functions.errormap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.MapPoint

class MapValidationTest {

    @Test
    fun aValidErrorMapHasNoProblem() {
        val points = listOf(MapPoint(20000, 9), MapPoint(0, 0), MapPoint(10000, 4), MapPoint(30000, 6))
        assertTrue(MapValidation.validateErrorMap(points).isEmpty())
    }

    @Test
    fun aDuplicatePositionStopsTheCheck() {
        val points = listOf(MapPoint(0, 0), MapPoint(10, 50), MapPoint(10, 3), MapPoint(20, -100))
        assertEquals(listOf<MapProblem>(MapProblem.DuplicatePosition(10)), MapValidation.validateErrorMap(points))
    }

    @Test
    fun aReversalAndADeadBandUseTheSortedNeighbors() {
        // 0 + 0 = 0; 10 + (-10) = 0 (dead band); 20 + (-15) = 5; 30 + (-30) = 0 (reversal).
        val points = listOf(MapPoint(30, -30), MapPoint(0, 0), MapPoint(20, -15), MapPoint(10, -10))
        assertEquals(
            listOf(MapProblem.DeadBand(0, 10), MapProblem.Reversal(20, 30)),
            MapValidation.validateErrorMap(points),
        )
    }

    @Test
    fun theSumsUse64Bits() {
        val big = Int.MAX_VALUE.toLong()
        val points = listOf(MapPoint(big - 1, big), MapPoint(big, big))
        assertTrue(MapValidation.validateErrorMap(points).isEmpty())
    }

    @Test
    fun aNegativeBacklashIsAProblem() {
        val points = listOf(MapPoint(100, -1), MapPoint(0, 5))
        assertEquals(listOf<MapProblem>(MapProblem.NegativeBacklash(100, -1)), MapValidation.validateBacklashMap(points, null))
    }

    @Test
    fun aBacklashOfOneScrewTurnIsAProblemOnlyWhenTheTurnIsKnown() {
        val points = listOf(MapPoint(0, 400), MapPoint(100, 399))
        assertEquals(
            listOf<MapProblem>(MapProblem.BacklashExceedsScrewRev(0, 400, 400)),
            MapValidation.validateBacklashMap(points, 400),
        )
        assertTrue(MapValidation.validateBacklashMap(points, null).isEmpty())
        assertTrue(MapValidation.validateBacklashMap(points, 0).isEmpty())
    }

    @Test
    fun aDuplicateInABacklashMapStopsTheCheck() {
        val points = listOf(MapPoint(5, -1), MapPoint(5, 2))
        assertEquals(listOf<MapProblem>(MapProblem.DuplicatePosition(5)), MapValidation.validateBacklashMap(points, 1))
    }
}
