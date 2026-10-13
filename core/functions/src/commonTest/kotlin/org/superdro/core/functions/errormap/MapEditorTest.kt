package org.superdro.core.functions.errormap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.MapPoint
import org.superdro.core.readout.encoder.MapText

class MapEditorTest {

    @Test
    fun theFileNameMustEndWithCsv() {
        assertTrue(MapEditor.isCsvFileName("map.CSV"))
        assertTrue(MapEditor.isCsvFileName(null))
        assertFalse(MapEditor.isCsvFileName("map.txt"))
    }

    @Test
    fun aNulCharacterOrManyControlCharactersLookLikeBinaryData() {
        assertEquals(listOf<MapImportProblem>(MapImportProblem.LooksBinary), MapEditor.import("0,0\u0000\n", MapKind.Error).problems)
        // 6 bad characters of 100 is more than 1/20.
        val noisy = "\u0001".repeat(6) + "a".repeat(94)
        assertTrue(MapEditor.looksBinary(noisy))
        // 5 of 100 is not more than 1/20. CR, LF, and TAB are not bad characters.
        val quiet = "�".repeat(5) + "\r\n\t" + "a".repeat(92)
        assertFalse(MapEditor.looksBinary(quiet))
    }

    @Test
    fun aTextWithNoPointsAndNoMalformedLinesHasNoValidPoints() {
        val result = MapEditor.import("# only a comment\n", MapKind.Error)
        assertFalse(result.accepted)
        assertEquals(listOf<MapImportProblem>(MapImportProblem.NoValidPoints), result.problems)
    }

    @Test
    fun moreMalformedLinesThanPointsIsNotACsv() {
        assertEquals(listOf<MapImportProblem>(MapImportProblem.NotCsv), MapEditor.import("a b c\nd e f\n", MapKind.Error).problems)
        assertEquals(listOf<MapImportProblem>(MapImportProblem.NotCsv), MapEditor.import("1,1\na b c\nd e f\n", MapKind.Error).problems)
    }

    @Test
    fun eachMalformedLineIsAProblemAndADecimalValueAddsTheHint() {
        val result = MapEditor.import("0,0\n10,1\n1.5,2\n", MapKind.Error)
        assertFalse(result.accepted)
        assertEquals(listOf(MapImportProblem.MalformedLine(3), MapImportProblem.DecimalValues), result.problems)
    }

    @Test
    fun tooManyPointsIsAProblem() {
        val text = (0..MapText.MAX_POINTS).joinToString("\n") { "$it,0" }
        assertEquals(listOf<MapImportProblem>(MapImportProblem.TooManyPoints), MapEditor.import(text, MapKind.Error).problems)
    }

    @Test
    fun truePositionsBecomeCorrections() {
        // max|value − position| = 9; 9 × 4 = 36 < max(1, 20000): the file holds true positions.
        val result = MapEditor.import("0,0\n10000,10004\n20000,20009\n", MapKind.Error)
        assertTrue(result.accepted)
        assertTrue(result.convertedFromAbsolute)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9)), result.points)
    }

    @Test
    fun correctionsAreNotConverted() {
        val result = MapEditor.import("10000,4\n0,0\n", MapKind.Error)
        assertTrue(result.accepted)
        assertFalse(result.convertedFromAbsolute)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4)), result.points)
        assertFalse(MapEditor.holdsTruePositions(listOf(MapPoint(5, 5))))
    }

    @Test
    fun aBacklashMapIsNeverConverted() {
        val result = MapEditor.import("0,0\n10000,10004\n", MapKind.Backlash)
        assertFalse(result.convertedFromAbsolute)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 10004)), result.points)
    }

    @Test
    fun theImportRefusesAMapThatTheValidatorRefuses() {
        val result = MapEditor.import("0,0\n10,-20\n", MapKind.Error)
        assertFalse(result.accepted)
        assertEquals(listOf<MapImportProblem>(MapImportProblem.Invalid(MapProblem.Reversal(0, 10))), result.problems)
        val backlash = MapEditor.import("0,5\n100,-1\n", MapKind.Backlash, countsPerScrewRev = 4)
        assertEquals(
            listOf<MapImportProblem>(
                MapImportProblem.Invalid(MapProblem.BacklashExceedsScrewRev(0, 5, 4)),
                MapImportProblem.Invalid(MapProblem.NegativeBacklash(100, -1)),
            ),
            backlash.problems,
        )
    }

    @Test
    fun aTableWithRowsGivesTheReplaceQuestion() {
        val result = MapEditor.import("20000,9\n0,0\n10000,4\n", MapKind.Error, currentRowCount = 7)
        assertEquals(ReplaceQuestion(currentCount = 7, newCount = 3, firstPosition = 0, lastPosition = 20000), result.replaceQuestion)
        assertNull(MapEditor.import("0,0\n", MapKind.Error).replaceQuestion)
    }

    @Test
    fun aGeneratedByCommentMakesTheMapReadOnly() {
        val touch = MapEditor.import("# generated-by: TouchDRO Error Map Wizard\n0,0\n10,1\n", MapKind.Error)
        assertTrue(touch.readOnly)
        val own = MapEditor.import("# Generated-By: SuperDRO Error Map Wizard\n0,0\n10,1\n", MapKind.Error)
        assertTrue(own.readOnly)
        assertFalse(MapEditor.import("# a note\n0,0\n10,1\n", MapKind.Error).readOnly)
    }

    @Test
    fun theProvenanceReaderReadsTheKnownKeysAndKeepsTheNotes() {
        val provenance = MapProvenance.fromComments(
            listOf(
                "# generated-by: SuperDRO Error Map Wizard",
                "# Generated-On: 2026-10-11 14:03:22",
                "# passes: 4",
                "# spread-before-counts: 23.0",
                "# spread-after-counts: 4.0",
                "# a note",
            ),
        )
        assertEquals(
            MapProvenance("SuperDRO Error Map Wizard", "2026-10-11 14:03:22", 4, 23.0, 4.0, listOf("a note")),
            provenance,
        )
        assertEquals(
            listOf(
                "# generated-by: SuperDRO Error Map Wizard",
                "# generated-on: 2026-10-11 14:03:22",
                "# passes: 4",
                "# spread-before-counts: 23.0",
                "# spread-after-counts: 4.0",
                "# a note",
            ),
            provenance.toComments(),
        )
    }

    @Test
    fun saveSortsTheRowsAndPutsTheCommentsFirst() {
        val provenance = MapProvenance(notes = listOf("checked"))
        val result = MapEditor.save(MapKind.Error, listOf(MapPoint(10, 1), MapPoint(0, 0)), provenance)
        assertEquals(MapSaveResult.Saved(listOf(MapPoint(0, 0), MapPoint(10, 1)), "# checked\n0,0\n10,1\n"), result)
    }

    @Test
    fun saveRefusesDuplicatesReversalsAndDeadBands() {
        assertEquals(
            MapSaveResult.Refused(listOf(MapProblem.DuplicatePosition(10))),
            MapEditor.save(MapKind.Error, listOf(MapPoint(10, 1), MapPoint(10, 2))),
        )
        assertEquals(
            MapSaveResult.Refused(listOf(MapProblem.DeadBand(0, 10))),
            MapEditor.save(MapKind.Error, listOf(MapPoint(0, 0), MapPoint(10, -10))),
        )
        assertEquals(
            MapSaveResult.Refused(listOf(MapProblem.Reversal(0, 10))),
            MapEditor.save(MapKind.Error, listOf(MapPoint(0, 0), MapPoint(10, -11))),
        )
    }

    @Test
    fun clearAndSaveClearsTheMap() {
        assertEquals(MapSaveResult.Cleared, MapEditor.save(MapKind.Error, MapEditor.clear()))
    }

    @Test
    fun theExportFileNameHasTheKindTheCleanAdapterTheInputAndTheTime() {
        assertEquals(
            "error-correction-map-My_Adapter_2-X-20261012_080000.csv",
            MapExport.fileName(MapKind.Error, "  My Adapter #2 ", 'x', "20261012_080000"),
        )
        assertEquals("backlash-map-adapter-Z-20261012_080000.csv", MapExport.fileName(MapKind.Backlash, "!!!", 'z', "20261012_080000"))
    }

    @Test
    fun theExportTextHasTheSuperDroHeaderAndImportsAgain() {
        val points = listOf(MapPoint(10000, 4), MapPoint(0, 0))
        val provenance = MapProvenance(generatedBy = MapProvenance.SUPERDRO_WIZARD, passes = 2)
        val text = MapExport.text(MapKind.Error, points, "Mill", 'x', "2026-10-12 08:00:00", provenance)
        val expected = "# SuperDRO error correction map\n" +
            "# adapter: Mill  |  input: X  |  exported: 2026-10-12 08:00:00\n" +
            "# position   = raw encoder count, measured from the machine datum\n" +
            "# correction = signed delta, in encoder counts\n" +
            "#              corrected position = position + correction\n" +
            "# NOTE: valid only while the axis is referenced to the same datum this map\n" +
            "#       was made with. Re-reference before applying it.\n" +
            "# generated-by: SuperDRO Error Map Wizard\n" +
            "# passes: 2\n" +
            "position,correction\n" +
            "0,0\n" +
            "10000,4\n"
        assertEquals(expected, text)
        val again = MapEditor.import(text, MapKind.Error)
        assertTrue(again.accepted)
        assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4)), again.points)
        assertEquals(provenance, again.provenance)
        assertTrue(again.readOnly)
    }

    @Test
    fun theBacklashExportHeaderHasTheBacklashColumn() {
        val lines = MapExport.headerLines(MapKind.Backlash, "Lathe", 'z', "2026-10-12 08:00:00")
        assertEquals("# SuperDRO backlash map", lines.first())
        assertEquals("position,backlash", lines.last())
        assertEquals(9, lines.size)
    }

    @Test
    fun theSummaryGivesTheSpanTheSpacingTheLargestCorrectionAndTheError() {
        val points = listOf(MapPoint(20000, 9), MapPoint(0, 0), MapPoint(10000, 4), MapPoint(30000, 6))
        val provenance = MapProvenance(spreadBeforeCounts = 23.0, spreadAfterCounts = 4.0, passes = 4)
        val summary = MapEditor.summary(points, provenance)
        assertEquals(
            MapSummary(
                firstPosition = 0,
                lastPosition = 30000,
                intervalCount = 3,
                typicalSpacing = 10000,
                largestCorrection = 9,
                source = provenance,
                errorBeforeHalf = 11.5,
                errorAfterHalf = 2.0,
            ),
            summary,
        )
        assertEquals(12L, MapEditor.summary(listOf(MapPoint(0, -12), MapPoint(10, 3))).largestCorrection)
        // Neighbor distances 10 and 15 give the median 12.5, rounded to 13.
        assertEquals(13L, MapEditor.summary(listOf(MapPoint(0, 0), MapPoint(10, 0), MapPoint(25, 0))).typicalSpacing)
        assertNull(MapEditor.summary(emptyList()).firstPosition)
    }
}
