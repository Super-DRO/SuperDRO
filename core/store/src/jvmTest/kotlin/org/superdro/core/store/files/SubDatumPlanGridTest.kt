package org.superdro.core.store.files

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.store.files.subdatum.SubDatumCsvImporter
import org.superdro.core.store.files.subdatum.SubDatumImportContext
import org.superdro.core.store.files.subdatum.SubDatumImportPreview
import org.superdro.core.store.files.subdatum.SubDatumItem
import org.superdro.core.store.files.subdatum.SubDatumMatch

/**
 * The grid of the sub-datum import plan gives the same result as a test of each earlier row and
 * each sub-datum of the workspace.
 */
class SubDatumPlanGridTest {

    private val importer = SubDatumCsvImporter(TestEvaluator::evaluate, now = { FIXED_TIME }, ids = sequentialIds())

    /** The values in mm near the cell borders: a row with 0 decimals gives cells of 1 mm. */
    private val offsets = listOf(-0.6, -0.5, -0.4999, -0.05, -0.001, 0.0, 0.001, 0.05, 0.4999, 0.5, 0.6)

    private fun value(random: Random): Double = random.nextInt(0, 4) + offsets[random.nextInt(offsets.size)]

    private fun text(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

    private fun fileText(random: Random, rows: Int): String = buildString {
        appendLine("# units: mm")
        appendLine("name,note,tags,x,y,z")
        repeat(rows) { index ->
            val decimals = random.nextInt(0, 4)
            when (random.nextInt(10)) {
                0 -> appendLine("Guide,,,${text(value(random), decimals)},,")
                1 -> appendLine("Guide,,,,${text(value(random), decimals)},")
                2 -> appendLine("P$index,,,${text(value(random), decimals)},${text(value(random), decimals)},")
                else -> appendLine(
                    "P$index,,t${index % 3},${text(value(random), decimals)},${text(value(random), decimals)},${text(value(random), decimals)}",
                )
            }
        }
    }

    private fun workspace(random: Random, count: Int): List<SubDatum> = List(count) { index ->
        SubDatum(
            id = "s$index",
            name = "S$index",
            tags = listOf("old"),
            x = value(random) * 1000.0,
            y = value(random) * 1000.0,
            z = if (index % 4 == 0) null else value(random) * 1000.0,
            createdOn = FIXED_TIME,
            updatedOn = FIXED_TIME,
        )
    }

    /** The result of the plan: the row actions, the values of the rows, and the writes. */
    private data class Outcome(
        val rows: List<Triple<Int, RowAction, SubDatumItem?>>,
        val add: List<SubDatum>,
        val update: List<SubDatum>,
        val addGuides: List<Guide>,
    )

    /** The plan with a test of each earlier row and each sub-datum, as before the grid. */
    private fun reference(preview: SubDatumImportPreview, context: SubDatumImportContext, operation: ImportOperation): Outcome {
        val rows = mutableListOf<Triple<Int, RowAction, SubDatumItem?>>()
        val add = mutableListOf<SubDatum>()
        val updates = LinkedHashMap<String, SubDatum>()
        val addGuides = mutableListOf<Guide>()
        val earlier = mutableListOf<SubDatumItem>()
        val match = operation == ImportOperation.AddNewOnly || operation == ImportOperation.MergeMatching
        for (row in preview.rows) {
            val item = row.item
            if (item == null) {
                rows += Triple(row.row, RowAction.Skip, null)
                continue
            }
            if (match && earlier.any { other -> SubDatumMatch.sameItem(item, other) }) {
                rows += Triple(row.row, RowAction.Skip, null)
                continue
            }
            earlier += item
            when (item) {
                is SubDatumItem.Point -> {
                    val found = if (match) SubDatumMatch.nearest(item, context.subDatums.map { s -> updates[s.id] ?: s }) else null
                    when {
                        found == null -> {
                            add += item.subDatum
                            rows += Triple(row.row, RowAction.Add, item)
                        }
                        operation == ImportOperation.AddNewOnly -> rows += Triple(row.row, RowAction.AlreadyHere, item)
                        else -> {
                            val file = item.subDatum
                            val merged = found.copy(
                                name = file.name,
                                note = file.note,
                                tags = found.tags + file.tags.filter { tag -> tag !in found.tags },
                                updatedOn = FIXED_TIME,
                            )
                            updates[found.id] = merged
                            rows += Triple(row.row, RowAction.Update, SubDatumItem.Point(merged, item.tolerance))
                        }
                    }
                }
                is SubDatumItem.Line -> {
                    if (match && context.guides.any { guide -> SubDatumMatch.sameGuide(item, guide) }) {
                        rows += Triple(row.row, RowAction.AlreadyHere, item)
                    } else {
                        addGuides += item.guide
                        rows += Triple(row.row, RowAction.Add, item)
                    }
                }
            }
        }
        return Outcome(rows, add, updates.values.toList(), addGuides)
    }

    private fun actual(preview: SubDatumImportPreview, operation: ImportOperation): Outcome {
        val plan = preview.plan(operation)
        val rows = (plan.preview.toImport + plan.preview.skipped).sortedBy { it.row }.map { row -> Triple(row.row, row.action, row.value) }
        return Outcome(rows, plan.addSubDatums, plan.updateSubDatums, plan.addGuides)
    }

    @Test
    fun theGridPlanIsTheSameAsTheFullTest() {
        val random = Random(7)
        val guides = listOf(Guide(id = "g1", axis = AxisId.X, valueUm = 1000.0), Guide(id = "g2", axis = AxisId.Y, valueUm = 2500.0))
        val context = SubDatumImportContext("p1", MachineType.VerticalMill, true, workspace(random, 400), guides)
        val preview = importer.read(fileText(random, 3000), context)
        assertTrue(preview.usable, preview.problems.toString())
        for (operation in preview.operations) {
            val expected = reference(preview, context, operation)
            assertEquals(expected, actual(preview, operation), "The operation $operation")
            if (operation == ImportOperation.MergeMatching) {
                assertTrue(expected.rows.count { it.second == RowAction.Skip } > 10, "The fixture has duplicates.")
                assertTrue(expected.update.size > 10, "The fixture has matches in the workspace.")
                assertTrue(expected.rows.count { it.second == RowAction.Add } > 10, "The fixture has new points.")
            }
        }
    }

    @Test
    fun aValueThatIsNotFiniteUsesTheFullTest() {
        val random = Random(11)
        val context = SubDatumImportContext(
            "p1",
            MachineType.VerticalMill,
            true,
            workspace(random, 50) + SubDatum(id = "inf", x = Double.POSITIVE_INFINITY, y = 0.0, z = 0.0),
        )
        val preview = importer.read(fileText(random, 300), context)
        for (operation in preview.operations) {
            assertEquals(reference(preview, context, operation), actual(preview, operation), "The operation $operation")
        }
    }
}
