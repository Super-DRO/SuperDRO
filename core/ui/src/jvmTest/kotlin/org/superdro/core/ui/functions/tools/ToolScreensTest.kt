package org.superdro.core.ui.functions.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.theme.AppTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription

/** The Compose UI tests and the screenshots of the tool library, the tool edit sheet, and the tool panel. */
@OptIn(ExperimentalTestApi::class)
class ToolScreensTest {

    private val millTools = listOf(
        Tool(id = "1", name = "1/4 End Mill", description = "2 flute HSS", metric = false, diameterUm = 6_350.0, toothCount = 2, zOffsetUm = 31_750.0),
        Tool(id = "2", name = "6 mm Drill", metric = true, diameterUm = 6_000.0, toothCount = 2, zOffsetUm = -12_500.0),
        Tool(id = "3", name = "10 mm Face Mill", metric = true, diameterUm = 10_000.0, toothCount = 4, zOffsetUm = -3_000.0),
    )

    private fun rows(tools: List<Tool>, activeId: String? = null, selected: Set<String> = emptySet()) = tools.map { tool ->
        ToolRowUiState(tool.id, tool.name, tool.description, ToolTexts.row(tool, profileMetric = false), tool.id == activeId, tool.id in selected)
    }

    private class RecordingEvents : ToolLibraryEvents {
        val calls = mutableListOf<String>()
        override fun onBack() { calls += "back" }
        override fun onAdd() { calls += "add" }
        override fun onEdit(id: String) { calls += "edit $id" }
        override fun onDelete(id: String) { calls += "delete $id" }
        override fun onToggleSelected(id: String) { calls += "select $id" }
        override fun onSelectAll() { calls += "selectAll" }
        override fun onClearSelection() { calls += "clear" }
        override fun onDeleteSelected() { calls += "deleteSelected" }
        override fun onSort(sort: ToolSort) { calls += "sort $sort" }
        override fun onSharedLibrary(on: Boolean) { calls += "shared $on" }
        override fun onImport() { calls += "import" }
        override fun onExport() { calls += "export" }
    }

    @Test
    fun theAddToolButtonHasALabelInTheTree() = runComposeUiTest {
        val events = RecordingEvents()
        setContent { AppTheme { ToolLibraryScreen(ToolLibraryUiState(loaded = true, rows = rows(millTools)), events) } }
        // The merged node of the button does not get the text of the button, so the button has
        // the label as its content description, with the click action.
        onNodeWithContentDescription("Add Tool").assertHasClickAction().performClick()
        assertEquals(listOf("add"), events.calls)
    }

    @Test
    fun theToolLibraryFlow() = runComposeUiTest {
        val events = RecordingEvents()
        var state = ToolLibraryUiState(loaded = true, rows = rows(millTools))
        setContent { AppTheme { ToolLibraryScreen(state, events) } }
        onNodeWithTag(TOOL_ADD_TAG).performClick()
        onNodeWithTag(toolRowTag("2")).performClick()
        onNodeWithTag(toolRowTag("3")).performTouchInput { longClick() }
        onNodeWithTag(TOOL_SHARED_TAG).performClick()
        onNodeWithTag(TOOL_EXPORT_TAG).performClick()
        onNodeWithTag(TOOL_IMPORT_TAG).performClick()
        assertEquals(listOf("add", "edit 2", "select 3", "shared true", "export", "import"), events.calls)
        events.calls.clear()
        state = state.copy(rows = rows(millTools, selected = setOf("3")), selectedIds = setOf("3"))
        setContent { AppTheme { ToolLibraryScreen(state, events) } }
        onNodeWithText("Selected: 1").assertIsDisplayed()
        onNodeWithTag(toolRowTag("1")).performClick()
        onNodeWithTag(TOOL_DELETE_SELECTED_TAG).performClick()
        assertEquals(listOf("select 1", "deleteSelected"), events.calls)
    }

    @Test
    fun aTapOnTheToolPanelGivesTheTool() = runComposeUiTest {
        val taps = mutableListOf<String>()
        setContent { AppTheme { ToolPanel(ToolPanelUiState(true, rows(millTools, activeId = "2")), onTap = { taps += it }, onOpenLibrary = {}) } }
        onNodeWithTag(toolPanelRowTag("1")).performClick()
        onNodeWithText("Active").assertIsDisplayed()
        assertEquals(listOf("1"), taps)
    }

    private object NoEditEvents : ToolEditEvents {
        override fun onName(text: String) = Unit
        override fun onDescription(text: String) = Unit
        override fun onMetric(metric: Boolean) = Unit
        override fun onDiameter(text: String) = Unit
        override fun onToothCount(text: String) = Unit
        override fun onZOffset(text: String) = Unit
        override fun onXOffset(text: String) = Unit
        override fun onInternal(internal: Boolean) = Unit
        override fun onSync(axis: AxisId) = Unit
        override fun onSave() = Unit
        override fun onDismiss() = Unit
    }

    @Test
    fun theToolScreensRenderInLightAndDarkMode() {
        val library = ToolLibraryUiState(loaded = true, rows = rows(millTools, activeId = "2", selected = setOf("3")), selectedIds = setOf("3"))
        val mill = ToolEditUiState(
            loaded = true, newTool = false, kind = ToolKind.Mill, name = "6 mm Drill", description = "Carbide", metric = true,
            diameter = LengthFieldState.of(6_000.0, true), toothCount = NumberFieldState.ofCount(2), zOffset = LengthFieldState.of(-12_500.0, true),
        )
        val lathe = ToolEditUiState(
            loaded = true, kind = ToolKind.Lathe, name = "Boring Bar", description = "3/8 bar", metric = false,
            xOffset = LengthFieldState.of(-3_175.0, false), zOffset = LengthFieldState.of(9_525.0, false), internal = true,
        )
        val latheTools = listOf(
            Tool(id = "4", kind = ToolKind.Lathe, name = "Boring Bar", xOffsetUm = -3_175.0, zOffsetUm = 9_525.0, internal = true),
            Tool(id = "5", kind = ToolKind.Lathe, name = "OD Turning", description = "CCMT insert"),
        )
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            renderFunctionScreenshot("functions-tools-library-$mode", dark) { ToolLibraryScreen(library, RecordingEvents()) }
            renderFunctionScreenshot("functions-tools-library-portrait-$mode", dark, widthDp = 800, heightDp = 1280) {
                ToolLibraryScreen(library.copy(selectedIds = emptySet(), rows = rows(millTools, activeId = "2")), RecordingEvents())
            }
            renderFunctionScreenshot("functions-tools-edit-mill-$mode", dark) { ToolEditSheet(mill, NoEditEvents) }
            renderFunctionScreenshot("functions-tools-edit-lathe-$mode", dark) { ToolEditSheet(lathe, NoEditEvents) }
            renderFunctionScreenshot("functions-tools-panel-$mode", dark, widthDp = 360, heightDp = 600) {
                Box(Modifier.width(360.dp)) { ToolPanel(ToolPanelUiState(true, rows(latheTools, activeId = "4")), onTap = {}, onOpenLibrary = {}) }
            }
        }
    }
}
