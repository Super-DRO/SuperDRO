package org.superdro.core.ui.functions.subdatums

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.sd_deleted
import org.superdro.core.ui.LocalAppSnackbarHostState
import org.superdro.core.ui.functions.common.AttachUndoHost
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests and the screenshots of the sub-datum panel and the sub-datum sheets. */
@OptIn(ExperimentalTestApi::class)
class SubDatumScreensTest {

    private val rows = listOf(
        SubDatumRowUi("1", "Circle - 1", "", "X 100.000 • Y 100.000", listOf(TagMarkUi("Circle", 0)), kind = SubDatumGroupKind.Pattern),
        SubDatumRowUi("2", "Circle - 2", "", "X 143.301 • Y 75.000", listOf(TagMarkUi("Circle", 0)), active = true, kind = SubDatumGroupKind.Pattern),
        SubDatumRowUi("3", "Hole A", "Left mounting hole", "X 10.000 • Y 5.000 • Z -2.000", listOf(TagMarkUi("holes", 1), TagMarkUi("left", 2))),
        SubDatumRowUi("4", "Point 4", "", "X 0.000 • Y 0.000"),
        SubDatumRowUi("g", "Guide", "", "X = 25.000", guide = true),
    )

    private val state = SubDatumPanelUiState(
        loaded = true,
        workspaceId = "w",
        workspaceName = "Default Workspace",
        rows = rows,
        pointCount = 4,
        activeId = "2",
        filterRows = listOf(
            TagFilterRowUi(null, -1, false),
            TagFilterRowUi("Circle", 0, false),
            TagFilterRowUi("holes", 1, true),
            TagFilterRowUi("left", 2, false),
        ),
        canUndoTransform = true,
    )

    private class RecordingEvents : SubDatumPanelEvents {
        val calls = mutableListOf<String>()
        override fun onTap(id: String) { calls += "tap $id" }
        override fun onLongPress(id: String) { calls += "long $id" }
        override fun onMenuDismiss() { calls += "dismiss" }
        override fun onEdit(id: String) { calls += "edit $id" }
        override fun onSelectPoint(id: String) { calls += "selectPoint $id" }
        override fun onDelete(id: String) { calls += "delete $id" }
        override fun onSetOrigin(id: String) { calls += "origin $id" }
        override fun onAlignSpindle(id: String) { calls += "spindle $id" }
        override fun onAlignOrigin(id: String) { calls += "alignOrigin $id" }
        override fun onPair(pointA: String, pointB: String) { calls += "pair $pointA $pointB" }
        override fun onWorkspaces() { calls += "workspaces" }
        override fun onSelecting(on: Boolean) { calls += "selecting $on" }
        override fun onSelectAll() { calls += "selectAll" }
        override fun onDeselect() { calls += "deselect" }
        override fun onTagSelected() { calls += "tag" }
        override fun onCopy() { calls += "copy" }
        override fun onFilterToggle() { calls += "filter" }
        override fun onFilterLongPress() { calls += "filterLong" }
        override fun onFilterRow(tag: String?) { calls += "filterRow $tag" }
        override fun onToggle3d() { calls += "3d" }
        override fun onDeleteSelectedOrAll() { calls += "deleteSelectedOrAll" }
        override fun onDeleteAll() { calls += "deleteAll" }
        override fun onFirst() { calls += "first" }
        override fun onPrevious() { calls += "previous" }
        override fun onNext() { calls += "next" }
        override fun onLast() { calls += "last" }
        override fun onImport() { calls += "import" }
        override fun onExport() { calls += "export" }
        override fun onWorkspaceCommands() { calls += "commands" }
        override fun onUndoTransform() { calls += "undoTransform" }
    }

    @Test
    fun thePanelFlowTapLongPressAndDelete() = runComposeUiTest {
        val events = RecordingEvents()
        setContent { AppTheme { Box(Modifier.width(400.dp).height(800.dp)) { SubDatumPanel(state, events) } } }
        onNodeWithTag(SubDatumPanelTags.row("1")).performClick()
        onNodeWithTag(SubDatumPanelTags.row("3")).performTouchInput { longClick() }
        onNodeWithTag(SubDatumPanelTags.NEXT).performClick()
        onNodeWithTag(SubDatumPanelTags.FIRST).performClick()
        onNodeWithTag(SubDatumPanelTags.WORKSPACE).performClick()
        onNodeWithTag(SubDatumPanelTags.DELETE).performClick()
        onNodeWithTag(SubDatumPanelTags.COPY).performClick()
        assertEquals(listOf("tap 1", "long 3", "next", "first", "workspaces", "deleteSelectedOrAll", "copy"), events.calls)
        events.calls.clear()
        setContent {
            AppTheme {
                Box(Modifier.width(400.dp).height(800.dp)) {
                    SubDatumPanel(state.copy(menu = PointMenuUi("3", "Hole A", "2", "Circle - 2")), events)
                }
            }
        }
        onNodeWithText("Pair commands: Circle - 2 and Hole A").assertIsDisplayed()
        onNodeWithTag(SubDatumPanelTags.MENU_DELETE).performClick()
        assertEquals(listOf("delete 3"), events.calls)
    }

    @Test
    fun aDeleteShowsUndoInTheSnackbar() = runComposeUiTest {
        var undone = 0
        lateinit var offers: UndoOffers
        val host = SnackbarHostState()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalAppSnackbarHostState provides host) {
                    val scope = rememberCoroutineScope()
                    // A long time, so that the clock of the test does not end the offer.
                    offers = remember { UndoOffers(scope, durationMs = 600_000L) }
                    AttachUndoHost(offers)
                    Box(Modifier.fillMaxSize()) { SnackbarHost(host, Modifier.align(Alignment.BottomCenter)) }
                }
            }
        }
        waitForIdle()
        runOnIdle { offers.offer(UiText.of(Res.string.sd_deleted, 3)) { undone++ } }
        waitUntil(timeoutMillis = 5_000) { host.currentSnackbarData != null }
        onNodeWithText("Deleted sub-datums: 3").assertIsDisplayed()
        onNodeWithText("Undo").performClick()
        waitUntil(timeoutMillis = 5_000) { undone == 1 }
        assertEquals(1, undone)
    }

    private val millEdit = SubDatumEditUiState(
        loaded = true,
        newPoint = false,
        name = "Hole A",
        note = "Left mounting hole",
        tags = "holes left",
        x = LengthFieldState.of(10_000.0, true),
        y = LengthFieldState.of(5_000.0, true),
        z = LengthFieldState.of(-2_000.0, true),
        a = NumberFieldState.ofArcSec(162_000.0),
        aLabel = "α",
        showA = true,
    )

    private class RecordingEditEvents : SubDatumEditEvents {
        val calls = mutableListOf<String>()
        override fun onName(text: String) { calls += "name $text" }
        override fun onNote(text: String) { calls += "note $text" }
        override fun onTags(text: String) { calls += "tags $text" }
        override fun onText(field: SubDatumField, text: String) { calls += "$field $text" }
        override fun onUnit(field: SubDatumField, metric: Boolean) { calls += "unit $field $metric" }
        override fun onSave() { calls += "save" }
        override fun onDelete() { calls += "delete" }
        override fun onDismiss() { calls += "dismiss" }
    }

    @Test
    fun theEditSheetSendsTheTypedTextAndSaves() = runComposeUiTest {
        val events = RecordingEditEvents()
        setContent { AppTheme { Column { SubDatumEditSheet(millEdit.copy(name = ""), events) } } }
        onNodeWithTag(fieldTextTag(SubDatumSheetTags.NAME)).performTextInput("B")
        onNodeWithTag(fieldTextTag(SubDatumSheetTags.X)).performTextInput("1")
        onNodeWithTag(PRIMARY_BUTTON_TAG).performClick()
        // The sheet is stateless: the test state does not change, so only the first event of each field counts.
        assertEquals("name B", events.calls.first())
        assertTrue(events.calls.any { it == "X 101" || it == "X 110" }, events.calls.toString())
        assertEquals("save", events.calls.last())
    }

    @Test
    fun theSubDatumScreensRenderInLightAndDarkMode() {
        val latheEdit = SubDatumEditUiState(
            loaded = true,
            newPoint = true,
            x = LengthFieldState.of(25_000.0, true),
            z = LengthFieldState.of(-30_000.0, true),
            lathe = true,
            xDiameter = true,
            showY = false,
        )
        val tag = TagSheetUiState(text = "holes", count = 3, suggestions = listOf("Circle", "Grid", "holes"))
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            renderFunctionScreenshot("functions-subdatums-panel-$mode", dark, widthDp = 420, heightDp = 800) {
                SubDatumPanel(state.copy(filterOpen = true, filterOn = true), RecordingEvents())
            }
            renderFunctionScreenshot("functions-subdatums-panel-portrait-$mode", dark, widthDp = 800, heightDp = 1280) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f))
                    Box(Modifier.fillMaxWidth().height(520.dp)) {
                        SubDatumPanel(state.copy(selecting = true, selectedIds = setOf("1", "3"), rows = rows.map { it.copy(selected = it.id in setOf("1", "3")) }), RecordingEvents())
                    }
                }
            }
            renderFunctionScreenshot("functions-subdatums-point-menu-$mode", dark, widthDp = 420, heightDp = 800) {
                SubDatumPanel(state.copy(menu = PointMenuUi("3", "Hole A", "2", "Circle - 2")), RecordingEvents())
            }
            renderFunctionScreenshot("functions-subdatums-shape-menu-$mode", dark) { ShapeMenuSheet(lathe = false, onSelect = {}, onDismiss = {}) }
            renderFunctionScreenshot("functions-subdatums-edit-mill-$mode", dark, heightDp = 900) { SubDatumEditSheet(millEdit, RecordingEditEvents()) }
            renderFunctionScreenshot("functions-subdatums-edit-lathe-$mode", dark) { SubDatumEditSheet(latheEdit, RecordingEditEvents()) }
            renderFunctionScreenshot("functions-subdatums-tag-$mode", dark) {
                TagSheet(tag, object : TagSheetEvents {
                    override fun onText(text: String) = Unit
                    override fun onSuggestion(tag: String) = Unit
                    override fun onApply() = Unit
                    override fun onDismiss() = Unit
                })
            }
        }
    }
}
