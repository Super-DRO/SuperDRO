package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.store.files.subdatum.SubDatumCsvExporter
import org.superdro.core.store.files.subdatum.SubDatumCsvImporter
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.files.subdatum.SubDatumItem

/** The sub-datum CSV import and export. */
class SubDatumCsvTest {

    private val importer = SubDatumCsvImporter(TestEvaluator::evaluate, now = { FIXED_TIME }, ids = sequentialIds())

    private fun mill(metric: Boolean = false, subDatums: List<SubDatum> = emptyList(), guides: List<Guide> = emptyList(), unitsOverride: Boolean? = null) =
        SubDatumImportContext("p1", MachineType.VerticalMill, metric, subDatums, guides, unitsOverride)

    private fun lathe(metric: Boolean = true) = SubDatumImportContext("p2", MachineType.Lathe, metric)

    private fun points(items: List<SubDatumItem?>): List<SubDatum> = items.filterIsInstance<SubDatumItem.Point>().map(SubDatumItem.Point::subDatum)

    @Test
    fun aMillFileOf32503ImportsWithExpressionsAndInchUnits() {
        val preview = importer.read(Fixtures.bytes("subdatums-3.25.03-mill-inch.csv"), mill())
        assertTrue(preview.usable)
        val subDatums = points(preview.rows.map { it.item })
        assertEquals(listOf("Hole 1", "Hole 2", "Pocket, corner"), subDatums.map(SubDatum::name))
        assertEquals(listOf("Left mounting hole", "", "Quoted name with comma"), subDatums.map(SubDatum::note))
        assertEquals(listOf(listOf("holes", "left"), listOf("holes"), listOf("pocket")), subDatums.map(SubDatum::tags))
        assertEquals(listOf(25400.0, 50800.0, 82550.0), subDatums.map { it.x })
        assertEquals(listOf(12700.0, 12700.0, 31750.0), subDatums.map { it.y })
        assertEquals(listOf(null, -6350.0, null), subDatums.map { it.z })
        // The file gives no unit: the metric default of the profile (inch) applies.
        assertEquals(listOf("The file does not give its unit. The import uses the unit of the profile (inch)."), preview.problems.map { it.describe() })
        assertEquals(
            listOf(ImportOperation.AddAsNew, ImportOperation.AddNewOnly, ImportOperation.MergeMatching, ImportOperation.ReplaceAll),
            preview.operations,
        )
        val plan = preview.plan()
        assertEquals(3, plan.addSubDatums.size)
        assertEquals("Sub-datum import finished: 3 points added, 0 points updated, 0 guides added.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun aLatheFileReadsXAndZAndTheStoredXIsTheFileValue() {
        val preview = importer.read(Fixtures.bytes("subdatums-3.25.03-lathe-mm.csv"), lathe())
        val subDatums = points(preview.rows.map { it.item })
        assertEquals(listOf("Shoulder", "Groove"), subDatums.map(SubDatum::name))
        assertEquals(listOf(12500.0, 10000.0), subDatums.map { it.x })
        assertEquals(listOf(-30000.0, -45500.0), subDatums.map { it.z })
        assertEquals(listOf(null, null), subDatums.map { it.y })
        assertEquals(listOf("2 mm groove"), subDatums.drop(1).map(SubDatum::note))
    }

    @Test
    fun onALatheZIsRequiredAndYIsNotRead() {
        val preview = importer.read(Fixtures.bytes("subdatums-3.25.03-mill-inch.csv"), lathe(metric = false))
        val texts = preview.rows.flatMap { row -> row.problems.map { it.describe() } }
        assertEquals(listOf("Row 2: The value of “z” is blank.", "Row 4: The value of “z” is blank."), texts)
        assertTrue("This profile is a lathe. The import ignores the y values." in preview.problems.map { it.describe() })
        assertEquals(null, points(preview.rows.map { it.item }).single().y)
        // A row with an error makes REPLACE_ALL not available.
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
    }

    @Test
    fun aFormat2FileGivesItsUnitAndItsGuideRows() {
        val preview = importer.read(Fixtures.bytes("subdatums-format2-mill.csv"), mill(metric = true))
        assertTrue(preview.problems.isEmpty(), preview.problems.toString())
        assertFalse(preview.metric)
        val items = preview.rows.map { it.item }
        assertEquals(listOf("Point 1", "Grid - 2"), points(items).map(SubDatum::name))
        assertEquals(listOf(listOf(), listOf("Grid")), points(items).map(SubDatum::tags))
        assertEquals(-6350.0, points(items)[1].z)
        val guide = (items[2] as SubDatumItem.Line).guide
        assertEquals(AxisId.X, guide.axis)
        assertEquals(76200.0, guide.valueUm)
        // A TouchDRO file is not a file of this profile: ADD_AS_NEW is the default.
        assertEquals(ImportOperation.AddAsNew, preview.defaultOperation)
        val plan = preview.plan()
        assertEquals(1, plan.addGuides.size)
        assertEquals("Sub-datum import finished: 2 points added, 0 points updated, 1 guides added.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun aByteOrderMarkAndADecimalCommaAreAccepted() {
        listOf(importer, SubDatumCsvImporter(now = { FIXED_TIME })).forEach { reader ->
            val preview = reader.read(Fixtures.bytes("subdatums-bom-comma-lathe.csv"), lathe(metric = false))
            assertTrue(preview.usable, preview.problems.toString())
            assertTrue(preview.metric)
            val subDatums = points(preview.rows.map { it.item })
            assertEquals(listOf(12500.0, 10000.0), subDatums.map { it.x })
            assertEquals(listOf(-30000.0, -45500.0), subDatums.map { it.z })
        }
    }

    @Test
    fun theHeaderCanHaveAnyOrderAndOtherColumnsAndTheRowsGiveRowLists() {
        val preview = importer.read(Fixtures.bytes("subdatums-rules-mill.csv"), mill())
        val first = points(preview.rows.map { it.item }).single()
        assertEquals("Multi tag", first.name)
        assertEquals("note one", first.note)
        assertEquals(listOf("a", "b", "c"), first.tags)
        assertEquals(38100.0, first.x)
        assertEquals(null, first.y)
        val guides = preview.rows.mapNotNull { (it.item as? SubDatumItem.Line)?.guide }
        assertEquals(listOf(AxisId.Y to 19050.0, AxisId.Z to -12700.0), guides.map { it.axis to it.valueUm })
        val plan = preview.plan(ImportOperation.AddAsNew)
        assertEquals(
            listOf("Row 3: Name is blank", "Row 4: The value of “x” is not a number: “abc”."),
            plan.preview.skipped.flatMap { row -> row.problems.map { it.describe() } },
        )
        assertEquals(3, plan.preview.toImport.size)
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
    }

    @Test
    fun theImportReportListsTheSkippedRowsAndTheWarnings() {
        val report = importer.read(Fixtures.bytes("subdatums-rules-mill.csv"), mill()).plan(ImportOperation.AddAsNew).report.render()
        val expected = """
            Import report
            =============

            Imported: 3. Not imported: 2. Warnings: 1.

            --------------------------------------------------------
            Rows that the import skipped

            Row 3: Name is blank
            Row 4: The value of “x” is not a number: “abc”.

            --------------------------------------------------------
            Other warnings

            The file does not give its unit. The import uses the unit of the profile (inch).

        """.trimIndent()
        assertEquals(expected, report.toText())
    }

    @Test
    fun theUnitThatTheUserSelectsWinsWithAWarning() {
        val preview = importer.read(Fixtures.bytes("subdatums-format2-mill.csv"), mill(unitsOverride = true))
        assertTrue(preview.metric)
        assertEquals(listOf("The file gives the unit inch. The import uses the unit that you selected (mm)."), preview.problems.map { it.describe() })
        assertEquals(1000.0, points(preview.rows.map { it.item }).first().x)
    }

    @Test
    fun thePointMatchSkipsMergesAndFindsDuplicates() {
        val stored = SubDatum(id = "s1", name = "Old 1", note = "old note", tags = listOf("old"), x = 25390.0, y = 12700.0)
        val storedGuide = Guide(id = "g1", axis = AxisId.X, valueUm = 76200.0)
        val preview = importer.read(Fixtures.bytes("subdatums-format2-mill.csv"), mill(subDatums = listOf(stored), guides = listOf(storedGuide)))

        val addNewOnly = preview.plan(ImportOperation.AddNewOnly)
        assertEquals(listOf(RowAction.AlreadyHere, RowAction.Add, RowAction.AlreadyHere), (addNewOnly.preview.toImport + addNewOnly.preview.skipped).sortedBy { it.row }.map { it.action })
        assertEquals(listOf("Grid - 2"), addNewOnly.addSubDatums.map(SubDatum::name))
        assertTrue(addNewOnly.addGuides.isEmpty())

        val merge = preview.plan(ImportOperation.MergeMatching)
        val updated = merge.updateSubDatums.single()
        assertEquals("s1", updated.id)
        assertEquals("Point 1", updated.name)
        assertEquals("", updated.note)
        assertEquals(25390.0, updated.x)
        assertEquals(listOf("old"), updated.tags)
        assertEquals("Sub-datum import finished: 1 points added, 1 points updated, 0 guides added.", ImportTexts.English.text(merge.finishedMessage))

        val replace = preview.plan(ImportOperation.ReplaceAll)
        assertTrue(replace.deleteAll)
        assertEquals(2, replace.addSubDatums.size)

        val twice = importer.read("name,x,y\nA,1.0,2.0\nB,1.00,2.00\n", mill())
        val duplicate = twice.plan(ImportOperation.AddNewOnly)
        assertEquals(listOf("Row 3: An earlier row of the file has the same point."), duplicate.preview.skipped.flatMap { row -> row.problems.map { it.describe() } })
        assertEquals(2, twice.plan(ImportOperation.AddAsNew).addSubDatums.size)
    }

    @Test
    fun aToolFileIsNotASubDatumFile() {
        val preview = importer.read(Fixtures.bytes("tools-3.25.03-mill.csv"), mill())
        assertFalse(preview.usable)
        assertEquals(listOf("The file contains “tools”. This import needs “sub-datums”."), preview.problems.map { it.describe() })
        val missing = importer.read("name,note\nA,b\n", mill())
        assertEquals(listOf("The header does not have the required column “x”."), missing.problems.map { it.describe() })
        assertEquals(listOf("The file has no data rows."), importer.read("name,x\n", mill()).problems.map { it.describe() })
    }

    private val profile = MachineProfile.create(MachineType.VerticalMill).copy(id = "p1", name = "Bridgeport Mill", metricDefault = false)
    private val exported = listOf(
        SubDatum(id = "s1", name = "Point 1", x = 25400.0, y = 12700.0),
        SubDatum(id = "s2", name = "Hole, A", note = "n", tags = listOf("a", "b"), x = 50800.0, y = 12700.0, z = -6350.0),
    )
    private val exportedGuides = listOf(Guide(id = "g1", axis = AxisId.X, valueUm = 76200.0))

    @Test
    fun theExportWritesTheSuperDroMetadataAndTheDisplayFormatDecimals() {
        val file = SubDatumCsvExporter.export(profile, exported, exportedGuides, CONTEXT)
        val expected = listOf(
            "# superdro: sub-datums",
            "# format: 2",
            "# app: SuperDRO 1.0.0",
            "# exported: 2026-10-11 14:30:05",
            "# profile-id: p1",
            "# profile: Bridgeport Mill",
            "# machine: VERTICAL_MILL",
            "# units: inch",
            "name,note,tags,x,y,z",
            "Point 1,,,1.0000,0.5000,",
            "\"Hole, A\",n,a b,2.0000,0.5000,-0.2500",
            "Guide,,,3.0000,,",
        ).joinToString("\r\n", postfix = "\r\n")
        assertEquals(expected, file.text)
        assertEquals("superdro-sub_datums-Bridgeport_Mill-mill-20261011_143005.csv", file.fileName)
        assertEquals("text/csv", file.mimeType)
        assertFalse("device" in file.text)
        assertFalse(file.bytes().take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    }

    @Test
    fun theExportWritesTheSelectionElseAllShownRows() {
        val file = SubDatumCsvExporter.export(profile, exported, exportedGuides, CONTEXT, selectedIds = setOf("s2"))
        val rows = file.text.split("\r\n").filter { line -> line.isNotEmpty() && !line.startsWith("#") }
        assertEquals(listOf("name,note,tags,x,y,z", "\"Hole, A\",n,a b,2.0000,0.5000,-0.2500"), rows)
    }

    @Test
    fun theExportLeavesAnAxisEmptyWhenTheAxisIsOffOrTheMachineIsALathe() {
        val noY = profile.copy(y = profile.y.copy(enabled = false), display = profile.display.copy(readoutFormat = org.superdro.core.model.ReadoutFormat.Format2))
        val rows = SubDatumCsvExporter.export(noY, exported, emptyList(), CONTEXT).text.split("\r\n")
        assertTrue("Point 1,,,1.000,," in rows, rows.toString())
        val latheProfile = MachineProfile.create(MachineType.Lathe).copy(id = "p2", name = "South Bend", metricDefault = true)
        val latheRows = SubDatumCsvExporter.export(latheProfile, listOf(SubDatum(name = "S", x = 12500.0, y = 3000.0, z = -30000.0)), emptyList(), CONTEXT).text.split("\r\n")
        assertTrue("S,,,12.500,,-30.000" in latheRows, latheRows.toString())
        assertTrue("# units: mm" in latheRows)
    }

    @Test
    fun anExportImportsAgainWithNoChange() {
        val file = SubDatumCsvExporter.export(profile, exported, exportedGuides, CONTEXT)
        val preview = importer.read(file.bytes(), mill())
        assertTrue(preview.problems.isEmpty(), preview.problems.toString())
        // The file of this profile proposes MERGE_MATCHING first.
        assertEquals(ImportOperation.MergeMatching, preview.defaultOperation)
        val plan = preview.plan(ImportOperation.AddAsNew)
        assertEquals(exported.map { listOf(it.name, it.note, it.tags, it.x, it.y, it.z) }, plan.addSubDatums.map { listOf(it.name, it.note, it.tags, it.x, it.y, it.z) })
        assertEquals(exportedGuides.map { it.axis to it.valueUm }, plan.addGuides.map { it.axis to it.valueUm })
    }
}
