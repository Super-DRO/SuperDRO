package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolGeometry
import org.superdro.core.model.ToolKind
import org.superdro.core.store.files.tool.ToolImportContext
import org.superdro.core.store.files.tool.ToolImportPreview
import org.superdro.core.store.files.tool.ToolJsonImporter

/** The tool import from a JSON list: the cases of [ToolCsvTest] in JSON form. */
class ToolJsonTest {

    private val importer = ToolJsonImporter(now = { FIXED_TIME }, parseNumber = TestEvaluator::evaluate, ids = sequentialIds())
    private val mill = ToolImportContext("p1", MachineType.VerticalMill)
    private val lathe = ToolImportContext("p2", MachineType.Lathe)

    private fun rows(text: String): List<JsonObject> = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

    private fun tools(preview: ToolImportPreview): List<Tool> = preview.rows.mapNotNull { it.tool }

    /** The rows of `tools-3.25.03-mill.csv`: booleans, texts, and numbers in both forms. */
    private val millRows = rows(
        """
        [
          {"name": "1/4 End Mill", "description": "2 flute HSS", "metric": false, "diameter": 0.2500, "tooth_count": 2, "z_offset": 1.2500},
          {"name": "1/2 End Mill", "description": "4 flute, carbide", "metric": "no", "diameter": "0.5000", "tooth_count": "4", "z_offset": "0.8750"},
          {"name": "6 mm Drill", "metric": "yes", "diameter": 6.000, "tooth_count": 2.0, "z_offset": -12.500}
        ]
        """,
    )

    @Test
    fun aMillListImportsWithTheUnitsOfEachRow() {
        val preview = importer.read(millRows, mill)
        assertTrue(preview.problems.isEmpty(), preview.problems.toString())
        val tools = tools(preview)
        assertEquals(listOf("1/4 End Mill", "1/2 End Mill", "6 mm Drill"), tools.map(Tool::name))
        assertEquals(listOf("2 flute HSS", "4 flute, carbide", ""), tools.map(Tool::description))
        assertEquals(listOf(false, false, true), tools.map(Tool::metric))
        assertEquals(listOf(6350.0, 12700.0, 6000.0), tools.map(Tool::diameterUm))
        assertEquals(listOf(2, 4, 2), tools.map(Tool::toothCount))
        assertEquals(listOf(31750.0, 22225.0, -12500.0), tools.map(Tool::zOffsetUm))
        assertTrue(tools.all { it.kind == ToolKind.Mill })
        assertEquals(listOf(ImportOperation.UpdateMatching, ImportOperation.AddAsNew, ImportOperation.ReplaceAll), preview.operations)
        val plan = preview.plan()
        assertEquals("Tool import finished: 3 tools added, 0 tools updated.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun aLatheListReadsInternalAndTheOffsets() {
        val preview = importer.read(
            rows(
                """
                [
                  {"id": 4, "name": "Boring Bar", "description": "3/8 bar", "metric": false, "internal": true, "x_offset": -0.125, "z_offset": 0.375},
                  {"id": "1", "name": "OD Turning", "description": "CCMT insert", "metric": "No", "internal": "NO", "x_offset": 0.0}
                ]
                """,
            ),
            lathe,
        )
        assertTrue(preview.problems.isEmpty(), preview.problems.toString())
        val tools = tools(preview)
        assertEquals(listOf("Boring Bar", "OD Turning"), tools.map(Tool::name))
        assertEquals(listOf(true, false), tools.map(Tool::internal))
        assertEquals(listOf(false, false), tools.map(Tool::metric))
        assertEquals(listOf(-3175.0, 0.0), tools.map(Tool::xOffsetUm))
        // A blank Z offset of a lathe tool is 0.
        assertEquals(listOf(9525.0, 0.0), tools.map(Tool::zOffsetUm))
        assertTrue(tools.all { it.kind == ToolKind.Lathe && it.toothCount == 1 })
        assertEquals(listOf("4", "1"), preview.rows.map { it.id })
    }

    @Test
    fun anExpressionAndADecimalCommaAreAccepted() {
        val preview = importer.read(
            rows(
                """
                [
                  {"name": "1/4 End Mill", "description": "2 flute HSS", "metric": "no", "diameter": "1/4", "tooth_count": 2, "z_offset": 1.25},
                  {"name": "1/2 End Mill, carbide", "description": "4 flute", "metric": "no", "diameter": 0.5, "tooth_count": 4},
                  {"name": "6 mm Drill", "metric": "YES", "diameter": "6,0", "tooth_count": 2, "z_offset": -12.5}
                ]
                """,
            ),
            mill,
        )
        val tools = tools(preview)
        assertEquals(listOf("1/4 End Mill", "6 mm Drill"), tools.map(Tool::name))
        assertEquals(listOf(6350.0, 6000.0), tools.map(Tool::diameterUm))
        assertEquals(listOf(false, true), tools.map(Tool::metric))
        // A mill Z offset is a required number.
        assertEquals(listOf("Row 2: The value of “z_offset” is blank."), preview.rows.flatMap { row -> row.problems.map { it.describe() } })
    }

    @Test
    fun theRowRulesGiveTheExactTexts() {
        val preview = importer.read(
            rows(
                """
                [
                  {"name": "Drill A", "metric": false, "diameter": 0.25, "tooth_count": 2.0, "z_offset": 0},
                  {"name": "Drill A", "metric": false, "diameter": 0.25, "tooth_count": 2, "z_offset": 0},
                  {"name": "Drill B", "metric": "maybe", "diameter": 0.25, "tooth_count": 2, "z_offset": 0},
                  {"name": "Drill C", "metric": false, "diameter": 0, "tooth_count": 2, "z_offset": 0},
                  {"name": "Drill D", "metric": false, "diameter": 0.25, "tooth_count": 2.5, "z_offset": 0},
                  {"name": "", "metric": false, "diameter": 0.25, "tooth_count": 2, "z_offset": 0}
                ]
                """,
            ),
            mill,
        )
        assertEquals(listOf(2), tools(preview).map(Tool::toothCount))
        assertEquals(
            listOf(
                "Row 2: Tool offset with this name already exists in this file.",
                "Row 3: The value of “metric” must be yes or no: “maybe”.",
                "Row 4: The diameter must not be 0.",
                "Row 5: The tooth count must be a whole number, 0 or more: “2.5”.",
                "Row 6: Name is blank",
            ),
            preview.rows.flatMap { row -> row.problems.map { it.describe() } },
        )
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
    }

    @Test
    fun theLatheListChecksInternalAndDuplicateNames() {
        val preview = importer.read(
            rows(
                """
                [
                  {"name": "A", "metric": false, "internal": "maybe", "x_offset": 0, "z_offset": 0},
                  {"name": "B", "metric": false, "internal": true},
                  {"name": "B", "metric": true, "internal": false, "x_offset": 1, "z_offset": 1}
                ]
                """,
            ),
            lathe,
        )
        assertEquals(
            listOf("Row 1: The value of “internal” must be yes or no: “maybe”.", "Row 3: Tool offset with this name already exists in this file."),
            preview.rows.flatMap { row -> row.problems.map { it.describe() } },
        )
        assertEquals(listOf(0.0 to 0.0), tools(preview).map { it.xOffsetUm to it.zOffsetUm })
    }

    @Test
    fun aRowUpdatesTheLibraryToolWithItsIdOrItsName() {
        val created = Instant.parse("2025-01-01T00:00:00Z")
        val geometry = ToolGeometry()
        val library = listOf(
            Tool(id = "t-1", name = "1/4 End Mill", diameterUm = 1.0, geometry = geometry, createdOn = created, updatedOn = created),
            Tool(id = "t-2", name = "Other", createdOn = created, updatedOn = created),
        )
        val plan = importer.read(millRows, mill.copy(library = library)).plan(ImportOperation.UpdateMatching)
        val updated = plan.update.single()
        assertEquals("t-1", updated.id)
        assertEquals(6350.0, updated.diameterUm)
        assertEquals(geometry, updated.geometry)
        assertEquals(created, updated.createdOn)
        assertEquals(FIXED_TIME, updated.updatedOn)
        assertEquals(2, plan.add.size)
        assertEquals("Tool import finished: 2 tools added, 1 tools updated.", ImportTexts.English.text(plan.finishedMessage))

        val byId = importer.read(
            rows(
                """
                [
                  {"id": "t-2", "name": "Renamed", "metric": false, "diameter": 0.5, "tooth_count": 3, "z_offset": 0},
                  {"id": "t-2", "name": "Again", "metric": false, "diameter": 0.5, "tooth_count": 3, "z_offset": 0}
                ]
                """,
            ),
            mill.copy(library = library),
        )
        val idPlan = byId.plan(ImportOperation.UpdateMatching)
        assertEquals(listOf("t-2" to "Renamed"), idPlan.update.map { it.id to it.name })
        assertEquals(listOf("Row 2: An earlier row has the same ID “t-2”."), idPlan.preview.skipped.flatMap { row -> row.problems.map { it.describe() } })

        val addAll = byId.plan(ImportOperation.AddAsNew)
        assertEquals(2, addAll.add.size)
        assertTrue(addAll.update.isEmpty())

        val replace = importer.read(millRows, mill.copy(library = library)).plan(ImportOperation.ReplaceAll)
        assertEquals(listOf("t-1", "t-2"), replace.deleteIds)
        assertEquals(3, replace.add.size)
    }

    @Test
    fun aSubDatumListMissingColumnsAndAnEmptyListAreRefused() {
        assertEquals(
            listOf("The file contains “sub-datums”. This import needs “tools”."),
            importer.read(rows("""[{"name": "Hole 1", "x": 1.0, "y": 0.5, "tags": "holes"}]"""), mill).problems.map { it.describe() },
        )
        assertEquals(
            listOf("The file contains “sub-datums”. This import needs “tools”."),
            importer.read(rows("""[{"name": "Hole 1", "x": 1.0}]"""), mill).problems.map { it.describe() },
        )
        assertEquals(
            listOf("The header does not have the required column “diameter”.", "The header does not have the required column “tooth_count”."),
            importer.read(rows("""[{"name": "Boring Bar", "metric": false, "internal": true, "x_offset": -0.125, "z_offset": 0.375}]"""), mill).problems.map { it.describe() },
        )
        assertEquals(listOf("The file has no data rows."), importer.read(emptyList(), mill).problems.map { it.describe() })
        val nothing = importer.read(rows("""[{"name": "", "metric": false, "diameter": 1, "tooth_count": 2, "z_offset": 0}]"""), mill)
        assertEquals(listOf("No row of the file can be imported."), nothing.problems.map { it.describe() })
        assertFalse(nothing.usable)
    }
}
