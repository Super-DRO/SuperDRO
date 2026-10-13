package org.superdro.validation.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.GraphicalViewPlacement
import org.superdro.core.model.SidePanelMode
import org.superdro.core.ui.readout.ADD_POINT_TAG
import org.superdro.core.ui.readout.FUNCTION_STRIP_TAG
import org.superdro.core.ui.readout.FunctionButton
import org.superdro.core.ui.readout.ReadoutEvents
import org.superdro.core.ui.readout.STATUS_STRIP_TAG
import org.superdro.core.ui.readout.SUPPORTING_PANE_TAG
import org.superdro.core.ui.readout.actionTag
import org.superdro.core.ui.readout.rowTag
import org.superdro.core.ui.readout.sidePanelTag
import org.superdro.core.ui.readout.valueTag

/**
 * The areas of the main screen, the row height rule, and the layout of each window class. The
 * readout screen has the areas (status strip, readout rows, side panel, function
 * strip) in the positions of the layout figures. The windows have 1 px for each dp.
 */
@OptIn(ExperimentalTestApi::class)
class MainScreenLayoutValidationTest {

    private val order = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular, AxisId.Tachometer)

    private fun SkikoComposeUiTest.rows(axes: List<AxisId> = order): List<Rect> = axes.map { axis -> onNodeWithTag(rowTag(axis)).rect() }

    private fun near(expected: Float, actual: Float, tolerance: Float = 2f) = abs(expected - actual) <= tolerance

    @Test
    fun landscape_areas_are_in_the_positions_of_the_figure() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        val status = onNodeWithTag(STATUS_STRIP_TAG).rect()
        val rows = rows()
        val pane = onNodeWithTag(SUPPORTING_PANE_TAG).rect()
        val strip = onNodeWithTag(FUNCTION_STRIP_TAG).rect()
        // The status strip is at the top, over the full width.
        assertTrue(near(0f, status.left) && near(1280f, status.right), "status strip $status")
        assertTrue(rows.all { it.top >= status.bottom }, "rows below the status strip")
        assertTrue(pane.top >= status.bottom && strip.top >= status.bottom)
        // The readout rows are in the order of the figure.
        val tops = rows.map { it.top }
        assertEquals(tops.sorted(), tops, "rows X, Y, Z, α, RPM from the top")
        // The readout pane is at the left, about 60 % of the width.
        rows.forEach { row -> assertTrue(near(0f, row.left, 4f), "row at the left edge: $row") }
        val share = rows.maxOf { it.right } / 1280f
        assertTrue(share in 0.55f..0.65f, "the readout pane has about 60 % of the width: $share")
        // The supporting pane is between the readout and the rail. The rail is at the right edge.
        assertTrue(pane.left >= rows.maxOf { it.right } - 2f && pane.right <= strip.left + 2f, "pane $pane, strip $strip")
        assertTrue(near(1280f, strip.right, 4f), "the strip at the right edge: $strip")
        assertTrue(strip.height > strip.width, "a vertical rail: $strip")
    }

    @Test
    fun portrait_areas_are_in_the_positions_of_the_figure() = runWindowTest(800, 1280) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        val status = onNodeWithTag(STATUS_STRIP_TAG).rect()
        val rows = rows()
        val strip = onNodeWithTag(FUNCTION_STRIP_TAG).rect()
        val graphical = onNodeWithTag(SAMPLE_GRAPHICAL_TAG).rect()
        assertTrue(near(0f, status.left) && near(800f, status.right), "status strip $status")
        assertTrue(rows.all { it.top >= status.bottom })
        // Full width rows at the top (medium window class).
        rows.forEach { row -> assertTrue(near(0f, row.left, 4f) && near(800f, row.right, 4f), "full width row $row") }
        val tops = rows.map { it.top }
        assertEquals(tops.sorted(), tops)
        // The function strip is at the bottom, with the full width.
        assertTrue(near(1280f, strip.bottom, 8f), "strip at the bottom: $strip")
        assertTrue(strip.top >= rows.maxOf { it.bottom }, "strip below the rows")
        // The graphical view is below the readout by default.
        assertTrue(graphical.top >= rows.maxOf { it.bottom } - 1f && graphical.bottom <= strip.top + 1f, "graphical view $graphical")
    }

    @Test
    fun portrait_graphical_view_above_the_readout_with_the_setting() = runWindowTest(800, 1280) {
        setContent { SampleMainScreen(MainScreenSamples.mill(placement = GraphicalViewPlacement.AboveReadout), dark = false) }
        val graphical = onNodeWithTag(SAMPLE_GRAPHICAL_TAG).rect()
        val rows = rows()
        assertTrue(graphical.bottom <= rows.minOf { it.top } + 1f, "graphical view above the rows: $graphical, ${rows.first()}")
    }

    @Test
    fun portrait_graphical_view_full_screen_gives_no_pane() = runWindowTest(800, 1280) {
        setContent { SampleMainScreen(MainScreenSamples.mill(placement = GraphicalViewPlacement.FullScreen), dark = false) }
        assertEquals(0, onAllNodesWithTag(SAMPLE_GRAPHICAL_TAG).fetchSemanticsNodes().size)
        // The graphical view is then a full-screen entry: the strip always has its button.
        onNodeWithTag(org.superdro.core.ui.readout.functionTag(FunctionButton.GraphicalView)).assertExists()
    }

    @Test
    fun expanded_graphical_view_is_in_the_supporting_pane_while_the_side_panel_is_off() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = SidePanelMode.Off), dark = false) }
        val pane = onNodeWithTag(SUPPORTING_PANE_TAG).rect()
        val graphical = onNodeWithTag(SAMPLE_GRAPHICAL_TAG).rect()
        assertTrue(pane.contains(graphical.center) && graphical.width <= pane.width + 1f, "graphical $graphical in pane $pane")
        assertEquals(0, onAllNodesWithTag(SAMPLE_SIDE_PANEL_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun expanded_side_panel_replaces_the_graphical_view_in_the_supporting_pane() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = SidePanelMode.SubDatums), dark = false) }
        val pane = onNodeWithTag(SUPPORTING_PANE_TAG).rect()
        val side = onNodeWithTag(SAMPLE_SIDE_PANEL_TAG).rect()
        assertTrue(pane.contains(side.center), "side panel $side in pane $pane")
        assertEquals(0, onAllNodesWithTag(SAMPLE_GRAPHICAL_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun large_window_uses_the_expanded_layout() = runWindowTest(1600, 1000) {
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false) }
        val strip = onNodeWithTag(FUNCTION_STRIP_TAG).rect()
        assertTrue(near(1600f, strip.right, 4f) && strip.height > strip.width, "rail at the right edge: $strip")
        onNodeWithTag(SUPPORTING_PANE_TAG).assertExists()
        val share = rows().maxOf { it.right } / 1600f
        assertTrue(share in 0.55f..0.65f, "about 60 %: $share")
    }

    @Test
    fun portrait_side_panel_opens_as_a_bottom_sheet() = runWindowTest(800, 1280) {
        setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = SidePanelMode.SubDatums), dark = false) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000L)
        val side = onNodeWithTag(SAMPLE_SIDE_PANEL_TAG).assertIsDisplayed().rect()
        assertTrue(near(1280f, side.bottom, 8f) && side.top > 1280f * 0.5f, "a sheet at the bottom: $side")
        // No supporting pane in portrait.
        assertEquals(0, onAllNodesWithTag(SUPPORTING_PANE_TAG).fetchSemanticsNodes().size)
    }

    private class Recorder : ReadoutEvents {
        val calls = ArrayList<String>()
        override fun onFunctionSheet(open: Boolean) { calls += "sheet $open" }
        override fun onSetupSettings() { calls += "settings" }
        override fun onSetupCalibrate(axis: AxisId) { calls += "calibrate $axis" }
        override fun onLabelTap(axis: AxisId) { calls += "label $axis" }
        override fun onValueTap(axis: AxisId) { calls += "value $axis" }
    }

    @Test
    fun compact_function_strip_is_a_bottom_sheet_from_a_button() = runWindowTest(480, 900) {
        val events = Recorder()
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false, events = events) }
        assertEquals(0, onAllNodesWithTag(FUNCTION_STRIP_TAG).fetchSemanticsNodes().size, "no strip until the button opens the sheet")
        onNode(hasText("Functions") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf("sheet true"), events.calls)
    }

    @Test
    fun compact_bottom_sheet_shows_the_strip() = runWindowTest(480, 900) {
        setContent { SampleMainScreen(MainScreenSamples.mill(functionSheetOpen = true), dark = false) }
        waitForIdle()
        mainClock.advanceTimeBy(1_000L)
        onNodeWithTag(FUNCTION_STRIP_TAG).assertExists()
    }

    @Test
    fun row_height_is_one_over_max_3_count_of_the_readout_pane_landscape() {
        listOf(order, listOf(AxisId.X, AxisId.Y, AxisId.Z), listOf(AxisId.X, AxisId.Z)).forEach { axes ->
            runWindowTest(1280, 800) {
                val rows = MainScreenSamples.millRows.filter { it.axis in axes }
                setContent { SampleMainScreen(MainScreenSamples.mill(rows = rows), dark = false) }
                val rects = rows(axes)
                // The readout pane: from the top of the first row to the bottom of the window.
                val paneTop = rects.first().top
                val paneHeight = 800f - paneTop
                val expected = paneHeight / max(3, axes.size)
                rects.forEach { rect -> assertTrue(near(expected, rect.height, 1.5f), "$axes: row height ${rect.height}, expected $expected") }
            }
        }
    }

    @Test
    fun row_height_is_one_over_max_3_count_of_the_readout_pane_portrait() {
        listOf(order, listOf(AxisId.X, AxisId.Z)).forEach { axes ->
            runWindowTest(800, 1280) {
                val rows = MainScreenSamples.millRows.filter { it.axis in axes }
                setContent { SampleMainScreen(MainScreenSamples.mill(rows = rows), dark = false) }
                val rects = rows(axes)
                val graphical = onNodeWithTag(SAMPLE_GRAPHICAL_TAG).rect()
                // The readout pane: from the top of the first row to the divider above the graphical view.
                val paneHeight = graphical.top - rects.first().top
                val expected = paneHeight / max(3, axes.size)
                rects.forEach { rect -> assertTrue(near(expected, rect.height, 2f), "$axes: row height ${rect.height}, expected $expected") }
            }
        }
    }

    @Test
    fun setup_reminder_banner_is_above_the_rows_with_Settings_and_Calibrate() = runWindowTest(1280, 800) {
        val events = Recorder()
        setContent { SampleMainScreen(MainScreenSamples.mill(reminder = true), dark = false, events = events) }
        val banner = onNodeWithText("Calibration required").assertIsDisplayed().rect()
        val status = onNodeWithTag(STATUS_STRIP_TAG).rect()
        val first = onNodeWithTag(rowTag(AxisId.X)).rect()
        assertTrue(banner.top >= status.bottom && banner.bottom <= first.top, "banner $banner between $status and $first")
        onNode(hasText("Settings") and hasClickAction()).performClick()
        onNode(hasText("Calibrate") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(listOf("settings", "calibrate X"), events.calls)
    }

    @Test
    fun no_banner_with_no_reminder() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(reminder = false), dark = false) }
        assertEquals(0, onAllNodesWithTag("readout-setup-reminder").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithTag(rowTag(AxisId.X)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun plus_button_shows_in_off_and_sub_datums_and_hides_in_tools() {
        listOf(SidePanelMode.Off to 1, SidePanelMode.SubDatums to 1, SidePanelMode.Tools to 0).forEach { (mode, count) ->
            runWindowTest(1280, 800) {
                setContent { SampleMainScreen(MainScreenSamples.mill(sidePanel = mode), dark = false) }
                assertEquals(count, onAllNodesWithTag(ADD_POINT_TAG).fetchSemanticsNodes().size, "$mode")
            }
        }
    }

    @Test
    fun tool_tab_is_enabled_only_when_the_library_is_not_empty() {
        listOf(true, false).forEach { tools ->
            runWindowTest(1280, 800) {
                val state = MainScreenSamples.mill().let { it.copy(sidePanel = it.sidePanel.copy(toolsEnabled = tools)) }
                setContent { SampleMainScreen(state, dark = false) }
                val chip = onNodeWithTag(sidePanelTag(SidePanelMode.Tools))
                if (tools) chip.assertIsEnabled() else chip.assertIsNotEnabled()
                onNodeWithTag(sidePanelTag(SidePanelMode.SubDatums)).assertIsEnabled()
            }
        }
    }

    @Test
    fun status_strip_items_and_the_Measurement_chip() = runWindowTest(1280, 800) {
        setContent { SampleMainScreen(MainScreenSamples.mill(measurement = true), dark = false) }
        listOf("ABS", "Tool 3", "Datum S2", "1250 RPM", "Measurement").forEach { text ->
            val inStrip = onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(STATUS_STRIP_TAG)), useUnmergedTree = true)
            assertEquals(1, inStrip.fetchSemanticsNodes().size, text)
        }
    }

    @Test
    fun row_label_opens_details_and_value_opens_the_keypad_with_up_to_three_buttons() = runWindowTest(1280, 800) {
        val events = Recorder()
        setContent { SampleMainScreen(MainScreenSamples.mill(), dark = false, events = events) }
        onNode(hasText("X") and hasClickAction()).performClick()
        onNodeWithTag(valueTag(AxisId.X)).performClick()
        waitForIdle()
        assertEquals(listOf("label X", "value X"), events.calls)
        val buttons = AxisButtonMode.entries.count { mode -> onAllNodesWithTag(actionTag(AxisId.X, mode)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(buttons in 1..3, "up to three action buttons: $buttons")
    }

    @Test
    fun no_orientation_lock_in_the_manifest() {
        val manifest = org.superdro.validation.Traceability.rootDir.resolve("app/android/src/main/AndroidManifest.xml").readText()
        assertTrue("screenOrientation" !in manifest, "the activity has no orientation lock")
        assertTrue("resizeableActivity=\"false\"" !in manifest)
    }

    /** Gives the number of pixels of [hex] in [rect] of the window image. */
    private fun SkikoComposeUiTest.count(rect: Rect, hex: String): Int {
        val image = captureToImage()
        val all = pixels(image)
        var count = 0
        for (y in rect.top.toInt() until rect.bottom.toInt()) {
            for (x in rect.left.toInt() until rect.right.toInt()) {
                if ("#%06X".format(all[y * image.width + x] and 0xFFFFFF) == hex) count++
            }
        }
        return count
    }

    @Test
    fun each_linear_row_has_its_position_slider_below_the_value() {
        listOf(true, false).forEach { slider ->
            runWindowTest(1280, 800) {
                val rows = MainScreenSamples.millRows.map { row -> if (row.kind == org.superdro.core.ui.readout.RowKind.Linear) row.copy(slider = slider) else row }
                setContent { SampleMainScreen(MainScreenSamples.mill(rows = rows), dark = false) }
                waitForIdle()
                // The slider lines and ticks use onSurfaceVariant.
                val tick = org.superdro.core.ui.theme.Theme.lightScheme.onSurfaceVariant.hex()
                listOf(AxisId.X, AxisId.Y, AxisId.Z).forEach { axis ->
                    val row = onNodeWithTag(rowTag(axis)).rect()
                    val value = onNodeWithTag(valueTag(axis)).rect()
                    val below = Rect(value.left, value.bottom, value.right, row.bottom)
                    val pixels = if (below.height >= 1f) count(below, tick) else 0
                    if (slider) assertTrue(pixels > 50, "$axis: a slider below the value ($pixels pixels)") else assertEquals(0, pixels, "$axis: no slider")
                }
            }
        }
    }
}
