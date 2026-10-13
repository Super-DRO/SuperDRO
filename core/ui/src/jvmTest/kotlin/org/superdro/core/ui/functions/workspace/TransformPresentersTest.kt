package org.superdro.core.ui.functions.workspace

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.transform.FlipAxis
import org.superdro.core.functions.transform.GuidePolicy
import org.superdro.core.model.AxisId
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.navigation.SubDatumCommand
import org.superdro.core.ui.navigation.WorkspaceTransformKind
import org.superdro.core.ui.text.UiText

/** The workspace transforms and the origin commands with the worked examples. */
class TransformPresentersTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)
    private val messages = mutableListOf<UiText>()

    @AfterTest
    fun close() = harness.close()

    private val mill = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)

    private suspend fun workspace(): Workspace = harness.readout.workspace()

    private suspend fun points(vararg points: SubDatum) {
        harness.store.machines.insertSubDatums(workspace().id, points.toList())
    }

    private fun transform(kind: WorkspaceTransformKind) =
        TransformPresenter(kind, harness.services, harness.presenter.frame, harness.presenter::positionUnavailable, harness.tab.undo)

    private fun absolute(axis: AxisId): Double = harness.presenter.frame.value!!.reading(axis).absolute

    @Test
    fun theFlipAcrossXOfTheWorkedExampleWithUndo() = scope.runTest {
        harness.start(mill)
        points(SubDatum(id = "p", name = "P", x = 10_000.0, y = 40_000.0, z = -2_000.0))
        harness.store.machines.saveGuide(workspace().id, Guide(id = "gy", axis = AxisId.Y, valueUm = 30_000.0))
        harness.store.machines.saveGuide(workspace().id, Guide(id = "gx", axis = AxisId.X, valueUm = 5_000.0))
        advanceUntilIdle()
        val flip = transform(WorkspaceTransformKind.Flip)
        runCurrent()
        assertEquals(listOf(AxisId.Y), flip.uiState.value.axes)
        flip.onSource(PivotSource.Custom)
        flip.onText(AxisId.Y, "25")
        flip.onApply()
        runCurrent()
        assertTrue(flip.uiState.value.done)
        val after = workspace()
        val point = after.subDatums.single()
        assertEquals(10_000.0, point.x!!, 1e-9)
        assertEquals(10_000.0, point.y!!, 1e-9)
        assertEquals(-2_000.0, point.z!!, 1e-9)
        assertEquals(20_000.0, after.guides.first { it.id == "gy" }.valueUm, 1e-9)
        assertEquals(5_000.0, after.guides.first { it.id == "gx" }.valueUm, 1e-9)
        val offer = assertNotNull(harness.tab.undo.pending.value)
        assertEquals("Workspace flipped", offer.label.loadString(TestEnvironment))
        assertTrue(harness.tab.undo.undo(offer.id))
        runCurrent()
        assertEquals(40_000.0, workspace().subDatums.single().y!!, 1e-9)
        assertEquals(30_000.0, workspace().guides.first { it.id == "gy" }.valueUm, 1e-9)
        flip.close()
    }

    @Test
    fun theUndoStaysTenSecondsAndTheMenuUndoesTheLastTransform() = scope.runTest {
        harness.start(mill)
        points(SubDatum(name = "P", x = 10_000.0, y = 40_000.0))
        advanceUntilIdle()
        val flip = transform(WorkspaceTransformKind.Flip)
        runCurrent()
        flip.onFlipAxis(FlipAxis.AcrossY)
        flip.onSource(PivotSource.Origin)
        flip.onApply()
        runCurrent()
        assertEquals(-10_000.0, workspace().subDatums.single().x!!, 1e-9)
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(harness.tab.undo.pending.value)
        // The store keeps the copy: "Undo last transform" of the workspaces sheet.
        val sheet = WorkspacesPresenter(harness.profile.id, harness.services, harness.presenter, messages::add)
        runCurrent()
        assertEquals("Flip", sheet.uiState.value.undoLabel)
        sheet.onUndoLastTransform()
        runCurrent()
        assertEquals(10_000.0, workspace().subDatums.single().x!!, 1e-9)
        assertEquals("The last transform is undone.", messages.last().loadString(TestEnvironment))
        runCurrent()
        assertNull(sheet.uiState.value.undoLabel)
        sheet.close()
        flip.close()
    }

    @Test
    fun theRotateOfTheWorkedExampleAndTheGuideQuestion() = scope.runTest {
        harness.start(mill)
        points(SubDatum(name = "P", x = 20_000.0, y = 0.0, z = 3_000.0))
        harness.store.machines.saveGuide(workspace().id, Guide(axis = AxisId.X, valueUm = 30_000.0))
        advanceUntilIdle()
        val rotate = transform(WorkspaceTransformKind.Rotate)
        runCurrent()
        rotate.onAngle("30")
        rotate.onSource(PivotSource.Custom)
        rotate.onText(AxisId.X, "10")
        rotate.onText(AxisId.Y, "0")
        rotate.onApply()
        runCurrent()
        assertEquals(1, rotate.uiState.value.guideQuestion)
        rotate.onGuideAnswer(GuidePolicy.Convert)
        runCurrent()
        val after = workspace()
        val point = after.subDatums.first { it.name == "P" }
        assertEquals(18_660.254, point.x!!, 1e-3)
        assertEquals(5_000.0, point.y!!, 1e-6)
        assertEquals(3_000.0, point.z!!, 1e-9)
        assertEquals(emptyList(), after.guides)
        val converted = after.subDatums.first { it.name == "Guide" }
        assertEquals(27_320.508, converted.x!!, 1e-3)
        assertEquals(10_000.0, converted.y!!, 1e-6)
        rotate.close()
    }

    @Test
    fun theScaleOfTheWorkedExampleAndAScaleWithoutZ() = scope.runTest {
        harness.start(mill)
        points(SubDatum(id = "p", name = "P", x = 120_000.0, y = 50_000.0, z = -10_000.0), SubDatum(id = "s", name = "S2D", x = 100_000.0, y = 50_000.0))
        advanceUntilIdle()
        val scale = transform(WorkspaceTransformKind.Scale)
        runCurrent()
        assertTrue(scale.uiState.value.scaleZ)
        scale.onPercent("150")
        scale.onSource(PivotSource.Custom)
        scale.onText(AxisId.X, "100")
        scale.onText(AxisId.Y, "50")
        scale.onText(AxisId.Z, "0")
        scale.onApply()
        runCurrent()
        val point = workspace().subDatums.first { it.id == "p" }
        assertEquals(130_000.0, point.x!!, 1e-6)
        assertEquals(50_000.0, point.y!!, 1e-6)
        assertEquals(-15_000.0, point.z!!, 1e-6)
        scale.close()
        // A 2D sub-datum is a pivot of a scale without Z.
        val second = transform(WorkspaceTransformKind.Scale)
        runCurrent()
        second.onScaleZ(false)
        second.onPercent("200")
        second.onSource(PivotSource.SubDatum)
        second.onSubDatum("s")
        second.onApply()
        runCurrent()
        assertTrue(second.uiState.value.done, second.uiState.value.error.toString())
        val again = workspace().subDatums.first { it.id == "p" }
        assertEquals(160_000.0, again.x!!, 1e-6)
        assertEquals(-15_000.0, again.z!!, 1e-6)
        second.close()
    }

    @Test
    fun theScaleChecksThePercentAndTheSubDatumSource() = scope.runTest {
        harness.start(mill)
        points(SubDatum(id = "s", name = "S2D", x = 1_000.0, y = 2_000.0))
        advanceUntilIdle()
        val scale = transform(WorkspaceTransformKind.Scale)
        runCurrent()
        scale.onPercent("0")
        scale.onSource(PivotSource.Origin)
        scale.onApply()
        assertEquals("Enter a scale of more than 0 percent.", scale.uiState.value.valueError!!.loadString(TestEnvironment))
        scale.onPercent("150")
        scale.onSource(PivotSource.SubDatum)
        scale.onApply()
        assertEquals("Select a sub-datum, or select a different source.", scale.uiState.value.error!!.loadString(TestEnvironment))
        scale.onSubDatum("s")
        scale.onApply()
        assertEquals("“S2D” has no Z position.", scale.uiState.value.error!!.loadString(TestEnvironment))
        scale.close()
    }

    @Test
    fun alignAbsoluteOriginToSubDatumRenumbersTheWorkspace() = scope.runTest {
        harness.start(mill)
        harness.inputs(AxisId.X to 60_000.0, AxisId.Y to 70_000.0, AxisId.Z to 10_000.0)
        points(SubDatum(id = "s", name = "S", x = 25_000.0, y = 40_000.0, z = -5_000.0), SubDatum(id = "p", name = "P", x = 30_000.0, y = 50_000.0, z = 0.0))
        advanceUntilIdle()
        val command = SubDatumCommandPresenter("s", SubDatumCommand.AlignOriginToSubDatum, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertNull(command.uiState.value.error)
        command.onConfirm()
        runCurrent()
        assertTrue(command.uiState.value.done)
        val after = workspace()
        val p = after.subDatums.first { it.id == "p" }
        assertEquals(5_000.0, p.x!!, 1e-9)
        assertEquals(10_000.0, p.y!!, 1e-9)
        assertEquals(5_000.0, p.z!!, 1e-9)
        val s = after.subDatums.first { it.id == "s" }
        assertEquals(0.0, s.x!!, 1e-9)
        assertEquals(0.0, s.y!!, 1e-9)
        assertEquals(0.0, s.z!!, 1e-9)
        assertEquals(35_000.0, absolute(AxisId.X), 1e-9)
        assertEquals(30_000.0, absolute(AxisId.Y), 1e-9)
        assertEquals(15_000.0, absolute(AxisId.Z), 1e-9)
        // The undo restores the rows and the absolute origin.
        val offer = assertNotNull(harness.tab.undo.pending.value)
        harness.tab.undo.undo(offer.id)
        runCurrent()
        assertEquals(30_000.0, workspace().subDatums.first { it.id == "p" }.x!!, 1e-9)
        assertEquals(60_000.0, absolute(AxisId.X), 1e-9)
        command.close()
    }

    @Test
    fun alignSubDatumToSpindleShowsTheShiftsAndMovesTheOrigin() = scope.runTest {
        harness.start(mill)
        harness.inputs(AxisId.X to 60_000.0, AxisId.Y to 70_000.0, AxisId.Z to 0.0)
        points(SubDatum(id = "s", name = "S", x = 25_000.0, y = 40_000.0))
        advanceUntilIdle()
        val command = SubDatumCommandPresenter("s", SubDatumCommand.AlignToSpindle, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertEquals(listOf("X -35.000 mm", "Y -30.000 mm"), command.uiState.value.shifts.map { it.loadString(TestEnvironment) })
        command.onConfirm()
        runCurrent()
        assertEquals(25_000.0, absolute(AxisId.X), 1e-9)
        assertEquals(40_000.0, absolute(AxisId.Y), 1e-9)
        assertEquals(25_000.0, workspace().subDatums.single().x!!, 1e-9)
        assertNotNull(harness.presenter.uiState.value.undo)
        command.close()
    }

    @Test
    fun alignSubDatumToSpindleNeedsAConnectedSessionAndHasANoOp() = scope.runTest {
        harness.start(mill, state = org.superdro.core.connection.SessionState.Disconnected)
        points(SubDatum(id = "s", name = "S", x = 0.0, y = 0.0))
        advanceUntilIdle()
        val refused = SubDatumCommandPresenter("s", SubDatumCommand.AlignToSpindle, harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertNotNull(refused.uiState.value.error)
        refused.close()
    }

    @Test
    fun alignAToBMovesTheOriginAndDeletesBWithUndo() = scope.runTest {
        harness.start(mill)
        harness.inputs(AxisId.X to 50_000.0, AxisId.Y to 30_000.0)
        points(SubDatum(id = "a", name = "A", x = 10_000.0, y = 20_000.0), SubDatum(id = "b", name = "B", x = 15_000.0, y = 20_000.0))
        advanceUntilIdle()
        val pair = PairPresenter("a", "b", harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertEquals("5.000 mm", pair.uiState.value.distance!!.loadString(TestEnvironment))
        assertEquals("0°", pair.uiState.value.angleDegrees)
        assertTrue(pair.uiState.value.tooClose)
        pair.onMove()
        val move = pair.uiState.value.question as PairQuestion.Move
        assertEquals(listOf("X -5.000 mm", "Y +0.000 mm"), move.shifts.map { it.loadString(TestEnvironment) })
        pair.onMoveConfirm()
        runCurrent()
        assertEquals(45_000.0, absolute(AxisId.X), 1e-9)
        assertEquals(30_000.0, absolute(AxisId.Y), 1e-9)
        assertEquals(PairQuestion.DeleteB, pair.uiState.value.question)
        pair.onDeleteB(true)
        runCurrent()
        assertTrue(pair.uiState.value.done)
        assertEquals(listOf("A"), workspace().subDatums.map { it.name })
        val offer = assertNotNull(harness.tab.undo.pending.value)
        harness.tab.undo.undo(offer.id)
        runCurrent()
        assertEquals(listOf("A", "B"), workspace().subDatums.map { it.name }.sorted())
        pair.close()
    }

    @Test
    fun alignBToSpindleSnapsTo90Degrees() = scope.runTest {
        harness.start(mill)
        harness.inputs(AxisId.X to 0.5, AxisId.Y to 50_000.0)
        points(SubDatum(id = "a", name = "A", x = 0.0, y = 0.0), SubDatum(id = "b", name = "B", x = 50_000.0, y = 0.0))
        advanceUntilIdle()
        val pair = PairPresenter("a", "b", harness.services, harness.presenter, harness.tab.undo, messages::add)
        runCurrent()
        assertTrue(pair.uiState.value.spindleVisible)
        assertFalse(pair.uiState.value.tooClose)
        pair.onSpindle()
        val rotate = pair.uiState.value.question as PairQuestion.Rotate
        assertEquals("90°", rotate.thetaDegrees)
        assertNull(rotate.uncertainDegrees)
        pair.onRotateConfirm()
        runCurrent()
        assertTrue(pair.uiState.value.done)
        val b = workspace().subDatums.first { it.id == "b" }
        assertEquals(0.0, b.x!!, 1e-6)
        assertEquals(50_000.0, b.y!!, 1e-6)
        pair.close()
    }

    @Test
    fun anArmedThreadBlocksTheWorkspaceCommands() = scope.runTest {
        harness.start(mill)
        points(SubDatum(id = "s", name = "S", x = 1_000.0, y = 2_000.0))
        harness.store.machines.updateWorkspace(workspace().id) { it.copy(armedThreadId = "thread-1") }
        advanceUntilIdle()
        val flip = transform(WorkspaceTransformKind.Flip)
        runCurrent()
        flip.onSource(PivotSource.Origin)
        flip.onApply()
        runCurrent()
        assertEquals(
            "The workspace commands are not available while thread cutting is active.",
            flip.uiState.value.error!!.loadString(TestEnvironment),
        )
        assertEquals(2_000.0, workspace().subDatums.single().y!!, 1e-9)
        val commands = WorkspaceCommandsPresenter(harness.services, harness.presenter, messages::add)
        runCurrent()
        assertNotNull(commands.uiState.value.blocked)
        assertTrue(commands.uiState.value.transforms)
        commands.close()
        flip.close()
    }

    @Test
    fun theWorkspaceCommandsHaveNoTransformsOnALathe() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe"))
        val commands = WorkspaceCommandsPresenter(harness.services, harness.presenter, messages::add)
        runCurrent()
        assertTrue(commands.uiState.value.loaded)
        assertFalse(commands.uiState.value.transforms)
        commands.close()
    }
}
