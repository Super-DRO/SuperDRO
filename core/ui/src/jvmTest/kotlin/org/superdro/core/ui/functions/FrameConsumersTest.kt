package org.superdro.core.ui.functions

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.ui.functions.patterns.PatternInput
import org.superdro.core.ui.functions.patterns.PatternKind
import org.superdro.core.ui.functions.patterns.PatternMachines
import org.superdro.core.ui.functions.patterns.PatternPresenter
import org.superdro.core.ui.functions.subdatums.SubDatumPanelPresenter
import org.superdro.core.ui.functions.thread.ThreadField
import org.superdro.core.ui.functions.thread.ThreadSetupPresenter

/**
 * The presenters that read the readout frame: a frame that changes only the live position does not
 * make their state again. A change of the workspace does. The sub-datums of the workspace are a
 * [CountingList], so each new state shows as a read of the list.
 */
class FrameConsumersTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun close() = harness.close()

    private fun TestScope.moves(readout: ScriptedReadout) {
        repeat(5) { index ->
            readout.move(AxisId.X to index * 100.0, AxisId.Y to index * 50.0, AxisId.Z to -index * 10.0)
            advanceUntilIdle()
        }
    }

    @Test
    fun thePatternMakesItsPointsOnlyForANewStructureOrOrigin() = scope.runTest {
        harness.start(PatternMachines.standardMill())
        val (workspace, points) = ScriptedReadout.counted(harness.readout.workspace(), listOf(SubDatum(name = "A", x = 0.0, y = 0.0)))
        val readout = ScriptedReadout(harness.profile, workspace)
        val presenter = PatternPresenter(PatternKind.HoleGrid, readout, harness.services)
        presenter.onSourceChange(PositionSource.Zero)
        presenter.onAxisSwitch(AxisLetter.Z, false)
        presenter.onText(PatternInput.Repeat1, "3")
        presenter.onText(PatternInput.Step1, "10")
        presenter.onText(PatternInput.Repeat2, "2")
        presenter.onText(PatternInput.Step2, "20")
        advanceUntilIdle()
        assertEquals(6, presenter.uiState.value.pointCount)
        assertEquals("Grid - 2", presenter.uiState.value.pointRows.first().name)
        var reads = points.reads
        moves(readout)
        assertEquals(reads, points.reads)

        // The origin "Current" follows the position, but the workspace is not read again.
        presenter.onSourceChange(PositionSource.Current)
        advanceUntilIdle()
        reads = points.reads
        readout.move(AxisId.X to 5_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        advanceUntilIdle()
        assertEquals("5.000", presenter.uiState.value.pointRows.first().x)
        assertEquals("Grid - 2", presenter.uiState.value.pointRows.first().name)
        assertEquals(reads, points.reads)

        // A new workspace gives the new names.
        readout.change(workspace.copy(lastOrdinal = 7))
        advanceUntilIdle()
        assertEquals("Grid - 8", presenter.uiState.value.pointRows.first().name)
        presenter.close()
    }

    @Test
    fun theSubDatumPanelMakesItsRowsOnlyForANewWorkspace() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill"))
        val first = SubDatum(name = "A", x = 1_000.0, y = 2_000.0)
        val (workspace, points) = ScriptedReadout.counted(harness.readout.workspace(), listOf(first))
        val readout = ScriptedReadout(harness.profile, workspace)
        val panel = SubDatumPanelPresenter(harness.profile.id, harness.services, readout, harness.tab.undo)
        advanceUntilIdle()
        // No collector: the panel is closed and makes no state.
        assertEquals(0, points.reads)
        subscribe(panel.uiState)
        advanceUntilIdle()
        assertEquals(listOf("A"), panel.uiState.value.rows.map { row -> row.name })
        val reads = points.reads
        moves(readout)
        assertEquals(reads, points.reads)
        readout.change(workspace.copy(subDatums = listOf(first, SubDatum(name = "B", x = 3_000.0, y = 4_000.0))))
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), panel.uiState.value.rows.map { row -> row.name })
        panel.close()
    }

    @Test
    fun theThreadSetupReadsTheLiveZOnlyForAnEvent() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
        val (workspace, points) = ScriptedReadout.counted(harness.readout.workspace(), listOf(SubDatum(name = "A", z = 0.0, tags = listOf("Thread 3"))))
        val readout = ScriptedReadout(harness.profile, workspace)
        val setup = ThreadSetupPresenter(readout, harness.services)
        advanceUntilIdle()
        assertEquals("Thread 4", setup.uiState.value.defaultTag)
        val reads = points.reads
        moves(readout)
        assertEquals(reads, points.reads)
        // "++1" uses the live Z of the readout as the base.
        readout.move(AxisId.X to 0.0, AxisId.Z to 2_000.0)
        advanceUntilIdle()
        setup.onText(ThreadField.StartZ, "++1")
        assertEquals(3_000.0, setup.uiState.value.startZ.valueUm!!, 1e-9)
        readout.change(workspace.copy(subDatums = listOf(SubDatum(name = "B", z = 0.0, tags = listOf("Thread 7")))))
        advanceUntilIdle()
        assertEquals("Thread 8", setup.uiState.value.defaultTag)
        setup.close()
    }
}
