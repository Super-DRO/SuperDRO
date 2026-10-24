package org.superdro.validation.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisId
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.AxisDetailsRoute
import org.superdro.core.ui.navigation.CalibrationWizardRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.PLACEHOLDER_TAG
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.navigation.ShapeMenuRoute
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.appEntryRegistry
import org.superdro.core.ui.readout.keypad.KEYPAD_FIELD_TAG

/**
 * Each SuperDRO screen, sheet, and dialog of the table exists with the kind
 * of the table ("Screen" is an entry of the tab back stack, "Sheet" is a `ModalBottomSheet` in
 * portrait and a dialog in landscape, "Dialog" is an `AlertDialog`), and opens in `App()` with no
 * placeholder ([ScreensOpenFirstHalfValidationTest] and [ScreensOpenSecondHalfValidationTest]). The
 * back gesture closes each sheet and screen.
 */
@OptIn(ExperimentalTestApi::class)
class ScreensValidationTest {

    @Test
    fun each_entry_is_registered_with_the_kind_of_the_table() {
        val registry = appEntryRegistry()
        val problems = (ScreensEntries.entries(ScreensEntries.noIds) + ScreensEntries.Entry(CalibrationWizardRoute(AxisId.X), EntryKind.Screen)).mapNotNull { entry ->
            val spec = when (val route = entry.route) {
                is TabRoute -> registry.spec(route)
                is GlobalRoute -> registry.spec(route)
                else -> null
            }
            when {
                spec == null -> "${entry.route}: no entry"
                entry.kind != null && registry.kindOf(entry.route) != entry.kind -> "${entry.route}: ${registry.kindOf(entry.route)}, the table gives ${entry.kind}"
                else -> null
            }
        }
        assertEquals(emptyList(), problems)
    }

    fun a_sheet_is_a_bottom_sheet_in_portrait_and_a_dialog_in_landscape() = AppGraph().use { graph ->
        graph.connect("mill", "Mill")
        listOf(1280 to 800, 800 to 1280).forEach { (width, height) ->
            runWindowTest(width, height) {
                setContent { ProvideDI(graph.di) { App() } }
                waitUntil("tab", 10_000L) { onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty() }
                open(graph, ScreensEntries.Entry(KeypadRoute(AxisId.X), EntryKind.Sheet))
                waitForIdle()
                mainClock.advanceTimeBy(1_500L)
                val field = onNodeWithTag(KEYPAD_FIELD_TAG).rect()
                if (width > height) {
                    // A dialog: centered, with space at the left and the right.
                    assertTrue(field.left > 100f && field.right < width - 100f, "dialog in landscape: $field")
                } else {
                    // A bottom sheet: the content is in the bottom part of the window.
                    assertTrue(field.top > height * 0.3f, "bottom sheet in portrait: $field")
                }
                closeAll(graph)
                waitForIdle()
            }
        }
    }

    @Test
    fun each_sheet_can_be_closed_with_no_choice() = AppGraph().use { graph ->
        graph.connect("mill", "Mill")
        // The back action closes each sheet.
        // The JVM test has no back key of the system: the test uses the other dismiss of the same
        // sheet, a tap outside it (the dialog in landscape, the scrim of the bottom sheet in portrait).
        listOf(Triple(1280, 800, androidx.compose.ui.geometry.Offset(10f, 790f)), Triple(800, 1280, androidx.compose.ui.geometry.Offset(400f, 120f))).forEach { (width, height, outside) ->
            runWindowTest(width, height) {
                setContent { ProvideDI(graph.di) { App() } }
                waitUntil("tab", 10_000L) { onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty() }
                listOf<TabRoute>(AxisDetailsRoute(AxisId.X), ShapeMenuRoute, KeypadRoute(AxisId.X)).forEach { route ->
                    open(graph, ScreensEntries.Entry(route, EntryKind.Sheet))
                    waitForIdle()
                    mainClock.advanceTimeBy(1_500L)
                    assertTrue(onAllNodes(isRoot()).fetchSemanticsNodes().size >= 2, "$route shows")
                    onAllNodes(isRoot())[0].performTouchInput { click(outside) }
                    mainClock.advanceTimeBy(1_500L)
                    waitUntil("$route closes in $width x $height", 5_000L) { graph.holder.tab(DeviceId("mill")).routes == listOf(ReadoutRoute) }
                }
                closeAll(graph)
            }
        }
    }

    @Test
    fun Calibrate_of_the_setup_reminder_opens_the_calibration_wizard() = AppGraph().use { graph ->
        graph.connect("mill", "Mill")
        runWindowTest(1280, 800) {
            setContent { ProvideDI(graph.di) { App() } }
            waitUntil("tab", 10_000L) { onAllNodesWithTag(org.superdro.core.ui.tabTag(DeviceId("mill"))).fetchSemanticsNodes().isNotEmpty() }
            // The route of "Calibrate" (PresenterReadoutEvents.onSetupCalibrate): the screen table gives the
            // calibration wizard (steps) as a screen.
            open(graph, ScreensEntries.Entry(CalibrationWizardRoute(AxisId.X), EntryKind.Screen))
            waitForIdle()
            mainClock.advanceTimeBy(1_500L)
            val placeholders = onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size
            assertEquals(0, placeholders, "the calibration wizard is a placeholder")
        }
    }
}
