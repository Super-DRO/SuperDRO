package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.superdro.core.model.CollectedPoint
import org.superdro.core.model.LogDisplayFormat
import org.superdro.core.model.LoggedResult
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.PointSource
import org.superdro.core.store.files.csv.CsvParser
import org.superdro.core.store.files.log.InspectionLogExport

/** The exports of the inspection log. */
class InspectionLogExportTest {

    private val context = ExportContext("1.0.0", Instant.parse("2026-10-11T21:30:00Z"), TimeZone.of("America/Los_Angeles"))
    private val profile = MachineProfile.create(MachineType.VerticalMill).copy(name = "Bridgeport", metricDefault = true)

    private val circle = MeasurementLogEntry(
        sequence = 1,
        verb = "circle_from_3_points",
        title = "Circle from 3 points",
        results = listOf(LoggedResult("center", x = 20000.0, y = 15000.0), LoggedResult("diameter", value = 50000.0)),
        points = listOf(CollectedPoint(0.0, 0.0), CollectedPoint(40000.0, 0.0), CollectedPoint(0.0, 30000.0)),
        note = "Bore A",
        workspaceName = "Default",
        display = LogDisplayFormat(metric = true, precision = 3),
        createdOn = Instant.parse("2026-10-11T21:25:30Z"),
    )
    private val distance = MeasurementLogEntry(
        sequence = 2,
        verb = "point_to_point_distance",
        title = "Point-to-Point Distance",
        results = listOf(
            LoggedResult("distance", value = 50000.0),
            LoggedResult("dx", value = 30000.0),
            LoggedResult("dy", value = 40000.0),
            LoggedResult("midpoint", x = 15000.0, y = 20000.0),
        ),
        points = listOf(CollectedPoint(0.0, 0.0, source = PointSource.Probe), CollectedPoint(30000.0, 40000.0, source = PointSource.Probe)),
        notes = listOf("UNCOMPENSATED"),
        workspaceName = "Default",
        display = LogDisplayFormat(metric = true, precision = 3),
        createdOn = Instant.parse("2026-10-11T21:27:02Z"),
    )

    @Test
    fun theCsvExportHas23QuotedColumnsAndCrLf() {
        val file = InspectionLogExport.csv(listOf(distance, circle), "Default", profile, context)
        assertEquals("superdro-inspection_log-Default-Bridgeport-mill-20261011_143000.csv", file.fileName)
        val expected = listOf(
            "\"Seq\",\"Timestamp\",\"Function\",\"Points\",\"Axis Angle (°)\",\"Note\",\"System Note\",\"Diameter (mm)\",\"Center X (mm)\"," +
                "\"Center Y (mm)\",\"Distance (mm)\",\"DX (mm)\",\"DY (mm)\",\"Midpoint X (mm)\",\"Midpoint Y (mm)\",\"Angle (°)\",\"Supplement (°)\"," +
                "\"Corner X (mm)\",\"Corner Y (mm)\",\"Angle to X (°)\",\"Angle to Y (°)\",\"Position X (mm)\",\"Position Y (mm)\"",
            "\"1\",\"2026-10-11 14:25:30 -0700\",\"Circle from 3 points\",\"P1 latch 0.000, 0.000 mm; P2 latch 40.000, 0.000 mm; P3 latch 0.000, 30.000 mm\"," +
                "\"\",\"Bore A\",\"\",\"50.000\",\"20.000\",\"15.000\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"",
            "\"2\",\"2026-10-11 14:27:02 -0700\",\"Point-to-Point Distance\",\"P1 probe 0.000, 0.000 mm; P2 probe 30.000, 40.000 mm\",\"\",\"\"," +
                "\"uncompensated\",\"\",\"\",\"\",\"50.000\",\"30.000\",\"40.000\",\"15.000\",\"20.000\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"",
        ).joinToString("\r\n", postfix = "\r\n")
        assertEquals(expected, file.text)
        assertTrue(CsvParser.parse(file.text).all { record -> record.size == 23 })
    }

    @Test
    fun theTextSheetHasTheEntriesInSequence() {
        val file = InspectionLogExport.text(listOf(circle, distance), "Default", profile, context)
        assertEquals("superdro-inspection_log-Default-Bridgeport-mill-20261011_143000.txt", file.fileName)
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
        assertEquals(expected, file.text)
    }

    @Test
    fun eachEntryUsesItsStoredUnitAndDecimals() {
        val inch = distance.copy(sequence = 3, display = LogDisplayFormat(metric = false, precision = 4), notes = emptyList())
        val sheet = InspectionLogExport.text(listOf(inch), "Default", profile, context).text
        assertTrue("  Distance    1.9685\"" in sheet.lines(), sheet)
        assertTrue("  P2 probe  1.1811, 1.5748 in" in sheet.lines(), sheet)
        // Mixed units: the CSV has the unit of the profile, and the inch entry gets 3 mm decimals.
        val csv = CsvParser.parse(InspectionLogExport.csv(listOf(circle, inch), "Default", profile, context).text)
        assertEquals("Distance (mm)", csv[0][10])
        assertEquals("50.000", csv[2][10])
        // One unit for all entries: the CSV has that unit.
        val inchCsv = CsvParser.parse(InspectionLogExport.csv(listOf(inch), "Default", profile, context).text)
        assertEquals("Distance (in)", inchCsv[0][10])
        assertEquals("1.9685", inchCsv[1][10])
    }
}
