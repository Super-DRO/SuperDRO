package org.superdro.core.functions.measurement

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.LoggedResult
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.PointSource

class InspectionExportTest {

    /** −0700, the offset of the examples. */
    private val offset = -7 * 3600
    private val mm3 = LogDisplayFormat(metric = true, precision = 3)

    private val circle = MeasurementLogEntry(
        sequence = 1,
        verb = "circle_from_3_points",
        title = "Circle from 3 points",
        results = listOf(
            LoggedResult(key = "center", x = 20000.0, y = 15000.0),
            LoggedResult(key = "diameter", value = 50000.0),
        ),
        points = listOf(
            CollectedPoint(x = 0.0, y = 0.0, source = PointSource.Latch),
            CollectedPoint(x = 40000.0, y = 0.0, source = PointSource.Latch),
            CollectedPoint(x = 0.0, y = 30000.0, source = PointSource.Latch),
        ),
        note = "Bore A",
        workspaceName = "Default",
        display = mm3,
        createdOn = Instant.parse("2026-10-11T21:25:30Z"),
    )

    private val distance = MeasurementLogEntry(
        sequence = 2,
        verb = "distance_2_points",
        title = "Point-to-Point Distance",
        results = listOf(
            LoggedResult(key = "distance", value = 50000.0),
            LoggedResult(key = "dx", value = 30000.0),
            LoggedResult(key = "dy", value = 40000.0),
            LoggedResult(key = "midpoint", x = 15000.0, y = 20000.0),
        ),
        points = listOf(
            CollectedPoint(x = 0.0, y = 0.0, source = PointSource.Probe, tipRadiusUm = 1000.0, approachX = 1, notes = listOf("UNCOMPENSATED")),
            CollectedPoint(x = 30000.0, y = 40000.0, source = PointSource.Probe, tipRadiusUm = 1000.0, approachY = 1, notes = listOf("UNCOMPENSATED")),
        ),
        notes = listOf("UNCOMPENSATED"),
        workspaceName = "Default",
        display = mm3,
        createdOn = Instant.parse("2026-10-11T21:27:02Z"),
    )

    // The example
    @Test
    fun theTwoEntriesGiveTheExampleCsv() {
        val expected =
            "\"Seq\",\"Timestamp\",\"Function\",\"Points\",\"Axis Angle (°)\",\"Note\",\"System Note\",\"Diameter (mm)\"," +
                "\"Center X (mm)\",\"Center Y (mm)\",\"Distance (mm)\",\"DX (mm)\",\"DY (mm)\",\"Midpoint X (mm)\"," +
                "\"Midpoint Y (mm)\",\"Angle (°)\",\"Supplement (°)\",\"Corner X (mm)\",\"Corner Y (mm)\",\"Angle to X (°)\"," +
                "\"Angle to Y (°)\",\"Position X (mm)\",\"Position Y (mm)\"\r\n" +
                "\"1\",\"2026-10-11 14:25:30 -0700\",\"Circle from 3 points\",\"P1 latch 0.000, 0.000 mm; P2 latch 40.000, " +
                "0.000 mm; P3 latch 0.000, 30.000 mm\",\"\",\"Bore A\",\"\",\"50.000\",\"20.000\",\"15.000\",\"\",\"\",\"\",\"\"," +
                "\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n" +
                "\"2\",\"2026-10-11 14:27:02 -0700\",\"Point-to-Point Distance\",\"P1 probe 0.000, 0.000 mm; P2 probe 30.000, " +
                "40.000 mm\",\"\",\"\",\"uncompensated\",\"\",\"\",\"\",\"50.000\",\"30.000\",\"40.000\",\"15.000\",\"20.000\"," +
                "\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"\r\n"
        assertEquals(expected, InspectionExport.csv(listOf(circle, distance), offset))
        assertContentEquals(expected.encodeToByteArray(), InspectionExport.csvBytes(listOf(circle, distance), offset))
    }

    @Test
    fun eachCsvLineHas23QuotedCells() {
        val csv = InspectionExport.csv(listOf(circle, distance), offset)
        val lines = csv.removeSuffix("\r\n").split("\r\n")
        assertEquals(3, lines.size)
        lines.forEach { line ->
            assertEquals(23, line.removePrefix("\"").removeSuffix("\"").split("\",\"").size)
        }
        assertEquals(23, InspectionExport.csvHeaders(metric = false).size)
        assertEquals("Diameter (in)", InspectionExport.csvHeaders(metric = false)[7])
    }

    @Test
    fun aQuoteInACellIsDoubledAndTheAxisAngleShows() {
        val entry = circle.copy(note = "Bore \"A\"", axisAngle = 1.256, notes = listOf("UNCOMPENSATED", "FRAME_CHANGED"))
        val row = InspectionExport.csv(listOf(entry), offset).split("\r\n")[1]
        assertEquals(true, row.contains(",\"1.26\",\"Bore \"\"A\"\"\",\"uncompensated; frame changed\","))
    }

    // The example with the SuperDRO title
    @Test
    fun theTwoEntriesGiveTheExampleTextSheet() {
        val expected = """
            SuperDRO — Inspection Log
            Workspace: Default

            #1  2026-10-11 14:25:30 -0700
            Circle from 3 points
              Center      20.000, 15.000 mm
              Circle      Ø 50.000 mm
              Note: Bore A
              P1 latch  0.000, 0.000 mm
              P2 latch  40.000, 0.000 mm
              P3 latch  0.000, 30.000 mm

            #2  2026-10-11 14:27:02 -0700
            Point-to-Point Distance
              Distance    50.000 mm
              ΔX          30.000 mm
              ΔY          40.000 mm
              Midpoint    15.000, 20.000 mm
              System: uncompensated
              P1 probe  0.000, 0.000 mm
              P2 probe  30.000, 40.000 mm

        """.trimIndent()
        assertEquals(expected, InspectionExport.textSheet(listOf(circle, distance), offset))
    }

    @Test
    fun theTextSheetUsesTheStoredFormatOfEachEntryAndTheGivenLabels() {
        val inch = distance.copy(display = LogDisplayFormat(metric = false, precision = 4), notes = emptyList(), workspaceName = "Fixture")
        val sheet = InspectionExport.textSheet(
            listOf(circle, inch),
            offset,
            InspectionSheetLabels(title = "Inspection", resultLabels = mapOf(ResultKey.Distance to "Abstand")),
        )
        val lines = sheet.split("\n")
        assertEquals("Inspection", lines[0])
        assertEquals("Workspace: Default, Fixture", lines[1])
        assertEquals(true, lines.contains("  Circle      Ø 50.000 mm"))
        assertEquals(true, lines.contains("  Abstand     1.9685\""))
        assertEquals(true, lines.contains("  Midpoint    0.5906, 0.7874\""))
        assertEquals(true, lines.contains("  P2 probe  1.1811, 1.5748 in"))
    }

    @Test
    fun aRowWithTheOtherUnitGoesIntoTheUnitOfTheHeader() {
        val inch = distance.copy(display = LogDisplayFormat(metric = false, precision = 4))
        val metricFirst = InspectionExport.csv(listOf(circle, inch), offset).split("\r\n")
        assertEquals(true, metricFirst[0].contains("\"Distance (mm)\""))
        assertEquals(true, metricFirst[2].contains("\"50.000\",\"30.000\",\"40.000\""))
        val inchFirst = InspectionExport.csv(listOf(inch, circle), offset).split("\r\n")
        assertEquals(true, inchFirst[0].contains("\"Distance (in)\""))
        assertEquals(true, inchFirst[1].contains("\"1.9685\",\"1.1811\",\"1.5748\""))
        assertEquals(true, inchFirst[2].contains("\"1.9685\",\"0.7874\",\"0.5906\""))
        assertEquals(true, inchFirst[2].contains("P2 latch 1.5748, 0.0000 in"))
        assertEquals(
            "\"Seq\"",
            InspectionExport.csv(emptyList(), offset, metricWhenEmpty = false).split(",").first(),
        )
        assertEquals(true, InspectionExport.csv(emptyList(), offset, metricWhenEmpty = false).contains("\"Diameter (in)\""))
    }

    // The file name
    @Test
    fun theFileNameHasTheCleanedParts() {
        val time = Instant.parse("2026-10-11T21:25:30Z")
        assertEquals(
            "superdro-inspection_log-My_Part_2-Bridgeport_old-mill-20261011_142530.csv",
            InspectionExport.fileName("My Part #2", "Bridgeport (old)", lathe = false, time, offset, "csv"),
        )
        assertEquals(
            "superdro-inspection_log-lathe-20261011_142530.txt",
            InspectionExport.fileName("---", "", lathe = true, time, offset, "txt"),
        )
        assertEquals("a_b", InspectionExport.cleanPart("__a  b__"))
        assertEquals("sub-datum", InspectionExport.sourceText(PointSource.SubDatum))
    }
}
