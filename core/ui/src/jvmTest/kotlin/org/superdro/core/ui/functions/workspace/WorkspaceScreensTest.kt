package org.superdro.core.ui.functions.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
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
import org.superdro.core.functions.transform.FlipAxis
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.functions.transform.RotateTransform
import org.superdro.core.model.AxisId
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.functions.common.PRIMARY_BUTTON_TAG
import org.superdro.core.ui.functions.common.fieldTextTag
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.navigation.SubDatumCommand
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests and the screenshots of the workspaces sheet, the machine datums, and the transforms. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceScreensTest {

    private val workspaces = WorkspacesUiState(
        loaded = true,
        rows = listOf(
            WorkspaceRowUiState("w1", "Default Workspace", active = true, selected = false),
            WorkspaceRowUiState("w2", "Second op", active = false, selected = false),
            WorkspaceRowUiState("w3", "Fixture plate", active = false, selected = false),
        ),
        activeId = "w1",
        undoLabel = "Rotate 30°",
    )

    private class RecordingWorkspaces : WorkspacesEvents {
        val calls = mutableListOf<String>()
        override fun onSelect(id: String) { calls += "select $id" }
        override fun onToggleSelected(id: String) { calls += "toggle $id" }
        override fun onClearSelection() { calls += "clear" }
        override fun onDeleteSelectedRequest() { calls += "deleteSelected" }
        override fun onDeleteSelectedConfirm() { calls += "confirm" }
        override fun onDeleteSelectedCancel() { calls += "cancel" }
        override fun onDelete(id: String) { calls += "delete $id" }
        override fun onRenameStart(id: String) { calls += "rename $id" }
        override fun onRenameText(text: String) { calls += "renameText $text" }
        override fun onRenameConfirm() { calls += "renameConfirm" }
        override fun onRenameCancel() { calls += "renameCancel" }
        override fun onNewName(text: String) { calls += "name $text" }
        override fun onCreate() { calls += "create" }
        override fun onUndoLastTransform() { calls += "undo" }
        override fun onDismiss() { calls += "dismiss" }
    }

    @Test
    fun theWorkspacesSheetFlow() = runComposeUiTest {
        val events = RecordingWorkspaces()
        setContent { AppTheme { WorkspacesSheet(workspaces, WorkspaceFieldsUiState(), events) } }
        onNodeWithTag(WorkspacesTags.NEW_NAME).performTextInput("Op 3")
        onNodeWithTag(WorkspacesTags.CREATE).performClick()
        onNodeWithTag(WorkspacesTags.row("w2")).performClick()
        onNodeWithTag(WorkspacesTags.row("w3")).performTouchInput { longClick() }
        onNodeWithTag(WorkspacesTags.rename("w3")).performClick()
        onNodeWithTag(WorkspacesTags.delete("w2")).performClick()
        onNodeWithTag(WorkspacesTags.UNDO).performClick()
        // The fixed state gives the text field its old value again: that event is not part of the flow.
        assertEquals(listOf("name Op 3", "create", "select w2", "toggle w3", "rename w3", "delete w2", "undo"), events.calls.filter { it != "name " })
    }

    @Test
    fun theMultiSelectDeleteAsksFirst() = runComposeUiTest {
        val events = RecordingWorkspaces()
        val selecting = workspaces.copy(
            rows = workspaces.rows.map { it.copy(selected = it.id != "w1") },
            selectedIds = setOf("w2", "w3"),
        )
        setContent { AppTheme { WorkspacesSheet(selecting, WorkspaceFieldsUiState(confirmDeleteSelected = true), events) } }
        onNodeWithText("Delete 2 workspaces?").assertExists()
        onNodeWithText("Delete", useUnmergedTree = true).assertExists()
    }

    private class RecordingTransform : TransformEvents {
        val calls = mutableListOf<String>()
        override fun onFlipAxis(axis: FlipAxis) { calls += "flip $axis" }
        override fun onAngle(text: String) { calls += "angle $text" }
        override fun onPercent(text: String) { calls += "percent $text" }
        override fun onScaleZ(on: Boolean) { calls += "z $on" }
        override fun onSource(source: PivotSource) { calls += "source $source" }
        override fun onSubDatum(id: String) { calls += "sub $id" }
        override fun onText(axis: AxisId, text: String) { calls += "text $axis $text" }
        override fun onUnit(axis: AxisId, metric: Boolean) { calls += "unit $axis $metric" }
        override fun onApply() { calls += "apply" }
        override fun onGuideAnswer(policy: GuidePolicy?) { calls += "guides $policy" }
        override fun onDismiss() { calls += "dismiss" }
    }

    private fun transformState(kind: WorkspaceTransformKind, source: PivotSource = PivotSource.Custom) = TransformUiState(
        loaded = true,
        kind = kind,
        angle = NumberFieldState.of(30.0),
        percent = NumberFieldState.of(150.0),
        source = source,
        fields = mapOf(
            AxisId.X to LengthFieldState.of(10_000.0, true),
            AxisId.Y to LengthFieldState.of(25_000.0, true),
            AxisId.Z to LengthFieldState.of(0.0, true),
        ),
        labels = mapOf(AxisId.X to "X", AxisId.Y to "Y", AxisId.Z to "Z"),
        subDatums = listOf(PivotChoice("s", "Circle - 1", listOf(UiText.Plain("X 100.000"), UiText.Plain("Y 50.000")))),
    )

    @Test
    fun theRotateSheetFlow() = runComposeUiTest {
        val events = RecordingTransform()
        setContent { AppTheme { TransformSheet(transformState(WorkspaceTransformKind.Rotate), events) } }
        onNodeWithTag(fieldTextTag(TransformTags.VALUE)).performTextInput("5")
        onNodeWithTag("${TransformTags.SOURCE}-1").performClick()
        onNodeWithTag(fieldTextTag(TransformTags.axis(AxisId.X))).performTextInput("1")
        onNodeWithTag(PRIMARY_BUTTON_TAG).performClick()
        // The fixed state gives each text field its old value again, so only the events of the input count.
        assertTrue(events.calls.any { call -> call.startsWith("angle ") && "5" in call }, events.calls.toString())
        assertTrue("source Origin" in events.calls)
        assertTrue(events.calls.any { call -> call.startsWith("text X ") && call != "text X 10" }, events.calls.toString())
        assertEquals("apply", events.calls.last())
    }

    @Test
    fun theGuideQuestionGivesThePolicy() = runComposeUiTest {
        val events = RecordingTransform()
        setContent { AppTheme { TransformSheet(transformState(WorkspaceTransformKind.Rotate).copy(guideQuestion = 2), events) } }
        onNodeWithTag(TransformTags.GUIDES_CONVERT).performClick()
        assertEquals(listOf("guides Convert"), events.calls)
    }

    private object NoPair : PairEvents {
        override fun onMove() = Unit
        override fun onMoveConfirm() = Unit
        override fun onDeleteB(delete: Boolean) = Unit
        override fun onSpindle() = Unit
        override fun onRotateConfirm() = Unit
        override fun onGuideAnswer(policy: GuidePolicy?) = Unit
        override fun onQuestionCancel() = Unit
        override fun onDismiss() = Unit
    }

    private object NoDatumPanel : MachineDatumPanelEvents {
        override fun onTap(id: String) = Unit
        override fun onEdit(id: String) = Unit
        override fun onDelete(id: String) = Unit
        override fun onAdd() = Unit
        override fun onQuickCapture() = Unit
        override fun onApplyConfirm() = Unit
        override fun onApplyCancel() = Unit
        override fun onProblemShown() = Unit
    }

    private object NoDatumEdit : MachineDatumEditEvents {
        override fun onName(text: String) = Unit
        override fun onNote(text: String) = Unit
        override fun onText(axis: AxisId, text: String) = Unit
        override fun onUnit(axis: AxisId, metric: Boolean) = Unit
        override fun onSync(axis: AxisId) = Unit
        override fun onSave() = Unit
        override fun onDismiss() = Unit
    }

    private object NoCommands : WorkspaceCommandsEvents {
        override fun onAlignOrigin(subDatumId: String) = Unit
        override fun onFlip() = Unit
        override fun onRotate() = Unit
        override fun onScale() = Unit
        override fun onUndo() = Unit
        override fun onDismiss() = Unit
    }

    @Test
    fun theWorkspaceScreensRenderInLightAndDarkMode() {
        val datums = MachineDatumPanelUiState(
            loaded = true,
            rows = listOf(
                MachineDatumRowUiState("1", "Datum 1", listOf(UiText.Plain("X 152.400"), UiText.Plain("Y 76.200"), UiText.Plain("Z -12.700")), UiText.Plain("mm")),
                MachineDatumRowUiState("2", "Vise jaw", listOf(UiText.Plain("X 10.000"), UiText.Plain("Y 20.000")), UiText.Plain("mm")),
            ),
        )
        val apply = datums.copy(
            apply = DatumApplyUiState("1", "Datum 1", listOf(UiText.Plain("X -2.400 mm"), UiText.Plain("Y -1.200 mm"), UiText.Plain("Z -7.300 mm"))),
        )
        val edit = MachineDatumEditUiState(
            loaded = true,
            newDatum = true,
            name = "Datum 3",
            note = "Vise, fixed jaw",
            axes = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular),
            x = LengthFieldState.of(152_400.0, true),
            y = LengthFieldState.of(76_200.0, true),
            z = LengthFieldState.of(-12_700.0, true),
            a = NumberFieldState.of(90.0),
            labels = mapOf(AxisId.X to "X", AxisId.Y to "Y", AxisId.Z to "Z", AxisId.Angular to "α"),
        )
        val pair = PairUiState(
            loaded = true,
            nameA = "Hole 1",
            nameB = "Hole 2",
            distance = UiText.Plain("5.000 mm"),
            angleDegrees = "0°",
            toleranceDegrees = "0.162°",
            tooClose = true,
            spindleVisible = true,
            spindleEnabled = true,
        )
        val rotateQuestion = pair.copy(
            question = PairQuestion.Rotate("90°", UiText.Plain("0.000 mm"), "0.162°", RotateTransform.ofDegrees(90.0, 0.0, 0.0)),
        )
        val commands = WorkspaceCommandsUiState(loaded = true, transforms = true, activeSubDatumId = "s", undoLabel = "Flip")
        val origin = SubDatumCommandUiState(loaded = true, command = SubDatumCommand.AlignOriginToSubDatum, name = "Circle - 1")
        val spindle = SubDatumCommandUiState(
            loaded = true,
            command = SubDatumCommand.AlignToSpindle,
            name = "Circle - 1",
            shifts = listOf(UiText.Plain("X -35.000 mm"), UiText.Plain("Y -30.000 mm")),
        )
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            renderFunctionScreenshot("functions-workspace-sheet-$mode", dark) {
                WorkspacesSheet(workspaces, WorkspaceFieldsUiState(newName = "Op 3"), RecordingWorkspaces())
            }
            renderFunctionScreenshot("functions-workspace-sheet-portrait-$mode", dark, widthDp = 800, heightDp = 1280) {
                WorkspacesSheet(workspaces, WorkspaceFieldsUiState(), RecordingWorkspaces())
            }
            renderFunctionScreenshot("functions-workspace-datums-$mode", dark, widthDp = 360, heightDp = 600) {
                Box(Modifier.width(360.dp)) { MachineDatumPanel(datums, NoDatumPanel) }
            }
            renderFunctionScreenshot("functions-workspace-datum-apply-$mode", dark) { MachineDatumPanel(apply, NoDatumPanel) }
            renderFunctionScreenshot("functions-workspace-datum-edit-$mode", dark) { MachineDatumEditSheet(edit, NoDatumEdit) }
            renderFunctionScreenshot("functions-workspace-commands-$mode", dark) { WorkspaceCommandsDialog(commands, NoCommands) }
            renderFunctionScreenshot("functions-workspace-flip-$mode", dark) {
                TransformSheet(transformState(WorkspaceTransformKind.Flip), RecordingTransform())
            }
            renderFunctionScreenshot("functions-workspace-rotate-$mode", dark) {
                TransformSheet(transformState(WorkspaceTransformKind.Rotate, PivotSource.Current), RecordingTransform())
            }
            renderFunctionScreenshot("functions-workspace-scale-$mode", dark) {
                TransformSheet(transformState(WorkspaceTransformKind.Scale, PivotSource.SubDatum).copy(subDatumId = "s"), RecordingTransform())
            }
            renderFunctionScreenshot("functions-workspace-guides-$mode", dark) {
                TransformSheet(transformState(WorkspaceTransformKind.Rotate).copy(guideQuestion = 2), RecordingTransform())
            }
            renderFunctionScreenshot("functions-workspace-pair-$mode", dark) { PairSheet(pair, NoPair) }
            renderFunctionScreenshot("functions-workspace-pair-rotate-$mode", dark) { PairSheet(rotateQuestion, NoPair) }
            renderFunctionScreenshot("functions-workspace-origin-$mode", dark) { SubDatumCommandDialog(origin, onConfirm = {}, onDismiss = {}) }
            renderFunctionScreenshot("functions-workspace-spindle-$mode", dark) { SubDatumCommandDialog(spindle, onConfirm = {}, onDismiss = {}) }
        }
    }
}
