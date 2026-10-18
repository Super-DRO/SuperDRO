package org.superdro.validation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.superdro.core.model.AuxiliaryConfig
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineMode
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.ui.readout.FUNCTION_STRIP_TAG
import org.superdro.core.ui.readout.MIN_GRID_COLUMNS
import org.superdro.core.ui.readout.gridColumns
import org.superdro.core.ui.readout.FunctionButton
import org.superdro.core.ui.readout.FunctionButton.AlignAxes
import org.superdro.core.ui.readout.FunctionButton.AngleMeasure
import org.superdro.core.ui.readout.FunctionButton.ArbitraryHoleCircle
import org.superdro.core.ui.readout.FunctionButton.ArcCut
import org.superdro.core.ui.readout.FunctionButton.CompoundCompensation
import org.superdro.core.ui.readout.FunctionButton.DimensionMeasure
import org.superdro.core.ui.readout.FunctionButton.EdgeFinder
import org.superdro.core.ui.readout.FunctionButton.EquidistantArray
import org.superdro.core.ui.readout.FunctionButton.GraphicalView
import org.superdro.core.ui.readout.FunctionButton.HeadTiltCompensation
import org.superdro.core.ui.readout.FunctionButton.HeightCompensation
import org.superdro.core.ui.readout.FunctionButton.HoleCircle
import org.superdro.core.ui.readout.FunctionButton.HoleGrid
import org.superdro.core.ui.readout.FunctionButton.LatheToolOffset
import org.superdro.core.ui.readout.FunctionButton.RadiusCompensation
import org.superdro.core.ui.readout.FunctionButton.RadiusDiameter
import org.superdro.core.ui.readout.FunctionButton.ReferenceInstrument
import org.superdro.core.ui.readout.FunctionButton.RotatePart
import org.superdro.core.ui.readout.FunctionButton.ThreadSetup
import org.superdro.core.ui.readout.FunctionButton.ZReference
import org.superdro.core.ui.readout.FunctionButtonUiState
import org.superdro.core.ui.readout.FunctionStrip
import org.superdro.core.ui.readout.FunctionStripRules
import org.superdro.core.ui.readout.GlobalButtonsUiState
import org.superdro.core.ui.readout.MODE_TAG
import org.superdro.core.ui.readout.NoReadoutEvents
import org.superdro.core.ui.readout.StripForm
import org.superdro.core.ui.readout.UNITS_TAG
import org.superdro.core.ui.readout.ZERO_SET_TAG
import org.superdro.core.ui.readout.functionTag
import org.superdro.core.ui.theme.AppTheme

/**
 * The visibility for each machine type, with the
 * horizontal mill, and each `show_*` switch. The expected orders are the columns "Mill order"
 * and "Lathe order" of the function strip table. "end" rows come after the numbered rows, in the order of the
 * rows of the table.
 */
class FunctionStripValidationTest {

    private fun buttons(profile: MachineProfile): List<FunctionButton> =
        FunctionStripRules.buttons(profile, profile.activeWorkspace).map { item -> item.button }

    private val mill = MachineProfile.create(MachineType.VerticalMill)
    private val horizontalMill = MachineProfile.create(MachineType.HorizontalMill)
    private val lathe = MachineProfile.create(MachineType.Lathe)

    private fun MachineProfile.withCaliper() = copy(caliper = caliper.copy(enabled = true))

    /** A mill with a quill input: the secondary input of Z (precondition 2). */
    private fun MachineProfile.withQuill() = copy(z = z.copy(secondary = InputConfig('w')))

    /** A lathe with a compound input: the auxiliary readout Y is the compound slide. */
    private fun MachineProfile.withCompound() =
        copy(y = y.copy(enabled = true), auxiliary = AuxiliaryConfig(type = AuxiliaryType.CompoundSlide, vectoring = true))

    /** The mill order 1 to 12 of the table, then the "end" rows of a mill in cutting mode. */
    private val millOrder = listOf(
        ZReference, RadiusCompensation, HeightCompensation, EdgeFinder, HoleCircle, ArbitraryHoleCircle,
        ArcCut, HoleGrid, EquidistantArray, DimensionMeasure, AngleMeasure, GraphicalView,
        ThreadSetup, HeadTiltCompensation, RotatePart,
    )

    /** The lathe order 1 to 9 of the table, then the "end" rows of a lathe. */
    private val latheOrder = listOf(
        RadiusDiameter, LatheToolOffset, DimensionMeasure, ArcCut, AngleMeasure, EquidistantArray, HoleGrid,
        HoleCircle, GraphicalView, ThreadSetup, CompoundCompensation,
    )

    @Test
    fun vertical_mill_order_with_each_switch_on() {
        val profile = mill.withCaliper().withQuill()
        assertEquals(millOrder, buttons(profile))
    }

    @Test
    fun horizontal_mill_shows_the_same_buttons_in_the_same_order() {
        assertEquals(millOrder, buttons(horizontalMill.withCaliper().withQuill()))
        assertEquals(buttons(mill), buttons(horizontalMill))
        // The edge finder, Z reference, compensation, and arbitrary circle buttons show on both mill types.
        listOf(EdgeFinder, ZReference, RadiusCompensation, HeightCompensation, ArbitraryHoleCircle).forEach { button ->
            assertTrue(button in buttons(horizontalMill), "$button on the horizontal mill")
        }
    }

    @Test
    fun lathe_order_with_each_switch_on() {
        assertEquals(latheOrder, buttons(lathe.withCaliper().withCompound()))
    }

    @Test
    fun new_profiles_with_the_defaults_of_4_17() {
        // Each `show_*` switch is true by default. The caliper is off by default, so the
        // dimension measure hides. A new mill has no quill input and a new lathe has no compound input.
        assertEquals(millOrder - DimensionMeasure - HeadTiltCompensation, buttons(mill))
        assertEquals(latheOrder - DimensionMeasure - CompoundCompensation, buttons(lathe))
    }

    /** Each `show_*` switch of the table with the button that it controls. */
    private val switches: List<Pair<FunctionButton, (MachineProfile) -> MachineProfile>> = listOf(
        ZReference to { p -> p.copy(functionButtons = p.functionButtons.copy(zReference = false)) },
        RadiusCompensation to { p -> p.copy(functionButtons = p.functionButtons.copy(radiusCompensation = false)) },
        HeightCompensation to { p -> p.copy(functionButtons = p.functionButtons.copy(heightCompensation = false)) },
        EdgeFinder to { p -> p.copy(functionButtons = p.functionButtons.copy(edgeFinder = false)) },
        HoleCircle to { p -> p.copy(functionButtons = p.functionButtons.copy(holeCircle = false)) },
        ArbitraryHoleCircle to { p -> p.copy(functionButtons = p.functionButtons.copy(arbitraryHoleCircle = false)) },
        ArcCut to { p -> p.copy(functionButtons = p.functionButtons.copy(arcCut = false)) },
        HoleGrid to { p -> p.copy(functionButtons = p.functionButtons.copy(holeGrid = false)) },
        EquidistantArray to { p -> p.copy(functionButtons = p.functionButtons.copy(equidistantArray = false)) },
        DimensionMeasure to { p -> p.copy(functionButtons = p.functionButtons.copy(dimensionMeasure = false)) },
        AngleMeasure to { p -> p.copy(functionButtons = p.functionButtons.copy(angleMeasure = false)) },
        HeadTiltCompensation to { p -> p.copy(functionButtons = p.functionButtons.copy(headTilt = false)) },
        CompoundCompensation to { p -> p.copy(functionButtons = p.functionButtons.copy(compoundAngle = false)) },
    )

    @Test
    fun each_show_switch_hides_only_its_button_on_each_machine_type() {
        listOf(mill.withCaliper().withQuill(), horizontalMill.withCaliper().withQuill(), lathe.withCaliper().withCompound()).forEach { base ->
            val all = buttons(base)
            switches.forEach { (button, off) ->
                val expected = all - button
                assertEquals(expected, buttons(off(base)), "${base.machineType}: switch of $button off")
            }
        }
    }

    @Test
    fun always_buttons_stay_with_each_switch_off() {
        val allOff: (MachineProfile) -> MachineProfile = { p -> switches.fold(p) { profile, (_, off) -> off(profile) } }
        assertEquals(listOf(GraphicalView, ThreadSetup, RotatePart), buttons(allOff(mill.withCaliper().withQuill())))
        assertEquals(listOf(GraphicalView, ThreadSetup, RotatePart), buttons(allOff(horizontalMill)))
        assertEquals(listOf(RadiusDiameter, LatheToolOffset, GraphicalView, ThreadSetup), buttons(allOff(lathe.withCaliper().withCompound())))
    }

    @Test
    fun dimension_measure_needs_the_caliper_enabled() {
        assertTrue(DimensionMeasure !in buttons(mill))
        assertTrue(DimensionMeasure in buttons(mill.withCaliper()))
        assertTrue(DimensionMeasure !in buttons(lathe))
        assertTrue(DimensionMeasure in buttons(lathe.withCaliper()))
    }

    @Test
    fun head_tilt_needs_the_switch_and_a_quill_input() {
        assertTrue(HeadTiltCompensation !in buttons(mill))
        assertTrue(HeadTiltCompensation in buttons(mill.withQuill()))
        assertTrue(HeadTiltCompensation in buttons(horizontalMill.withQuill()))
        assertTrue(HeadTiltCompensation !in buttons(lathe.withCaliper().withCompound()))
    }

    @Test
    fun compound_needs_the_switch_and_a_compound_input() {
        assertTrue(CompoundCompensation !in buttons(lathe))
        assertTrue(CompoundCompensation in buttons(lathe.withCompound()))
        assertTrue(CompoundCompensation !in buttons(mill.withQuill()))
    }

    @Test
    fun measurement_mode_hides_the_compensation_buttons_and_adds_align_and_instrument() {
        val measuring = { p: MachineProfile -> p.copy(machineMode = MachineMode.Measurement) }
        val expected = millOrder - ZReference - RadiusCompensation - HeightCompensation + AlignAxes + ReferenceInstrument
        assertEquals(expected, buttons(measuring(mill.withCaliper().withQuill())))
        // The instrument button on both mill types.
        assertEquals(expected, buttons(measuring(horizontalMill.withCaliper().withQuill())))
        // Cutting mode: no align axes and no instrument.
        assertTrue(AlignAxes !in buttons(mill) && ReferenceInstrument !in buttons(mill))
    }

    @Test
    fun measurement_mode_is_not_on_a_lathe() {
        val profile = lathe.withCaliper().withCompound().copy(machineMode = MachineMode.Measurement)
        assertEquals(latheOrder, buttons(profile))
    }

    /** Gives the test tags of the strip nodes in the order of the semantics tree. */
    private fun tags(): List<String> = listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG) + FunctionButton.entries.map(::functionTag)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun strip_starts_with_zero_set_mode_and_units_then_the_functions_in_order() = runComposeUiTest {
        val functions = millOrder.map { FunctionButtonUiState(it) }
        setContent {
            AppTheme(systemInDarkTheme = false) {
                Box(Modifier.width(160.dp).height(3000.dp)) {
                    FunctionStrip(GlobalButtonsUiState(), functions, NoReadoutEvents, StripForm.Rail, Modifier.fillMaxWidth().fillMaxHeight())
                }
            }
        }
        val expected = listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG) + millOrder.map(::functionTag)
        val tops = expected.map { tag -> onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops, "the rail shows the buttons from the top in the order $expected")
        assertEquals(tops.size, tops.toSet().size)
        assertTrue(tags().containsAll(expected))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun strip_scrolls_when_the_buttons_do_not_fit() = runComposeUiTest {
        val functions = millOrder.map { FunctionButtonUiState(it) }
        setContent {
            AppTheme(systemInDarkTheme = false) {
                Box(Modifier.width(160.dp).height(400.dp)) {
                    FunctionStrip(GlobalButtonsUiState(), functions, NoReadoutEvents, StripForm.Rail, Modifier.fillMaxWidth().fillMaxHeight())
                }
            }
        }
        val rail = onNodeWithTag(FUNCTION_STRIP_TAG).fetchSemanticsNode()
        assertNotNull(rail.config.getOrNull(SemanticsActions.ScrollBy), "the rail can scroll")
        assertNotNull(rail.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun portrait_strip_is_a_grid_of_two_rows_that_scrolls() = runComposeUiTest {
        val functions = millOrder.map { FunctionButtonUiState(it) }
        setContent {
            AppTheme(systemInDarkTheme = false) {
                Box(Modifier.width(800.dp).height(200.dp)) {
                    FunctionStrip(GlobalButtonsUiState(), functions, NoReadoutEvents, StripForm.TwoRows, Modifier.fillMaxWidth())
                }
            }
        }
        val all = listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG) + millOrder.map(::functionTag)
        val tops = all.map { tag -> onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y }.toSet()
        assertEquals(2, tops.size, "two rows of buttons: $tops")
        val strip = onNodeWithTag(FUNCTION_STRIP_TAG).fetchSemanticsNode()
        assertNotNull(strip.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange), "the two rows scroll")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun expanded_strip_is_a_grid_with_the_columns_of_its_width_in_order() = runComposeUiTest {
        val functions = millOrder.map { FunctionButtonUiState(it) }
        setContent {
            AppTheme(systemInDarkTheme = false) {
                Box(Modifier.width(540.dp).height(2000.dp)) {
                    FunctionStrip(GlobalButtonsUiState(), functions, NoReadoutEvents, StripForm.Grid, Modifier.fillMaxWidth())
                }
            }
        }
        val all = listOf(ZERO_SET_TAG, MODE_TAG, UNITS_TAG) + millOrder.map(::functionTag)
        val positions = all.map { tag -> onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot }
        // 540 dp gives 4 columns of buttons of approximately 120 dp.
        assertEquals(4, positions.map { it.x }.toSet().size, "four columns: $positions")
        assertEquals((all.size + 3) / 4, positions.map { it.y }.toSet().size, "the rows of the grid: $positions")
        // The order is from the left to the right, then from the top to the bottom.
        val order = positions.indices.sortedWith(compareBy({ positions[it].y }, { positions[it].x }))
        assertEquals(positions.indices.toList(), order, "the grid keeps the order of the strip")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun grid_scrolls_when_the_buttons_do_not_fit() = runComposeUiTest {
        val functions = millOrder.map { FunctionButtonUiState(it) }
        setContent {
            AppTheme(systemInDarkTheme = false) {
                Box(Modifier.width(540.dp).height(150.dp)) {
                    FunctionStrip(GlobalButtonsUiState(), functions, NoReadoutEvents, StripForm.Grid, Modifier.fillMaxWidth())
                }
            }
        }
        val grid = onNodeWithTag(FUNCTION_STRIP_TAG).fetchSemanticsNode()
        assertNotNull(grid.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange), "the grid scrolls")
    }

    @Test
    fun grid_columns_follow_the_width_with_at_least_two() {
        assertEquals(4, gridColumns(532.dp, 120.dp, 8.dp))
        assertEquals(3, gridColumns(500.dp, 120.dp, 8.dp))
        assertEquals(2, gridColumns(100.dp, 120.dp, 8.dp))
        assertEquals(MIN_GRID_COLUMNS, gridColumns(0.dp, 120.dp, 8.dp))
    }
}
