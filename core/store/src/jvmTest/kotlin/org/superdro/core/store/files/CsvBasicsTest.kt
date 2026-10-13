package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.store.files.csv.CsvFormatException
import org.superdro.core.store.files.csv.CsvMetadata
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.csv.CsvParser
import org.superdro.core.store.files.csv.CsvTable
import org.superdro.core.store.files.csv.CsvWriter

/** The CSV reader and writer, the metadata block, the numbers, and the file names. */
class CsvBasicsTest {

    @Test
    fun theParserReadsQuotesLineEndsAndIgnoresEmptyLines() {
        val records = CsvParser.parse("a,\"b, c\",\"d \"\"e\"\"\"\r\n\r\n1,2,3\n\"x\ny\",,\r")
        assertEquals(listOf(listOf("a", "b, c", "d \"e\""), listOf("1", "2", "3"), listOf("x\ny", "", "")), records)
    }

    @Test
    fun aQuoteWithNoEndIsAnErrorNotACrash() {
        assertFailsWith<CsvFormatException> { CsvParser.parse("a,\"b\n") }
        val read = CsvTable.read("name,x\n\"open,1\n")
        assertIs<CsvTable.Companion.Read.Refused>(read)
        assertEquals("The app cannot read the file as CSV.", read.problem.describe())
    }

    @Test
    fun theWriterEndsEachRecordWithCrLfAndQuotesOnlyWhenNecessary() {
        assertEquals("a,\"b, c\",\"d \"\"e\"\"\",\" x\",\"#y\",z\r\n", CsvWriter.record(listOf("a", "b, c", "d \"e\"", " x", "#y", "z")))
        assertEquals("\"1\",\"two\"\r\n", CsvWriter.record(listOf("1", "two"), quoteAll = true))
    }

    @Test
    fun theByteOrderMarkIsRemovedSoTheFirstHeaderNameIsCorrect() {
        val decoded = ImportText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "name,x\nA,1\n".encodeToByteArray())
        assertIs<ImportText.Decoded.Text>(decoded)
        val table = (CsvTable.read(decoded.text) as CsvTable.Companion.Read.Table).table
        assertEquals(listOf("name", "x"), table.header)
    }

    @Test
    fun theHeaderNamesAreTrimmedAndInLowercase() {
        val table = (CsvTable.read(" Name , X\nA,1\n") as CsvTable.Companion.Read.Table).table
        assertEquals(listOf("name", "x"), table.header)
        assertEquals(2, table.records.single().row)
    }

    @Test
    fun theMetadataBlockIsReadAndTheDeviceValueIsIgnored() {
        val text = Fixtures.text("subdatums-format2-mill.csv")
        val table = (CsvTable.read(text) as CsvTable.Companion.Read.Table).table
        val metadata = table.metadata
        assertEquals(FileSource.TouchDro, metadata.source)
        assertEquals("sub-datums", metadata.kind)
        assertEquals(2, metadata.format)
        assertEquals("3.32.00, Build 2026-09-20", metadata.app)
        assertEquals("2026-10-11 14:35:12", metadata.exported)
        assertEquals("1", metadata.profileId)
        assertEquals("Bridgeport Mill", metadata.profile)
        assertEquals(MachineType.VerticalMill, metadata.machine)
        assertEquals(false, metadata.metric)
        // The type has no device field, so the value cannot be kept.
        assertFalse(metadata.toString().contains("6f1c2d4e"))
        // The row numbers do not count the metadata lines: row 1 is the header line.
        assertEquals(listOf(2, 3, 4), table.records.map { record -> record.row })
    }

    @Test
    fun anUnknownKeyAndABlankValueAreIgnored() {
        val metadata = CsvMetadata.parse(listOf("# colour: red", "# profile:", "#no colon", "# Profile: Shop"))
        assertEquals(CsvMetadata(profile = "Shop"), metadata)
    }

    @Test
    fun theUnitWordsAreReadInAnyCase() {
        listOf("mm", "MM", "Metric", "millimeter", "Millimeters", "MILLIMETRE", "millimetres").forEach { word ->
            assertEquals(true, CsvMetadata.parseUnits(word), word)
        }
        listOf("in", "IN", "Inch", "inches", "Imperial", "\"").forEach { word ->
            assertEquals(false, CsvMetadata.parseUnits(word), word)
        }
        assertNull(CsvMetadata.parseUnits("cubits"))
    }

    @Test
    fun aFormatAboveTwoIsRefused() {
        val read = CsvTable.read("# touchdro: tools\n# format: 3\nname,metric\n")
        assertIs<CsvTable.Companion.Read.Refused>(read)
        assertEquals("A newer version made this file (format 3). Update SuperDRO to import it.", read.problem.describe())
    }

    @Test
    fun aFileWithSemicolonsIsRefused() {
        val read = CsvTable.read("name;x;y\nA;1;2\n")
        assertIs<CsvTable.Companion.Read.Refused>(read)
        assertEquals(MessageKey.FileSemicolon, read.problem.message.key)
    }

    @Test
    fun anEmptyFileAndABinaryFileAreRefused() {
        assertEquals("The file has no content.", (ImportText.decode("  \n".encodeToByteArray()) as ImportText.Decoded.Refused).problem.describe())
        assertEquals("The file is not a text file.", (ImportText.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)) as ImportText.Decoded.Refused).problem.describe())
        assertEquals(
            "The file is too large to import. The limit is 1000000 bytes.",
            (ImportText.decode(ByteArray(ImportText.MAX_BYTES + 1) { 'a'.code.toByte() }) as ImportText.Decoded.Refused).problem.describe(),
        )
    }

    @Test
    fun theNumberFormatOf33200HasOneToFiveOrSixDecimals() {
        assertEquals("6.35", CsvNumbers.formatCompact(6350.0, metric = true))
        assertEquals("0.25", CsvNumbers.formatCompact(6350.0, metric = false))
        assertEquals("6.0", CsvNumbers.formatCompact(6000.0, metric = true))
        assertEquals("-12.5", CsvNumbers.formatCompact(-12500.0, metric = true))
        assertEquals("0.0", CsvNumbers.formatCompact(0.0, metric = false))
        assertEquals("0.00001", CsvNumbers.formatCompact(0.01, metric = true))
        assertEquals("0.000001", CsvNumbers.formatCompact(0.0254, metric = false))
    }

    @Test
    fun theReadoutFormatGivesTheDecimalsOfTheSubDatumExport() {
        assertEquals("1.0000", CsvNumbers.formatReadout(25400.0, metric = false, ReadoutFormat.Format3))
        assertEquals("1.000", CsvNumbers.formatReadout(25400.0, metric = false, ReadoutFormat.Format2))
        assertEquals("1.00000", CsvNumbers.formatReadout(25400.0, metric = false, ReadoutFormat.Format5))
        assertEquals("61.725", CsvNumbers.formatReadout(61725.0, metric = true, ReadoutFormat.Format3))
        assertEquals("61.74", CsvNumbers.formatReadout(61735.0, metric = true, ReadoutFormat.Format2))
        // Half up toward +∞, as Java Math.round: 12345.5 gives 12346, −12345.5 gives −12345.
        assertEquals("12346", CsvNumbers.formatFixed(12345.5, 0))
        assertEquals("-12345", CsvNumbers.formatFixed(-12345.5, 0))
        // Never "-0.0000".
        assertEquals("0.0000", CsvNumbers.formatReadout(-0.4, metric = false, ReadoutFormat.Format3))
    }

    @Test
    fun aPlainNumberCanHaveADecimalComma() {
        assertEquals(6.0, CsvNumbers.parsePlain("6,0"))
        assertEquals(-0.25, CsvNumbers.parsePlain(" -0.25 "))
        assertEquals(0.5, CsvNumbers.parsePlain(",5"))
        assertNull(CsvNumbers.parsePlain("1/4"))
        assertNull(CsvNumbers.parsePlain("abc"))
    }

    @Test
    fun theToleranceOfThePointMatchComesFromTheDecimals() {
        assertEquals(12.7, CsvNumbers.tolerance("1.250", metric = false), 1e-9)
        assertEquals(500.0, CsvNumbers.tolerance("12", metric = true), 1e-9)
        assertEquals(12700.0, CsvNumbers.tolerance("3", metric = false), 1e-9)
        assertEquals(0.5, CsvNumbers.tolerance("10,000", metric = true), 1e-9)
    }

    @Test
    fun theExportFileNamesFollowTheRule() {
        val time = LocalDateTime(2026, 10, 11, 14, 30, 5)
        assertEquals("superdro-tools-Bridgeport_Mill-mill-20261011_143005.csv", ExportFileNames.profileCsv("tools", "Bridgeport Mill", MachineType.HorizontalMill, time))
        assertEquals("superdro-sub_datums-South_Bend-lathe-20261011_143005.csv", ExportFileNames.profileCsv("sub-datums", "South Bend", MachineType.Lathe, time))
        assertEquals("superdro-profile-Bridgeport_2-20261011_143005.json", ExportFileNames.name(listOf("profile", "Bridgeport #2"), time, "json"))
        assertEquals("superdro-adapter_profile-20261011_143005.tdap", ExportFileNames.name(listOf("adapter-profile", "--"), time, "tdap"))
    }

    @Test
    fun theTimeTextsHaveTheFileFormats() {
        assertEquals("2026-10-11 14:30:05", CONTEXT.exportedText)
        assertEquals("SuperDRO 1.0.0", CONTEXT.appText)
        assertEquals("2026-10-11 07:30:05 -0700", FileTimes.formatWithOffset(FIXED_TIME, TimeZone.of("America/Los_Angeles")))
        assertEquals(FIXED_TIME, FileTimes.parse("2026-10-11 14:30:05", TimeZone.UTC))
        assertNull(FileTimes.parse("11.10.2026", TimeZone.UTC))
        assertTrue(MachineNames.parse("lathe") == MachineType.Lathe)
    }
}
