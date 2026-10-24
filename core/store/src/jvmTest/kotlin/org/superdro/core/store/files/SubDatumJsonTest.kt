package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.files.subdatum.SubDatumItem
import org.superdro.core.store.files.subdatum.SubDatumJsonImporter

/** The sub-datum import from a JSON list: the cases of [SubDatumCsvTest] in JSON form. */
class SubDatumJsonTest {

    private val importer = SubDatumJsonImporter(now = { FIXED_TIME }, parseNumber = TestEvaluator::evaluate, ids = sequentialIds())

    private fun rows(text: String): List<JsonObject> = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

    private fun mill(subDatums: List<SubDatum> = emptyList(), guides: List<Guide> = emptyList()) =
        SubDatumImportContext("p1", MachineType.VerticalMill, false, subDatums, guides)

    private val lathe = SubDatumImportContext("p2", MachineType.Lathe, true)

    private fun points(items: List<SubDatumItem?>): List<SubDatum> = items.filterIsInstance<SubDatumItem.Point>().map(SubDatumItem.Point::subDatum)

    /** The rows of `subdatums-3.25.03-mill-inch.csv`: numbers, texts, an expression, and a tag array. */
    private val millRows = rows(
        """
        [
          {"name": "Hole 1", "note": "Left mounting hole", "tags": ["holes", "left"], "x": 1.0000, "y": 0.5000, "z": null},
          {"name": "Hole 2", "tags": "holes", "x": "2.0000", "y": "0.5000", "z": -0.2500},
          {"name": "Pocket, corner", "note": "Quoted name with comma", "tags": ["pocket"], "x": "3+1/4", "y": 1.25}
        ]
        """,
    )

    @Test
    fun aMillListImportsWithExpressionsAndTheGivenUnit() {
        val preview = importer.read(millRows, metric = false, context = mill())
        assertTrue(preview.usable, preview.problems.toString())
        assertTrue(preview.problems.isEmpty(), preview.problems.toString())
        assertFalse(preview.metric)
        val subDatums = points(preview.rows.map { it.item })
        assertEquals(listOf("Hole 1", "Hole 2", "Pocket, corner"), subDatums.map(SubDatum::name))
        assertEquals(listOf("Left mounting hole", "", "Quoted name with comma"), subDatums.map(SubDatum::note))
        assertEquals(listOf(listOf("holes", "left"), listOf("holes"), listOf("pocket")), subDatums.map(SubDatum::tags))
        assertEquals(listOf(25400.0, 50800.0, 82550.0), subDatums.map { it.x })
        assertEquals(listOf(12700.0, 12700.0, 31750.0), subDatums.map { it.y })
        assertEquals(listOf(null, -6350.0, null), subDatums.map { it.z })
        assertEquals(
            listOf(
                ImportOperation.UpdateMatching, ImportOperation.AddAsNew, ImportOperation.AddNewOnly,
                ImportOperation.MergeMatching, ImportOperation.ReplaceAll,
            ),
            preview.operations,
        )
        val plan = preview.plan(ImportOperation.AddAsNew)
        assertEquals(3, plan.addSubDatums.size)
        assertEquals("Sub-datum import finished: 3 points added, 0 points updated, 0 guides added.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun aJsonNumberKeepsItsDecimalsForTheTolerance() {
        // 1.0000 has four decimals: the tolerance is 25400 / 10^4 / 2 = 1.27 µm. 1.0 has one: 1270 µm.
        val preview = importer.read(rows("""[{"name": "A", "x": 1.0000}, {"name": "B", "x": 1.0}]"""), metric = false, context = mill())
        val tolerances = preview.rows.map { (it.item as SubDatumItem.Point).tolerance.x }
        assertEquals(listOf(1.27, 1270.0), tolerances)
    }

    @Test
    fun aLatheListReadsXAndZAndIgnoresY() {
        val preview = importer.read(
            rows("""[{"name": "Shoulder", "x": 12.500, "z": -30.000}, {"name": "Groove", "note": "2 mm groove", "tags": "groove", "x": "10,0", "z": -45.5, "y": 3}]"""),
            metric = true,
            context = lathe,
        )
        val subDatums = points(preview.rows.map { it.item })
        assertEquals(listOf("Shoulder", "Groove"), subDatums.map(SubDatum::name))
        assertEquals(listOf(12500.0, 10000.0), subDatums.map { it.x })
        assertEquals(listOf(-30000.0, -45500.0), subDatums.map { it.z })
        assertEquals(listOf(null, null), subDatums.map { it.y })
        assertEquals(listOf("This profile is a lathe. The import ignores the y values."), preview.problems.map { it.describe() })
    }

    @Test
    fun onALatheZIsRequired() {
        val preview = importer.read(millRows, metric = false, context = lathe)
        val texts = preview.rows.flatMap { row -> row.problems.map { it.describe() } }
        assertEquals(listOf("Row 1: The value of “z” is blank.", "Row 3: The value of “z” is blank."), texts)
        assertEquals(null, points(preview.rows.map { it.item }).single().y)
        // A row with an error makes REPLACE_ALL not available.
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
    }

    @Test
    fun aGuideRowAndTheAngleImport() {
        val preview = importer.read(
            rows("""[{"name": "Point 1", "x": 1.0, "y": 0.5, "a": 90}, {"name": "Grid - 2", "tags": "Grid", "x": 2.0, "y": 0.5, "z": -0.25}, {"name": "Guide", "x": 3.0}]"""),
            metric = false,
            context = mill(),
        )
        assertTrue(preview.problems.isEmpty(), preview.problems.toString())
        val items = preview.rows.map { it.item }
        assertEquals(listOf("Point 1", "Grid - 2"), points(items).map(SubDatum::name))
        // 90 degrees is 324000 arc seconds.
        assertEquals(listOf(324_000.0, null), points(items).map { it.a })
        val guide = (items[2] as SubDatumItem.Line).guide
        assertEquals(AxisId.X, guide.axis)
        assertEquals(76200.0, guide.valueUm)
        val plan = preview.plan(ImportOperation.AddAsNew)
        assertEquals(1, plan.addGuides.size)
        assertEquals("Sub-datum import finished: 2 points added, 0 points updated, 1 guides added.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun theKeysCanHaveAnyCaseAndTheRowsGiveRowLists() {
        val preview = importer.read(
            rows(
                """
                [
                  {"Tags": "a b  c", "NAME": "Multi tag", "extra": "ignored", "x": 1.5, "note": "note one"},
                  {"x": 2, "y": 3},
                  {"z": -1, "name": "Bad number", "x": "abc", "y": 1},
                  {"name": "Guide", "y": 0.75},
                  {"z": -0.5, "name": "Guide"}
                ]
                """,
            ),
            metric = false,
            context = mill(),
        )
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
            listOf("Row 2: Name is blank", "Row 3: The value of “x” is not a number: “abc”."),
            plan.preview.skipped.flatMap { row -> row.problems.map { it.describe() } },
        )
        assertEquals(3, plan.preview.toImport.size)
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
        val report = plan.report.render().toText()
        assertTrue("Imported: 3. Not imported: 2. Warnings: 0." in report, report)
    }

    @Test
    fun thePointMatchSkipsMergesAndFindsDuplicates() {
        val stored = SubDatum(id = "s1", name = "Old 1", note = "old note", tags = listOf("old"), x = 25390.0, y = 12700.0)
        val storedGuide = Guide(id = "g1", axis = AxisId.X, valueUm = 76200.0)
        val preview = importer.read(
            rows("""[{"name": "Point 1", "x": 1.0, "y": 0.5}, {"name": "Grid - 2", "tags": "Grid", "x": 2.0, "y": 0.5, "z": -0.25}, {"name": "Guide", "x": 3.0}]"""),
            metric = false,
            context = mill(subDatums = listOf(stored), guides = listOf(storedGuide)),
        )

        val addNewOnly = preview.plan(ImportOperation.AddNewOnly)
        assertEquals(listOf(RowAction.AlreadyHere, RowAction.Add, RowAction.AlreadyHere), (addNewOnly.preview.toImport + addNewOnly.preview.skipped).sortedBy { it.row }.map { it.action })
        assertEquals(listOf("Grid - 2"), addNewOnly.addSubDatums.map(SubDatum::name))
        assertTrue(addNewOnly.addGuides.isEmpty())

        val merge = preview.plan(ImportOperation.MergeMatching)
        val updated = merge.updateSubDatums.single()
        assertEquals("s1", updated.id)
        assertEquals("Point 1", updated.name)
        assertEquals(25390.0, updated.x)
        assertEquals(listOf("old"), updated.tags)

        val replace = preview.plan(ImportOperation.ReplaceAll)
        assertTrue(replace.deleteAll)
        assertEquals(2, replace.addSubDatums.size)

        val twice = importer.read(rows("""[{"name": "A", "x": 1.0, "y": 2.0}, {"name": "B", "x": 1.00, "y": 2.00}]"""), metric = false, context = mill())
        val duplicate = twice.plan(ImportOperation.AddNewOnly)
        assertEquals(listOf("Row 2: An earlier row of the file has the same point."), duplicate.preview.skipped.flatMap { row -> row.problems.map { it.describe() } })
        assertEquals(2, twice.plan(ImportOperation.AddAsNew).addSubDatums.size)
    }

    @Test
    fun updateMatchingUpdatesTheStoredPointWithItsIdOrItsName() {
        val stored = listOf(
            SubDatum(id = "s1", name = "Hole 1", note = "old", tags = listOf("old"), x = 1.0, y = 2.0, groupId = 7L, createdOn = FIXED_TIME),
            SubDatum(id = "s2", name = "Hole 2", x = 3.0, createdOn = FIXED_TIME),
            SubDatum(id = "s3", name = "Other", x = 4.0, createdOn = FIXED_TIME),
        )
        val storedGuide = Guide(id = "g1", axis = AxisId.X, valueUm = 3_000.0)
        val preview = importer.read(
            rows(
                """
                [
                  {"id": "s1", "name": "Renamed", "tags": ["new"], "x": 10.0, "y": 20.0, "z": 30.0},
                  {"name": "Hole 2", "x": 5.0},
                  {"id": "s1", "name": "Again", "x": 1.0},
                  {"id": "unknown", "name": "New point", "x": 6.0},
                  {"name": "Guide", "x": 3.0},
                  {"name": "Guide", "y": 1.0}
                ]
                """,
            ),
            metric = true,
            context = mill(subDatums = stored, guides = listOf(storedGuide)),
        )
        assertEquals(ImportOperation.UpdateMatching, preview.defaultOperation)
        val plan = preview.plan()
        assertEquals(
            listOf(RowAction.Update, RowAction.Update, RowAction.Skip, RowAction.Add, RowAction.AlreadyHere, RowAction.Add),
            (plan.preview.toImport + plan.preview.skipped).sortedBy { it.row }.map { it.action },
        )
        val byId = plan.updateSubDatums.first { it.id == "s1" }
        // The update replaces the name, the note, the tags, and the coordinates. It keeps the group and the creation time.
        assertEquals(SubDatum(id = "s1", name = "Renamed", note = "", tags = listOf("new"), x = 10_000.0, y = 20_000.0, z = 30_000.0, groupId = 7L, createdOn = FIXED_TIME, updatedOn = FIXED_TIME), byId)
        val byName = plan.updateSubDatums.first { it.id == "s2" }
        assertEquals(5_000.0, byName.x)
        assertEquals(null, byName.y)
        assertEquals(listOf("Row 3: An earlier row has the same ID “s1”."), plan.preview.skipped.filter { it.action == RowAction.Skip }.flatMap { row -> row.problems.map { it.describe() } })
        assertEquals(listOf("New point"), plan.addSubDatums.map(SubDatum::name))
        assertEquals(listOf(AxisId.Y to 1_000.0), plan.addGuides.map { it.axis to it.valueUm })
        assertEquals("Sub-datum import finished: 1 points added, 2 points updated, 1 guides added.", ImportTexts.English.text(plan.finishedMessage))

        // The other operations ignore the id: ADD_AS_NEW adds each row with a new ID.
        val addAll = preview.plan(ImportOperation.AddAsNew)
        assertEquals(4, addAll.addSubDatums.size)
        assertTrue(addAll.addSubDatums.none { it.id == "s1" })
    }

    @Test
    fun aToolListMissingColumnsAndAnEmptyListAreRefused() {
        val tools = importer.read(rows("""[{"name": "Drill", "metric": true, "diameter": 6, "tooth_count": 2, "z_offset": 0}]"""), metric = true, context = mill())
        assertFalse(tools.usable)
        assertEquals(listOf("The file contains “tools”. This import needs “sub-datums”."), tools.problems.map { it.describe() })
        val missing = importer.read(rows("""[{"name": "A", "note": "b"}]"""), metric = true, context = mill())
        assertEquals(listOf("The header does not have the required column “x”."), missing.problems.map { it.describe() })
        assertEquals(listOf("The file has no data rows."), importer.read(emptyList(), metric = true, context = mill()).problems.map { it.describe() })
        val nothing = importer.read(rows("""[{"name": "", "x": 1}]"""), metric = true, context = mill())
        assertEquals(listOf("No row of the file can be imported."), nothing.problems.map { it.describe() })
        assertFalse(nothing.usable)
    }

    @Test
    fun anObjectValueIsNotANumber() {
        val preview = importer.read(rows("""[{"name": "A", "x": {"value": 1}}]"""), metric = true, context = mill())
        assertEquals(listOf("Row 1: The value of “x” is not a number: “{\"value\":1}”."), preview.rows.flatMap { row -> row.problems.map { it.describe() } })
    }
}
