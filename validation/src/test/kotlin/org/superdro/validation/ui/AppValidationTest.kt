package org.superdro.validation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.ui.ADD_MACHINE_TAG
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.navigation.AddDeviceRoute
import org.superdro.core.ui.navigation.HoleCircleRoute
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.MachineSettingsRoute
import org.superdro.core.ui.navigation.PLACEHOLDER_TAG
import org.superdro.core.ui.navigation.ReadoutRoute
import org.superdro.core.ui.readout.actionTag
import org.superdro.core.ui.readout.keypad.KEYPAD_FIELD_TAG
import org.superdro.core.ui.readout.keypad.KEYPAD_RESULT_TAG
import org.superdro.core.ui.readout.rowTag
import org.superdro.core.ui.readout.valueTag
import org.superdro.core.ui.tabTag

/**
 * `App()` with the full DI graph and the fake adapter: a machine that comes online gets a tab and
 * its readout rows, the undo snackbar, the
 * keypad sheet with a physical keyboard, the frame actions, and the back action.
 */
@OptIn(ExperimentalTestApi::class)
class AppValidationTest {

    private fun SkikoComposeUiTest.app(graph: AppGraph) = setContent { ProvideDI(graph.di) { App() } }

    private fun SkikoComposeUiTest.waitFor(tag: String, timeoutMs: Long = 10_000L) =
        waitUntil("node $tag", timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun SkikoComposeUiTest.waitForText(text: String, timeoutMs: Long = 10_000L) =
        waitUntil("text $text", timeoutMs) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun SkikoComposeUiTest.label(axis: AxisId): String =
        onNodeWithTag(valueTag(axis)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    private fun SkikoComposeUiTest.waitForLabel(axis: AxisId, predicate: (String) -> Boolean) =
        waitUntil("label of $axis", 10_000L) {
            onAllNodesWithTag(valueTag(axis)).fetchSemanticsNodes().isNotEmpty() && predicate(label(axis))
        }

    @Test
    fun app_opens_with_the_empty_state_when_no_machine_is_online() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            waitForText("No machine is online")
            onNode(hasText("Add machine") and hasClickAction()).assertExists()
        }
    }

    @Test
    fun each_online_machine_gets_a_tab_with_the_rows_in_the_mill_and_lathe_order() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            graph.connect("mill", "Mill")
            graph.connect("lathe", "Lathe", MachineType.Lathe, scenario = "commands") { profile -> profile.copy(y = profile.y.copy(enabled = true)) }
            waitFor(tabTag(DeviceId("mill")))
            waitFor(tabTag(DeviceId("lathe")))
            waitUntil("connected", 10_000L) {
                onAllNodesWithText("Connected", useUnmergedTree = true).fetchSemanticsNodes().size >= 2
            }
            // The mill rows X, Y, Z.
            waitFor(rowTag(AxisId.Z))
            val mill = listOf(AxisId.X, AxisId.Y, AxisId.Z).map { axis -> onNodeWithTag(rowTag(axis)).rect().top }
            assertEquals(mill.sorted(), mill)
            // The lathe rows X, Z, then the auxiliary readout Y, then the tachometer.
            onNodeWithTag(tabTag(DeviceId("lathe"))).performClick()
            waitFor(rowTag(AxisId.Tachometer))
            val lathe = listOf(AxisId.X, AxisId.Z, AxisId.Y, AxisId.Tachometer).map { axis -> onNodeWithTag(rowTag(axis)).rect().top }
            assertEquals(lathe.sorted(), lathe, "lathe X, Z, Y, RPM")
            assertTrue(onAllNodesWithText("Auxiliary", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the auxiliary readout label")
        }
    }

    @Test
    fun zero_shows_the_Undo_snackbar_and_Undo_restores_the_value() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            graph.connect("mill", "Mill", scenario = "still")
            waitFor(actionTag(AxisId.X, AxisButtonMode.SetZero))
            waitForLabel(AxisId.X) { it.startsWith("X axis, ") }
            val before = label(AxisId.X)
            onNodeWithTag(actionTag(AxisId.X, AxisButtonMode.SetZero)).performClick()
            waitForText("Undo")
            waitForLabel(AxisId.X) { it == "X axis, 0.000 millimeters" }
            onNode(hasText("Undo") and hasClickAction()).performClick()
            waitForLabel(AxisId.X) { it == before }
        }
    }

    @Test
    fun value_tap_opens_the_keypad_and_a_physical_keyboard_types_into_it() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            graph.connect("mill", "Mill", scenario = "still")
            waitFor(valueTag(AxisId.X))
            waitForLabel(AxisId.X) { it.startsWith("X axis, ") }
            onNodeWithTag(valueTag(AxisId.X)).performClick()
            waitFor(KEYPAD_FIELD_TAG)
            assertEquals(KeypadRoute(AxisId.X), graph.holder.tab(DeviceId("mill")).routes.last())
            mainClock.advanceTimeBy(1_000L)
            // A physical keyboard types into the keypad field.
            typeOnKeyboard("5.5")
            waitUntil("result", 5_000L) {
                onNodeWithTag(KEYPAD_RESULT_TAG, useUnmergedTree = true).fetchSemanticsNode()
                    .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString().contains("5.500")
            }
            onNodeWithTag(KEYPAD_FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
            waitForLabel(AxisId.X) { it == "X axis, 5.500 millimeters" }
            waitUntil("the sheet closes", 5_000L) { graph.holder.tab(DeviceId("mill")).routes == listOf(ReadoutRoute) }
        }
    }

    @Test
    fun plus_opens_the_add_device_sheet() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            waitFor(ADD_MACHINE_TAG)
            onNodeWithTag(ADD_MACHINE_TAG).performClick()
            waitUntil("add-device", 5_000L) { graph.holder.globalEntries.lastOrNull()?.route == AddDeviceRoute }
            mainClock.advanceTimeBy(1_000L)
            assertEquals(0, onAllNodesWithTag(PLACEHOLDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun tab_menu_Machine_settings_opens_the_screen_in_the_tab() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            graph.connect("mill", "Mill")
            waitFor(tabTag(DeviceId("mill")))
            onNodeWithTag(tabTag(DeviceId("mill"))).performTouchInput { longClick() }
            waitForText("Machine settings")
            onNode(hasText("Machine settings") and hasClickAction()).performClick()
            waitUntil("machine settings", 5_000L) { graph.holder.tab(DeviceId("mill")).routes.last() == MachineSettingsRoute }
            // The back action closes each screen.
            mainClock.advanceTimeBy(1_000L)
            onNode(hasContentDescription("Back") and hasClickAction()).performClick()
            waitUntil("back", 5_000L) { graph.holder.tab(DeviceId("mill")).routes == listOf(ReadoutRoute) }
        }
    }

    @Test
    fun a_function_screen_keeps_its_field_values_while_the_tab_changes() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            graph.connect("mill", "Mill")
            graph.connect("mill2", "Mill 2", scenario = "commands")
            waitFor(tabTag(DeviceId("mill")))
            waitFor(tabTag(DeviceId("mill2")))
            onNodeWithTag(tabTag(DeviceId("mill"))).performClick()
            waitForIdle()
            runOnUiThread { graph.holder.tab(DeviceId("mill")).push(HoleCircleRoute) }
            mainClock.advanceTimeBy(1_000L)
            waitUntil("a field", 5_000L) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            val field = onAllNodes(hasSetTextAction())[0]
            field.performTextReplacement("37")
            waitForIdle()
            onNodeWithTag(tabTag(DeviceId("mill2"))).performClick()
            mainClock.advanceTimeBy(1_000L)
            onNodeWithTag(tabTag(DeviceId("mill"))).performClick()
            mainClock.advanceTimeBy(1_000L)
            waitUntil("the field again", 5_000L) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            val text = onAllNodes(hasSetTextAction())[0].fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("37", text)
            assertEquals(HoleCircleRoute, graph.holder.tab(DeviceId("mill")).routes.last())
        }
    }

    private fun SkikoComposeUiTest.openAddDevice(graph: AppGraph) {
        waitFor(ADD_MACHINE_TAG)
        onNodeWithTag(ADD_MACHINE_TAG).performClick()
        waitUntil("add-device", 5_000L) { graph.holder.globalEntries.lastOrNull()?.route == AddDeviceRoute }
        mainClock.advanceTimeBy(1_500L)
        waitForIdle()
    }

    @Test
    fun add_device_sheet_lists_the_links_with_Pair_new_adapter_and_port_8080() = AppGraph().use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            openAddDevice(graph)
            onNode(hasText("Pair new adapter") and hasClickAction()).assertExists()
            listOf("Bluetooth", "USB", "Wi-Fi", "Demo").forEach { link ->
                assertTrue(onAllNodesWithText(link, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the link type $link")
            }
            val port = onNodeWithTag("wifi-port").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("8080", port, "the default port of a Wi-Fi adapter")
        }
    }

    @Test
    fun add_device_sheet_of_a_release_build_has_no_Demo() = AppGraph(debug = false).use { graph ->
        runWindowTest(1280, 800) {
            app(graph)
            openAddDevice(graph)
            onNode(hasText("Pair new adapter") and hasClickAction()).assertExists()
            assertTrue(onAllNodesWithText("Demo", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no demo link in a release build")
        }
    }

    @Test
    fun the_app_settings_can_hide_a_link_type() = AppGraph().use { graph ->
        graph.appSettings.update { settings -> settings.copy(hiddenLinkTypes = setOf(org.superdro.core.model.TransportKind.Tcp)) }
        runWindowTest(1280, 800) {
            app(graph)
            openAddDevice(graph)
            assertTrue(onAllNodesWithText("Wi-Fi", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no Wi-Fi link")
            assertTrue(onAllNodesWithTag("wifi-port").fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText("Bluetooth", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
