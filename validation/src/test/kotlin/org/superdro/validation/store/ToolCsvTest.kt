package org.superdro.validation.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportTexts
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.tool.ToolCsvExporter
import org.superdro.core.store.files.tool.ToolCsvImporter
import org.superdro.core.store.files.tool.ToolImportContext
import org.superdro.core.store.files.tool.ToolImportPreview
import org.superdro.core.store.valueOrNull

/**
 * The tool CSV import and export. The
 * expected values come from the tool tables and from the example files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolCsvTest {

    private val mill = ToolImportContext(profileId = "mill-1", machineType = MachineType.VerticalMill)
    private val lathe = ToolImportContext(profileId = "lathe-1", machineType = MachineType.Lathe)
    private val importer = ToolCsvImporter(parseNumber = StoreFixtures.f9)
    private val harnesses = mutableListOf<StoreHarness>()

    private fun TestScope.store(): StoreHarness = StoreHarness(StandardTestDispatcher(testScheduler)).also(harnesses::add)

    @AfterTest
    fun closeAll() {
        harnesses.forEach(StoreHarness::close)
    }

    private fun tools(preview: ToolImportPreview): List<Tool> = preview.rows.map { row -> assertNotNull(row.tool, "row ${row.row}: ${row.problems}") }

    private fun assertUm(expected: Double, actual: Double, what: String) = assertEquals(expected, actual, 1e-6, what)

    @Test
    fun import_of_the_mill_file_of_data_storage_9_2() {
        val preview = importer.read(StoreFixtures.text("data-storage-9.2-mill.csv"), mill)
        assertTrue(preview.usable)
        val tools = tools(preview)
        assertEquals(listOf("1/4 End Mill", "1/2 End Mill", "6 mm Drill"), tools.map(Tool::name))
        assertEquals(listOf("2 flute HSS", "4 flute, carbide", ""), tools.map(Tool::description))
        assertEquals(listOf(false, false, true), tools.map(Tool::metric))
        // The metric column of each row sets its units: inch 25400 µm, mm 1000 µm.
        listOf(6350.0, 12700.0, 6000.0).zip(tools).forEach { (um, tool) -> assertUm(um, tool.diameterUm, tool.name) }
        assertEquals(listOf(2, 4, 2), tools.map(Tool::toothCount))
        listOf(31750.0, 22225.0, -12500.0).zip(tools).forEach { (um, tool) -> assertUm(um, tool.zOffsetUm, tool.name) }
        tools.forEach { tool -> assertEquals(ToolKind.Mill, tool.kind) }
        val plan = preview.plan()
        assertEquals("Tool import finished: 3 tools added, 0 tools updated.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun import_of_the_lathe_file_of_data_storage_9_2() {
        val preview = importer.read(StoreFixtures.text("data-storage-9.2-lathe.csv"), lathe)
        val tools = tools(preview)
        assertEquals(listOf("OD Turning", "Boring Bar", "Parting"), tools.map(Tool::name))
        assertEquals(listOf(false, true, false), tools.map(Tool::internal))
        listOf(0.0, -3175.0, 381.0).zip(tools).forEach { (um, tool) -> assertUm(um, tool.xOffsetUm, tool.name) }
        listOf(0.0, 9525.0, -1016.0).zip(tools).forEach { (um, tool) -> assertUm(um, tool.zOffsetUm, tool.name) }
        tools.forEach { tool ->
            assertEquals(ToolKind.Lathe, tool.kind)
            // The tool rules: the tooth count of a lathe tool is 1.
            assertEquals(1, tool.toothCount)
        }
    }

    @Test
    fun a_lathe_export_of_3_25_03_with_8_values_reads_the_8th_value_as_the_z_offset() {
        val preview = importer.read(StoreFixtures.text("data-storage-9.3-lathe-export.csv"), lathe)
        val tool = tools(preview).single()
        assertEquals("Boring Bar", tool.name)
        assertEquals("3/8 bar", tool.description)
        assertTrue(tool.internal)
        assertUm(-3175.0, tool.xOffsetUm, "x")
        // Not 1 in (the tooth count in the 7th column): 0.375 in.
        assertUm(9525.0, tool.zOffsetUm, "z")
    }

    @Test
    fun import_of_the_3_32_00_mill_export_with_the_metadata_block() {
        val preview = importer.read(StoreFixtures.text("data-storage-14.13.4-mill-export.csv"), mill)
        val metadata = preview.metadata
        assertEquals(FileSource.TouchDro, metadata.source)
        assertEquals("tools", metadata.kind)
        assertEquals(2, metadata.format)
        assertEquals("3.32.00, Build 2026-09-20", metadata.app)
        assertEquals("2026-10-11 14:30:05", metadata.exported)
        assertEquals("1", metadata.profileId)
        assertEquals("Bridgeport Mill", metadata.profile)
        assertEquals(MachineType.VerticalMill, metadata.machine)
        val tools = tools(preview)
        assertEquals(listOf("6 mm Drill", "1/4 End Mill"), tools.map(Tool::name))
        assertUm(6000.0, tools[0].diameterUm, "drill")
        assertUm(-12500.0, tools[0].zOffsetUm, "drill")
        assertUm(6350.0, tools[1].diameterUm, "end mill")
        assertUm(31750.0, tools[1].zOffsetUm, "end mill")
        // An import of a TouchDRO export file gives new IDs.
        tools.forEach { tool -> assertTrue(StoreFixtures.UUID_FORM.matches(tool.id), tool.id) }
    }

    @Test
    fun import_of_the_3_32_00_lathe_export_with_7_values() {
        val preview = importer.read(StoreFixtures.text("data-storage-14.13.4-lathe-export.csv"), lathe)
        assertEquals(MachineType.Lathe, preview.metadata.machine)
        val tools = tools(preview)
        assertEquals(listOf("Boring Bar", "OD Turning"), tools.map(Tool::name))
        assertUm(-3175.0, tools[0].xOffsetUm, "x")
        assertUm(9525.0, tools[0].zOffsetUm, "z")
        assertTrue(tools[0].internal)
        assertFalse(tools[1].internal)
    }

    @Test
    fun hand_made_file_with_an_expression_any_case_and_the_decimal_comma() {
        val preview = importer.read(StoreFixtures.text("data-storage-14.13.4-mill-hand.csv"), mill)
        val rows = preview.rows
        assertEquals(listOf(2, 3, 4), rows.map { it.row })
        val first = assertNotNull(rows[0].tool)
        assertUm(6350.0, first.diameterUm, "1/4")
        assertUm(31750.0, first.zOffsetUm, "1.25")
        // The Z offset of a mill tool is a required number. A blank value is an error.
        assertNull(rows[1].tool)
        assertTrue(rows[1].problems.any { it.severity == Severity.Error })
        val drill = assertNotNull(rows[2].tool)
        assertTrue(drill.metric, "YES in any case")
        assertUm(6000.0, drill.diameterUm, "6,0 with the decimal comma")
        assertUm(-12500.0, drill.zOffsetUm, "-12.5")
    }

    @Test
    fun the_lathe_import_checks_internal_for_yes_or_no() {
        val text = "name,description,metric,internal,x_offset,z_offset\r\nA,,no,maybe,0,0\r\nB,,no,YES,0,0\r\nC,,NO,no,,\r\n"
        val rows = importer.read(text, lathe).rows
        assertNull(rows[0].tool, "maybe is not yes or no")
        assertTrue(rows[0].problems.any { it.severity == Severity.Error })
        assertTrue(assertNotNull(rows[1].tool).internal)
        val third = assertNotNull(rows[2].tool)
        assertFalse(third.internal)
        // A blank X or Z offset of a lathe tool is 0.
        assertEquals(0.0, third.xOffsetUm)
        assertEquals(0.0, third.zOffsetUm)
    }

    @Test
    fun a_second_row_with_the_same_name_is_an_error_in_mill_and_lathe_files() {
        val millText = "name,description,metric,diameter,tooth_count,z_offset\nT,,no,0.25,2,0\nT,,no,0.5,2,0\n"
        val millRows = importer.read(millText, mill).rows
        assertNotNull(millRows[0].tool)
        assertNull(millRows[1].tool)
        assertEquals(
            "Row 3: Tool offset with this name already exists in this file.",
            millRows[1].problems.single { it.severity == Severity.Error }.describe(),
        )
        val latheText = "name,description,metric,internal,x_offset,z_offset\nT,,no,no,0,0\nT,,no,yes,0,0\n"
        val latheRows = importer.read(latheText, lathe).rows
        assertNull(latheRows[1].tool)
        assertEquals(
            "Row 3: Tool offset with this name already exists in this file.",
            latheRows[1].problems.single { it.severity == Severity.Error }.describe(),
        )
    }

    @Test
    fun the_tool_rules() {
        val header = "name,description,metric,diameter,tooth_count,z_offset\n"
        fun row(line: String) = importer.read(header + line + "\n", mill).rows.single()
        assertNull(row(",d,no,0.25,2,0").tool, "the name is required")
        assertNotNull(row("${"N".repeat(50)},,no,0.25,2,0").tool, "50 characters")
        assertNull(row("${"N".repeat(51)},,no,0.25,2,0").tool, "51 characters")
        assertNotNull(row("N,${"d".repeat(100)},no,0.25,2,0").tool, "100 characters")
        assertNull(row("N,${"d".repeat(101)},no,0.25,2,0").tool, "101 characters")
        assertNull(row("N,,no,0,2,0").tool, "the diameter is not 0")
        assertNull(row("N,,no,,2,0").tool, "the diameter is required")
        assertEquals(2, row("N,,no,0.25,2.0,0").tool?.toothCount, "2.0 is accepted as 2")
        assertEquals(0, row("N,,no,0.25,0,0").tool?.toothCount, "0 or more")
        assertNull(row("N,,no,0.25,2.5,0").tool, "an integer")
        assertNull(row("N,,no,0.25,-1,0").tool, "0 or more")
        assertNull(row("N,,maybe,0.25,2,0").tool, "metric is yes or no")
        assertEquals(true, row("N,,Yes,0.25,2,0").tool?.metric, "yes in any case")
    }

    @Test
    fun a_byte_order_mark_a_free_column_order_and_other_columns_are_accepted() {
        val text = "﻿z_offset,Extra,TOOTH_COUNT,diameter,metric,name\r\n1,x,3,0.5,no,Free\r\n"
        val tool = tools(importer.read(text, mill)).single()
        assertEquals("Free", tool.name)
        assertEquals(3, tool.toothCount)
        assertUm(12700.0, tool.diameterUm, "diameter")
        assertUm(25400.0, tool.zOffsetUm, "z")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "name,description,metric,diameter,tooth_count,z_offset\nB,,no,1,1,1\n".encodeToByteArray()
        assertEquals("B", tools(importer.read(bytes, mill)).single().name)
    }

    @Test
    fun an_exception_during_the_import_gives_a_message_and_no_crash() {
        val failing = ToolCsvImporter(parseNumber = { throw IllegalStateException("evaluator failed") })
        val preview = failing.read(StoreFixtures.text("data-storage-9.2-mill.csv"), mill)
        assertFalse(preview.usable)
        assertTrue(preview.problems.any { it.severity == Severity.Error })
    }

    @Test
    fun a_row_with_the_name_of_a_library_tool_updates_that_tool() {
        val existing = Tool(id = "lib-1", kind = ToolKind.Mill, name = "1/4 End Mill", metric = false, diameterUm = 6000.0, toothCount = 3)
        val preview = importer.read(StoreFixtures.text("data-storage-9.2-mill.csv"), mill.copy(library = listOf(existing)))
        val plan = preview.plan()
        assertEquals(ImportOperation.UpdateMatching, plan.operation)
        val updated = plan.update.single()
        assertEquals("lib-1", updated.id)
        assertUm(6350.0, updated.diameterUm, "the file value")
        assertEquals(listOf("1/2 End Mill", "6 mm Drill"), plan.add.map(Tool::name))
        assertEquals("Tool import finished: 2 tools added, 1 tools updated.", ImportTexts.English.text(plan.finishedMessage))
    }

    @Test
    fun a_row_with_the_id_of_a_library_tool_updates_that_tool() {
        val existing = Tool(id = "lib-9", kind = ToolKind.Mill, name = "Old name", diameterUm = 1000.0, toothCount = 2)
        val text = "id,name,description,metric,diameter,tooth_count,z_offset\nlib-9,New name,,yes,8,3,0\nlib-9,Second,,yes,8,3,0\n"
        val plan = importer.read(text, mill.copy(library = listOf(existing))).plan(ImportOperation.UpdateMatching)
        val updated = plan.update.single()
        assertEquals("lib-9", updated.id)
        assertEquals("New name", updated.name)
        assertUm(8000.0, updated.diameterUm, "8 mm")
        // A second row for the same tool ID is an error.
        assertTrue(plan.preview.skipped.any { it.row == 3 })
        // ADD_AS_NEW ignores the id.
        val added = importer.read(text, mill.copy(library = listOf(existing))).plan(ImportOperation.AddAsNew).add
        assertEquals(2, added.size)
        assertTrue(added.none { it.id == "lib-9" })
    }

    @Test
    fun the_number_format_of_the_3_32_00_files() {
        // Mm 0.0####, inch 0.0#####; 6350 µm gives 6.35 and 0.25.
        assertEquals("6.35", CsvNumbers.formatCompact(6350.0, metric = true))
        assertEquals("0.25", CsvNumbers.formatCompact(6350.0, metric = false))
        assertEquals("6.0", CsvNumbers.formatCompact(6000.0, metric = true))
        assertEquals("-12.5", CsvNumbers.formatCompact(-12500.0, metric = true))
        assertEquals("0.0", CsvNumbers.formatCompact(0.0, metric = false))
        assertEquals("1.23457", CsvNumbers.formatCompact(1234.567, metric = true))
        assertEquals("0.000039", CsvNumbers.formatCompact(1.0, metric = false))
    }

    @Test
    fun the_mill_export_writes_the_header_7_values_and_the_superdro_metadata() {
        val profile = MachineProfile.create(MachineType.VerticalMill, "Bridgeport Mill")
        val tools = listOf(
            Tool(id = "t3", kind = ToolKind.Mill, name = "1/4 End Mill", description = "2 flute HSS", metric = false, diameterUm = 6350.0, toothCount = 2, zOffsetUm = 31750.0),
            Tool(id = "t7", kind = ToolKind.Mill, name = "6 mm Drill", metric = true, diameterUm = 6000.0, toothCount = 2, zOffsetUm = -12500.0),
            Tool(id = "l1", kind = ToolKind.Lathe, name = "Lathe tool"),
        )
        val file = ToolCsvExporter.export(profile, tools, StoreFixtures.exportContext)
        assertEquals("superdro-tools-Bridgeport_Mill-mill-20261011_143005.csv", file.fileName)
        assertEquals("text/csv", file.mimeType)
        val text = file.text
        assertTrue(text.endsWith("\r\n"))
        assertFalse(text.replace("\r\n", "").contains('\n'), "each record ends with CR LF")
        val lines = text.split("\r\n").filter(String::isNotEmpty)
        val metadata = lines.takeWhile { it.startsWith("#") }
        assertEquals("# superdro: tools", metadata.first())
        val keys = metadata.map { it.removePrefix("# ").substringBefore(':') }
        assertEquals(listOf("superdro", "format", "app", "exported", "profile-id", "profile", "machine"), keys)
        assertTrue("# format: 2" in metadata)
        assertTrue("# profile: Bridgeport Mill" in metadata)
        assertTrue("# machine: VERTICAL_MILL" in metadata)
        assertTrue("# exported: 2026-10-11 14:30:05" in metadata)
        assertTrue(metadata.none { it.startsWith("# device") || it.startsWith("# touchdro") || it.startsWith("# units") })
        val body = lines.drop(metadata.size)
        assertEquals("id,name,description,metric,diameter,tooth_count,z_offset", body.first())
        // The export writes only the tools of the machine kind.
        assertEquals(2, body.size - 1)
        val endMill = body[1].split(',')
        assertEquals(7, endMill.size)
        assertEquals(listOf("t3", "1/4 End Mill", "2 flute HSS", "no"), endMill.take(4))
        assertEquals(0.25, endMill[4].toDouble())
        assertEquals("2", endMill[5])
        assertEquals(1.25, endMill[6].toDouble())
        val drill = body[2].split(',')
        assertEquals("yes", drill[3])
        assertEquals(6.0, drill[4].toDouble())
        assertEquals(-12.5, drill[6].toDouble())
    }

    @Test
    fun the_lathe_export_writes_7_values_and_reads_back_with_no_change() {
        val profile = MachineProfile.create(MachineType.Lathe, "South Bend")
        val tools = listOf(
            Tool(id = "t4", kind = ToolKind.Lathe, name = "Boring Bar", description = "3/8 bar", metric = false, internal = true, xOffsetUm = -3175.0, zOffsetUm = 9525.0, toothCount = 1),
            Tool(id = "t1", kind = ToolKind.Lathe, name = "OD Turning", description = "CCMT insert", metric = true, xOffsetUm = 1500.0, zOffsetUm = -250.0, toothCount = 1),
        )
        val file = ToolCsvExporter.export(profile, tools, StoreFixtures.exportContext)
        assertEquals("superdro-tools-South_Bend-lathe-20261011_143005.csv", file.fileName)
        val body = file.text.split("\r\n").filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals("id,name,description,metric,internal,x_offset,z_offset", body.first())
        val bar = body[1].split(',')
        assertEquals(7, bar.size)
        assertEquals(listOf("t4", "Boring Bar", "3/8 bar", "no", "yes"), bar.take(5))
        assertEquals(-0.125, bar[5].toDouble())
        assertEquals(0.375, bar[6].toDouble())
        val again = tools(importer.read(file.text, ToolImportContext("lathe-2", MachineType.Lathe)))
        assertEquals(tools.map { listOf(it.name, it.description, it.metric, it.internal) }, again.map { listOf(it.name, it.description, it.metric, it.internal) })
        tools.zip(again).forEach { (before, after) ->
            assertUm(before.xOffsetUm, after.xOffsetUm, before.name)
            assertUm(before.zOffsetUm, after.zOffsetUm, before.name)
        }
    }

    @Test
    fun the_export_writes_only_the_selected_tools() {
        val profile = MachineProfile.create(MachineType.VerticalMill, "Mill")
        val tools = listOf(Tool(id = "a", name = "A", diameterUm = 1000.0), Tool(id = "b", name = "B", diameterUm = 2000.0))
        val body = ToolCsvExporter.export(profile, tools, StoreFixtures.exportContext, selectedIds = setOf("b"))
            .text.split("\r\n").filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(2, body.size)
        assertTrue(body[1].startsWith("b,B,"))
    }

    @Test
    fun a_mill_tool_file_imports_into_a_horizontal_mill_as_mill_tools_and_a_newer_format_is_refused() {
        val horizontal = ToolImportContext("h-1", MachineType.HorizontalMill)
        tools(importer.read(StoreFixtures.text("data-storage-9.2-mill.csv"), horizontal)).forEach { tool -> assertEquals(ToolKind.Mill, tool.kind) }
        // A metadata format above 2 stops the import.
        val newer = importer.read("# touchdro: tools\n# format: 3\nname,description,metric,diameter,tooth_count,z_offset\nA,,no,1,1,1\n", mill)
        assertFalse(newer.usable)
        // A sub-datum file in the tool import is refused.
        assertFalse(importer.read(StoreFixtures.text("data-storage-6.6-mill-inch.csv"), mill).usable)
    }

    @Test
    fun the_store_keeps_internal_of_an_imported_lathe_tool() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "Lathe A")).valueOrNull())
        val preview = importer.read(StoreFixtures.text("data-storage-9.2-lathe.csv"), ToolImportContext(profile.id, MachineType.Lathe))
        val plan = preview.plan(ImportOperation.AddAsNew)
        store.tools.saveTools(profile.id, plan.add)
        val library = store.tools.library(profile.id).first().associateBy(Tool::name)
        assertTrue(library.getValue("Boring Bar").internal)
        assertFalse(library.getValue("OD Turning").internal)
        assertEquals(-3175.0, library.getValue("Boring Bar").xOffsetUm, 1e-6)
    }

    @Test
    fun mill_tools_serve_both_mill_types_and_a_lathe_has_its_own_tools() = runTest {
        val store = store()
        val horizontal = assertNotNull(store.machines.createProfile(MachineProfile.create(MachineType.HorizontalMill, "H")).valueOrNull())
        val vertical = assertNotNull(store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "V")).valueOrNull())
        val lathe = assertNotNull(store.machines.createProfile(MachineProfile.create(MachineType.Lathe, "L")).valueOrNull())
        val own = assertNotNull(store.tools.addTool(horizontal.id, Tool(name = "Face mill", diameterUm = 50000.0)).valueOrNull())
        assertEquals(ToolKind.Mill, own.kind)
        assertEquals(listOf("Face mill"), store.tools.library(horizontal.id).first().map(Tool::name))
        listOf(horizontal, vertical, lathe).forEach { profile -> store.tools.setUseSharedLibrary(profile.id, true) }
        store.tools.addTool(vertical.id, Tool(name = "Shared drill", diameterUm = 5000.0))
        store.tools.addTool(lathe.id, Tool(name = "Shared boring bar"))
        assertEquals(listOf("Shared drill"), store.tools.library(horizontal.id).first().map(Tool::name))
        assertEquals(listOf("Shared drill"), store.tools.library(vertical.id).first().map(Tool::name))
        assertEquals(listOf("Shared boring bar"), store.tools.library(lathe.id).first().map(Tool::name))
    }

    @Test
    fun a_change_of_the_shared_library_switch_takes_effect_at_once() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "Switch")).valueOrNull())
        store.tools.addTool(profile.id, Tool(name = "Own"))
        assertEquals(listOf("Own"), store.tools.library(profile.id).first().map(Tool::name))
        store.tools.setUseSharedLibrary(profile.id, true)
        assertEquals(emptyList(), store.tools.library(profile.id).first().map(Tool::name))
        assertTrue(store.machines.loadProfile(profile.id)!!.useSharedToolLibrary)
        store.tools.setUseSharedLibrary(profile.id, false)
        assertEquals(listOf("Own"), store.tools.library(profile.id).first().map(Tool::name))
    }
}
