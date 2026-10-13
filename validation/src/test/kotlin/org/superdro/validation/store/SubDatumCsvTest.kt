package org.superdro.validation.store

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.csv.CsvMetadata
import org.superdro.core.store.files.csv.CsvNumbers
import org.superdro.core.store.files.subdatum.SubDatumCsvExporter
import org.superdro.core.store.files.subdatum.SubDatumCsvImporter
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.files.subdatum.SubDatumImportPreview
import org.superdro.core.store.files.subdatum.SubDatumItem
import org.superdro.core.store.valueOrNull

/**
 * The sub-datum CSV import and export. The expected
 * values come from the CSV table and from the example files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubDatumCsvTest {

    private val importer = SubDatumCsvImporter(parseNumber = StoreFixtures.f9)
    private val millInch = SubDatumImportContext(profileId = "mill-1", machineType = MachineType.VerticalMill, metricDefault = false)
    private val latheMm = SubDatumImportContext(profileId = "lathe-1", machineType = MachineType.Lathe, metricDefault = true)
    private val harnesses = mutableListOf<StoreHarness>()

    private fun TestScope.store(): StoreHarness = StoreHarness(StandardTestDispatcher(testScheduler)).also(harnesses::add)

    @AfterTest
    fun closeAll() {
        harnesses.forEach(StoreHarness::close)
    }

    private fun points(preview: SubDatumImportPreview): List<SubDatum> = preview.rows.map { row ->
        assertIs<SubDatumItem.Point>(row.item, "row ${row.row}: ${row.problems}").subDatum
    }

    private fun assertUm(expected: Double?, actual: Double?, what: String) {
        if (expected == null) assertNull(actual, what) else assertEquals(expected, assertNotNull(actual, what), 1e-6, what)
    }

    @Test
    fun import_of_the_mill_inch_file_of_data_storage_6_6() {
        val preview = importer.read(StoreFixtures.text("data-storage-6.6-mill-inch.csv"), millInch)
        assertFalse(preview.metric, "no units column: the metric default of the profile (inch)")
        val points = points(preview)
        assertEquals(listOf("Hole 1", "Hole 2", "Pocket, corner"), points.map(SubDatum::name))
        assertEquals(listOf("Left mounting hole", "", "Quoted name with comma"), points.map(SubDatum::note))
        assertEquals(listOf(listOf("holes", "left"), listOf("holes"), listOf("pocket")), points.map(SubDatum::tags))
        assertUm(25400.0, points[0].x, "Hole 1 x")
        assertUm(12700.0, points[0].y, "Hole 1 y")
        assertUm(null, points[0].z, "Hole 1 z")
        assertUm(50800.0, points[1].x, "Hole 2 x")
        assertUm(-6350.0, points[1].z, "Hole 2 z")
        // The number is an expression. 3+1/4 in = 82550 µm.
        assertUm(82550.0, points[2].x, "Pocket x")
        assertUm(31750.0, points[2].y, "Pocket y")
    }

    @Test
    fun import_of_the_lathe_mm_file_of_data_storage_6_6_keeps_the_radius() {
        val points = points(importer.read(StoreFixtures.text("data-storage-6.6-lathe-mm.csv"), latheMm))
        assertEquals(listOf("Shoulder", "Groove"), points.map(SubDatum::name))
        assertUm(12500.0, points[0].x, "Shoulder x")
        assertUm(-30000.0, points[0].z, "Shoulder z")
        assertUm(10000.0, points[1].x, "Groove x")
        assertUm(-45500.0, points[1].z, "Groove z")
        assertEquals("2 mm groove", points[1].note)
        assertEquals(listOf("groove"), points[1].tags)
        points.forEach { point -> assertNull(point.y) }
    }

    @Test
    fun the_units_line_wins_over_the_profile_and_the_decimal_comma_is_accepted() {
        val inchLathe = latheMm.copy(metricDefault = false)
        val preview = importer.read(StoreFixtures.text("data-storage-14.14.5-lathe-mm.csv"), inchLathe)
        assertTrue(preview.metric, "# units: mm")
        assertEquals(true, preview.metadata.metric)
        val points = points(preview)
        assertUm(12500.0, points[0].x, "Shoulder x")
        assertUm(-30000.0, points[0].z, "Shoulder z")
        assertUm(10000.0, points[1].x, "10,0 mm")
        assertUm(-45500.0, points[1].z, "Groove z")
    }

    @Test
    fun import_of_the_3_32_00_export_with_the_metadata_block_and_a_guide() {
        val preview = importer.read(StoreFixtures.text("data-storage-14.14.5-export-mill-inch.csv"), millInch.copy(metricDefault = true))
        val metadata = preview.metadata
        assertEquals(FileSource.TouchDro, metadata.source)
        assertEquals("sub-datums", metadata.kind)
        assertEquals(2, metadata.format)
        assertEquals("1", metadata.profileId)
        assertEquals("Bridgeport Mill", metadata.profile)
        assertEquals(MachineType.VerticalMill, metadata.machine)
        assertEquals(false, metadata.metric)
        assertFalse(preview.metric, "# units: inch")
        val rows = preview.rows
        val point1 = assertIs<SubDatumItem.Point>(rows[0].item).subDatum
        assertEquals("Point 1", point1.name)
        assertUm(25400.0, point1.x, "x")
        assertUm(12700.0, point1.y, "y")
        assertUm(null, point1.z, "z")
        val grid = assertIs<SubDatumItem.Point>(rows[1].item).subDatum
        assertEquals(listOf("Grid"), grid.tags)
        assertUm(-6350.0, grid.z, "z")
        // A row with one coordinate and the name "Guide" gives a guide.
        val guide = assertIs<SubDatumItem.Line>(rows[2].item).guide
        assertEquals(AxisId.X, guide.axis)
        assertEquals(76200.0, guide.valueUm, 1e-6)
        val plan = preview.plan()
        assertEquals(2, plan.addSubDatums.size)
        assertEquals(1, plan.addGuides.size)
        // New IDs.
        plan.addSubDatums.forEach { point -> assertTrue(StoreFixtures.UUID_FORM.matches(point.id)) }
    }

    @Test
    fun the_metadata_parser_rules() {
        val text = "#  TouchDRO :  sub-datums  \n# unknown: 5\n# profile:\n# device: abc\n# UNITS: Millimetres\nname,x\nP,1\n"
        val metadata = importer.read(text, millInch).metadata
        assertEquals(FileSource.TouchDro, metadata.source)
        assertEquals("sub-datums", metadata.kind)
        assertNull(metadata.profile, "a blank value is ignored")
        assertEquals(true, metadata.metric)
        // `units` accepts these words in any case.
        listOf("mm", "metric", "millimeter", "millimeters", "millimetre", "millimetres", "MM", "Metric").forEach { word ->
            assertEquals(true, CsvMetadata.parseUnits(word), word)
        }
        listOf("in", "inch", "inches", "imperial", "\"", "INCH", "Imperial").forEach { word ->
            assertEquals(false, CsvMetadata.parseUnits(word), word)
        }
        assertNull(CsvMetadata.parseUnits("feet"))
    }

    @Test
    fun y_is_optional_on_a_mill_and_x_is_required() {
        val text = "name,note,tags,x,y,z\nP1,,,1.0,,\nP2,,,,0.5,\n"
        val rows = importer.read(text, millInch).rows
        val first = assertIs<SubDatumItem.Point>(rows[0].item).subDatum
        assertUm(25400.0, first.x, "x")
        assertNull(first.y)
        assertNull(rows[1].item, "x is a required number")
        assertTrue(rows[1].problems.any { it.severity == Severity.Error })
    }

    @Test
    fun the_name_is_required_and_the_error_tells_the_row() {
        val text = "name,note,tags,x,y,z\nP1,,,1,1,\n,,,2,2,\n"
        val rows = importer.read(text, millInch).rows
        assertNull(rows[1].item)
        assertEquals("Row 3: Name is blank", rows[1].problems.single { it.severity == Severity.Error }.describe())
    }

    @Test
    fun a_lathe_needs_z_and_does_not_read_y() {
        val text = "name,note,tags,x,y,z\nA,,,1,7,2\nB,,,1,,\n"
        val rows = importer.read(text, latheMm).rows
        val first = assertIs<SubDatumItem.Point>(rows[0].item).subDatum
        assertNull(first.y, "a lathe does not read y")
        assertUm(1000.0, first.x, "x")
        assertUm(2000.0, first.z, "z")
        assertNull(rows[1].item, "z is required on a lathe")
    }

    @Test
    fun the_header_any_order_other_columns_and_the_byte_order_mark() {
        val text = "﻿z,extra,x,name\r\n-1,q,2,P\r\n"
        val point = points(importer.read(text, millInch.copy(metricDefault = true))).single()
        assertEquals("P", point.name)
        assertUm(2000.0, point.x, "x")
        assertUm(-1000.0, point.z, "z")
    }

    @Test
    fun an_exception_during_the_import_gives_a_message_and_no_crash() {
        val failing = SubDatumCsvImporter(parseNumber = { error("evaluator failed") })
        val preview = failing.read(StoreFixtures.text("data-storage-6.6-mill-inch.csv"), millInch)
        assertFalse(preview.usable)
        assertTrue(preview.problems.any { it.severity == Severity.Error })
    }

    @Test
    fun the_point_match_tolerance_comes_from_the_decimals_of_the_file() {
        // 1.250 in inch gives 12.7 µm; no decimals give 500 µm or 12700 µm.
        assertEquals(12.7, CsvNumbers.tolerance("1.250", metric = false), 1e-9)
        assertEquals(500.0, CsvNumbers.tolerance("3", metric = true), 1e-9)
        assertEquals(12700.0, CsvNumbers.tolerance("3", metric = false), 1e-9)
        val here = SubDatum(id = "w1", name = "Old", x = 31750.0, y = 12700.0)
        val context = millInch.copy(subDatums = listOf(here))
        val text = "name,note,tags,x,y,z\nNew name,new note,,1.250,0.500,\nOther,,,1.251,0.500,\n"
        val preview = importer.read(text, context)
        // A file of another installation: ADD_AS_NEW is the default; ADD_NEW_ONLY, MERGE_MATCHING, and REPLACE_ALL follow.
        assertEquals(
            listOf(ImportOperation.AddAsNew, ImportOperation.AddNewOnly, ImportOperation.MergeMatching, ImportOperation.ReplaceAll),
            preview.operations,
        )
        val newOnly = preview.plan(ImportOperation.AddNewOnly)
        assertEquals(listOf("Other"), newOnly.addSubDatums.map(SubDatum::name))
        assertEquals(listOf("New name"), newOnly.preview.skipped.filter { it.action == RowAction.AlreadyHere }.map { it.label })
        val merge = preview.plan(ImportOperation.MergeMatching)
        val updated = merge.updateSubDatums.single()
        assertEquals("w1", updated.id)
        assertEquals("New name", updated.name)
        assertEquals("new note", updated.note)
        assertEquals(31750.0, updated.x)
        assertTrue(preview.plan(ImportOperation.ReplaceAll).deleteAll)
    }

    @Test
    fun replace_all_is_not_available_when_a_row_has_an_error() {
        val preview = importer.read("name,x,y\nGood,1,1\n,2,2\n", millInch)
        assertFalse(ImportOperation.ReplaceAll in preview.operations)
    }

    @Test
    fun the_export_of_data_storage_6_7_with_the_superdro_metadata() {
        val profile = MachineProfile.create(MachineType.VerticalMill, "Bridgeport Mill")
        val points = listOf(
            SubDatum(id = "p1", name = "Point 1", x = 25400.0, y = 12700.0),
            SubDatum(id = "p2", name = "Hole 2", tags = listOf("holes"), x = 50800.0, y = 12700.0, z = -6350.0),
        )
        val guides = listOf(Guide(id = "g1", axis = AxisId.X, valueUm = 76200.0))
        val file = SubDatumCsvExporter.export(profile, points, guides, StoreFixtures.exportContext)
        assertEquals("superdro-sub_datums-Bridgeport_Mill-mill-20261011_143005.csv", file.fileName)
        val lines = file.text.split("\r\n").filter(String::isNotEmpty)
        assertTrue(file.text.endsWith("\r\n"))
        val metadata = lines.takeWhile { it.startsWith("#") }
        assertEquals("# superdro: sub-datums", metadata.first())
        assertEquals(
            listOf("superdro", "format", "app", "exported", "profile-id", "profile", "machine", "units"),
            metadata.map { it.removePrefix("# ").substringBefore(':') },
        )
        assertTrue("# units: inch" in metadata)
        assertTrue(metadata.none { it.startsWith("# device") })
        // Mill, inch, display_format 3.
        assertEquals(
            listOf(
                "name,note,tags,x,y,z",
                "Point 1,,,1.0000,0.5000,",
                "Hole 2,,holes,2.0000,0.5000,-0.2500",
                "Guide,,,3.0000,,",
            ),
            lines.drop(metadata.size),
        )
    }

    @Test
    fun the_export_writes_only_the_selected_sub_datums() {
        val profile = MachineProfile.create(MachineType.VerticalMill, "Mill")
        val points = listOf(SubDatum(id = "a", name = "A", x = 0.0), SubDatum(id = "b", name = "B", x = 25400.0))
        val body = SubDatumCsvExporter.export(profile, points, emptyList(), StoreFixtures.exportContext, selectedIds = setOf("b"))
            .text.split("\r\n").filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf("name,note,tags,x,y,z", "B,,,1.0000,,"), body)
    }

    @Test
    fun the_export_rules_of_the_axes_tags_and_units() {
        val metricMill = MachineProfile.create(MachineType.VerticalMill, "M").copy(metricDefault = true, y = MachineProfile.create().y.copy(enabled = false))
        val point = SubDatum(name = "P", note = "n", tags = listOf("a", "b"), x = 1234.6, y = 5.0, z = -10.0)
        val body = SubDatumCsvExporter.export(metricMill, listOf(point), emptyList(), StoreFixtures.exportContext)
            .text.split("\r\n").filter { it.isNotEmpty() && !it.startsWith("#") }
        // mm with display_format 3: 3 decimals. Y is disabled: no y value. Tags joined with one space.
        assertEquals("P,n,a b,1.235,,-0.010", body[1])
        val lathe = MachineProfile.create(MachineType.Lathe, "L")
        val lathePoint = SubDatum(name = "S", x = 25400.0, y = 99.0, z = -50800.0)
        val latheBody = SubDatumCsvExporter.export(lathe, listOf(lathePoint), emptyList(), StoreFixtures.exportContext)
            .text.split("\r\n").filter { it.isNotEmpty() && !it.startsWith("#") }
        // A lathe writes no y; the stored X (a radius) goes out as it is.
        assertEquals("S,,,1.0000,,-2.0000", latheBody[1])
    }

    @Test
    fun an_export_reads_back_with_the_same_points() {
        val profile = MachineProfile.create(MachineType.VerticalMill, "Round").copy(metricDefault = true)
        val points = listOf(
            SubDatum(name = "A, quoted", note = "a \"note\"", tags = listOf("t1", "t2"), x = 1000.0, y = -2500.0, z = 125.0),
            SubDatum(name = "#hash", x = 0.0, y = 0.0),
        )
        val file = SubDatumCsvExporter.export(profile, points, emptyList(), StoreFixtures.exportContext)
        val again = points(importer.read(file.text, SubDatumImportContext("other", MachineType.VerticalMill, metricDefault = false)))
        assertEquals(points.map { listOf(it.name, it.note, it.tags) }, again.map { listOf(it.name, it.note, it.tags) })
        points.zip(again).forEach { (before, after) ->
            assertUm(before.x, after.x, "x")
            assertUm(before.y, after.y, "y")
            assertUm(before.z, after.z, "z")
        }
    }

    @Test
    fun the_store_keeps_the_angular_coordinate_of_a_sub_datum() = runTest {
        val store = store()
        val profile = assertNotNull(store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "A axis")).valueOrNull())
        val workspaceId = store.machines.workspaces(profile.id).first().single().id
        store.machines.addSubDatum(workspaceId, SubDatum(x = 1000.0, y = 2000.0, a = 162000.0))
        val loaded = store.machines.subDatums(workspaceId).first().single()
        assertEquals(162000.0, loaded.a)
        assertEquals(1000.0, loaded.x)
    }
}
