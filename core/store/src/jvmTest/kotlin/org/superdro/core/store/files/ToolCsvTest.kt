package org.superdro.core.store.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolGeometry
import org.superdro.core.model.ToolKind
import org.superdro.core.store.files.tool.ToolCsvExporter
import org.superdro.core.store.files.tool.ToolCsvImporter
import org.superdro.core.store.files.tool.ToolImportContext

/** The tool CSV import and export. */
class ToolCsvTest {

    private val importer = ToolCsvImporter(TestEvaluator::evaluate, now = { FIXED_TIME }, ids = sequentialIds())
    private val mill = ToolImportContext("p1", MachineType.VerticalMill)
    private val lathe = ToolImportContext("p2", MachineType.Lathe)

    private fun tools(preview: org.superdro.core.store.files.tool.ToolImportPreview): List<Tool> = preview.rows.mapNotNull { it.tool }

    @Test
    fun aMillFileOf32503ImportsWithTheUnitsOfEachRow() {
        val preview = importer.read(Fixtures.bytes("tools-3.25.03-mill.csv"), mill)
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
    fun aLatheFileWithEightValuesReadsTheEighthValueAsTheZOffset() {
        val preview = importer.read(Fixtures.bytes("tools-3.25.03-lathe-8values.csv"), lathe)
        assertEquals(
            listOf("Some rows have one value more than the header. The import reads the last value as the Z offset."),
            preview.problems.map { it.describe() },
        )
        val tools = tools(preview)
        assertEquals(listOf("Boring Bar", "OD Turning"), tools.map(Tool::name))
        assertEquals(listOf(true, false), tools.map(Tool::internal))
        assertEquals(listOf(false, false), tools.map(Tool::metric))
        assertEquals(listOf(-3175.0, 0.0), tools.map(Tool::xOffsetUm))
        assertEquals(listOf(9525.0, -12700.0), tools.map(Tool::zOffsetUm))
        assertTrue(tools.all { it.kind == ToolKind.Lathe && it.toothCount == 1 })
    }

    @Test
    fun theFormat2FilesOf33200Import() {
        val millTools = tools(importer.read(Fixtures.bytes("tools-format2-mill.csv"), mill))
        assertEquals(listOf("6 mm Drill", "1/4 End Mill"), millTools.map(Tool::name))
        assertEquals(listOf(6000.0, 6350.0), millTools.map(Tool::diameterUm))
        assertEquals(listOf(-12500.0, 31750.0), millTools.map(Tool::zOffsetUm))

        val latheTools = importer.read(Fixtures.bytes("tools-format2-lathe.csv"), lathe)
        assertTrue(latheTools.problems.isEmpty(), latheTools.problems.toString())
        assertEquals(listOf(-3175.0, 0.0), tools(latheTools).map(Tool::xOffsetUm))
        assertEquals(listOf(9525.0, 0.0), tools(latheTools).map(Tool::zOffsetUm))
        assertEquals(listOf(true, false), tools(latheTools).map(Tool::internal))
    }

    @Test
    fun aHandMadeFileWithAByteOrderMarkExpressionsAndADecimalComma() {
        val preview = importer.read(Fixtures.bytes("tools-handmade-mill.csv"), mill)
        val tools = tools(preview)
        assertEquals(listOf("1/4 End Mill", "6 mm Drill"), tools.map(Tool::name))
        assertEquals(listOf(6350.0, 6000.0), tools.map(Tool::diameterUm))
        assertEquals(listOf(false, true), tools.map(Tool::metric))
        // A mill Z offset is a required number.
        assertEquals(listOf("Row 3: The value of “z_offset” is blank."), preview.rows.flatMap { row -> row.problems.map { it.describe() } })
    }

    @Test
    fun theRowRulesGiveTheExactTexts() {
        val preview = importer.read(Fixtures.bytes("tools-rules-mill.csv"), mill)
        assertEquals(listOf(2), tools(preview).map(Tool::toothCount))
        assertEquals(
            listOf(
                "Row 3: Tool offset with this name already exists in this file.",
                "Row 4: The value of “metric” must be yes or no: “maybe”.",
                "Row 5: The diameter must not be 0.",
                "Row 6: The tooth count must be a whole number, 0 or more: “2.5”.",
                "Row 7: Name is blank",
            ),
            preview.rows.flatMap { row -> row.problems.map { it.describe() } },
        )
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
    }

    @Test
    fun theLatheFileChecksInternalAndDuplicateNames() {
        val text = "name,metric,internal,x_offset,z_offset\nA,no,maybe,0,0\nB,no,yes,,\nB,yes,no,1,1\n"
        val preview = importer.read(text, lathe)
        assertEquals(
            listOf("Row 2: The value of “internal” must be yes or no: “maybe”.", "Row 4: Tool offset with this name already exists in this file."),
            preview.rows.flatMap { row -> row.problems.map { it.describe() } },
        )
        // A blank X or Z offset of a lathe tool is 0.
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
        val plan = importer.read(Fixtures.bytes("tools-3.25.03-mill.csv"), mill.copy(library = library)).plan(ImportOperation.UpdateMatching)
        val updated = plan.update.single()
        assertEquals("t-1", updated.id)
        assertEquals(6350.0, updated.diameterUm)
        assertEquals(geometry, updated.geometry)
        assertEquals(created, updated.createdOn)
        assertEquals(FIXED_TIME, updated.updatedOn)
        assertEquals(2, plan.add.size)
        assertEquals("Tool import finished: 2 tools added, 1 tools updated.", ImportTexts.English.text(plan.finishedMessage))

        val byId = importer.read("id,name,metric,diameter,tooth_count,z_offset\nt-2,Renamed,no,0.5,3,0\nt-2,Again,no,0.5,3,0\n", mill.copy(library = library))
        val idPlan = byId.plan(ImportOperation.UpdateMatching)
        assertEquals(listOf("t-2" to "Renamed"), idPlan.update.map { it.id to it.name })
        assertEquals(listOf("Row 3: An earlier row has the same ID “t-2”."), idPlan.preview.skipped.flatMap { row -> row.problems.map { it.describe() } })

        val addAll = byId.plan(ImportOperation.AddAsNew)
        assertEquals(2, addAll.add.size)
        assertTrue(addAll.update.isEmpty())

        val replace = importer.read(Fixtures.bytes("tools-3.25.03-mill.csv"), mill.copy(library = library)).plan(ImportOperation.ReplaceAll)
        assertEquals(listOf("t-1", "t-2"), replace.deleteIds)
        assertEquals(3, replace.add.size)
    }

    @Test
    fun aSubDatumFileAndMissingColumnsAreRefused() {
        assertEquals(
            listOf("The file contains “sub-datums”. This import needs “tools”."),
            importer.read(Fixtures.bytes("subdatums-3.25.03-mill-inch.csv"), mill).problems.map { it.describe() },
        )
        assertEquals(
            listOf("The header does not have the required column “diameter”.", "The header does not have the required column “tooth_count”."),
            importer.read(Fixtures.bytes("tools-format2-lathe.csv"), mill).problems.map { it.describe() },
        )
    }

    private val millProfile = MachineProfile.create(MachineType.VerticalMill).copy(id = "p1", name = "Bridgeport Mill")
    private val latheProfile = MachineProfile.create(MachineType.Lathe).copy(id = "p2", name = "South Bend")
    private val library = listOf(
        Tool(id = "t1", name = "1/4 End Mill", description = "2 flute HSS", metric = false, diameterUm = 6350.0, toothCount = 2, zOffsetUm = 31750.0),
        Tool(id = "t2", name = "6 mm Drill", metric = true, diameterUm = 6000.0, toothCount = 2, zOffsetUm = -12500.0),
        Tool(id = "t3", kind = ToolKind.Lathe, name = "Boring Bar", description = "3/8 bar", internal = true, xOffsetUm = -3175.0, zOffsetUm = 9525.0, toothCount = 1),
    )

    @Test
    fun theExportWritesSevenValuesAndTheSuperDroMetadata() {
        val millFile = ToolCsvExporter.export(millProfile, library, CONTEXT)
        assertEquals(
            listOf(
                "# superdro: tools",
                "# format: 2",
                "# app: SuperDRO 1.0.0",
                "# exported: 2026-10-11 14:30:05",
                "# profile-id: p1",
                "# profile: Bridgeport Mill",
                "# machine: VERTICAL_MILL",
                "id,name,description,metric,diameter,tooth_count,z_offset",
                "t1,1/4 End Mill,2 flute HSS,no,0.25,2,1.25",
                "t2,6 mm Drill,,yes,6.0,2,-12.5",
            ).joinToString("\r\n", postfix = "\r\n"),
            millFile.text,
        )
        assertEquals("superdro-tools-Bridgeport_Mill-mill-20261011_143005.csv", millFile.fileName)
        assertFalse("device" in millFile.text)

        val latheFile = ToolCsvExporter.export(latheProfile, library, CONTEXT)
        val rows = latheFile.text.split("\r\n")
        assertTrue("id,name,description,metric,internal,x_offset,z_offset" in rows)
        assertTrue("t3,Boring Bar,3/8 bar,no,yes,-0.125,0.375" in rows, rows.toString())
        assertEquals("superdro-tools-South_Bend-lathe-20261011_143005.csv", latheFile.fileName)

        val selected = ToolCsvExporter.export(millProfile, library, CONTEXT, selectedIds = setOf("t2")).text.split("\r\n").filter { it.startsWith("t") }
        assertEquals(listOf("t2,6 mm Drill,,yes,6.0,2,-12.5"), selected)
    }

    @Test
    fun anExportImportsAgainWithNoChange() {
        listOf(millProfile to mill, latheProfile to lathe).forEach { (profile, context) ->
            val kind = if (profile.machineType.isLathe) ToolKind.Lathe else ToolKind.Mill
            val own = library.filter { it.kind == kind }
            val file = ToolCsvExporter.export(profile, own, CONTEXT)
            val preview = importer.read(file.bytes(), context.copy(library = own))
            assertTrue(preview.problems.isEmpty(), preview.problems.toString())
            val plan = preview.plan(ImportOperation.UpdateMatching)
            assertTrue(plan.add.isEmpty())
            assertEquals(own.map { it.copy(updatedOn = FIXED_TIME) }, plan.update.map { it.copy(createdOn = own.first { o -> o.id == it.id }.createdOn) })
        }
    }
}
