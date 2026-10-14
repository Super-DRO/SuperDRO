package org.superdro.core.ui.functions.patterns

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.superdro.core.functions.patterns.AxisLetter
import org.superdro.core.functions.patterns.PositionSource
import org.superdro.core.model.AxisId

/**
 * The pattern presenter and the live position: a frame that changes only the position does not
 * make the points again, unless the origin is the current position.
 */
class PatternLivePositionTest {

    @BeforeTest
    fun useEnglish() {
        Locale.setDefault(Locale.ENGLISH)
    }

    /** A grid of 3 × 2 points with the steps 10 mm and 20 mm, and Z off. */
    private fun TestScope.grid(presenter: PatternPresenter, source: PositionSource) {
        presenter.onSourceChange(source)
        presenter.onAxisSwitch(AxisLetter.Z, false)
        type(presenter, PatternInput.Repeat1, "3")
        type(presenter, PatternInput.Step1, "10")
        type(presenter, PatternInput.Repeat2, "2")
        type(presenter, PatternInput.Step2, "20")
    }

    @Test
    fun aMoveDoesNotMakeTheStateAgainWithTheOriginZero() = withPattern(PatternMachines.standardMill(), PatternKind.HoleGrid) { presenter, harness ->
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 2_000.0, AxisId.Z to 0.0)
        grid(presenter, PositionSource.Zero)
        val before = presenter.uiState.value
        assertEquals(6, before.pointCount)
        harness.inputs(AxisId.X to 5_000.0, AxisId.Y to 7_000.0, AxisId.Z to -300.0)
        // The same object: the presenter did not make the points, the names, or the rows again.
        assertSame(before, presenter.uiState.value)
    }

    @Test
    fun aChangeOfTheWorkspaceGivesTheNewNames() = withPattern(PatternMachines.standardMill(), PatternKind.HoleGrid) { presenter, harness ->
        grid(presenter, PositionSource.Zero)
        assertEquals(listOf("Grid - 1", "Grid - 2"), presenter.uiState.value.pointRows.take(2).map { row -> row.name })
        val workspaceId = harness.readout.workspace().id
        harness.store.machines.updateWorkspace(workspaceId) { workspace -> workspace.copy(lastOrdinal = 7) }
        advanceUntilIdle()
        assertEquals(listOf("Grid - 8", "Grid - 9"), presenter.uiState.value.pointRows.take(2).map { row -> row.name })
    }

    @Test
    fun aChangeOfTheProfileGivesTheNewTexts() = withPattern(PatternMachines.standardMill(), PatternKind.HoleGrid) { presenter, harness ->
        grid(presenter, PositionSource.Zero)
        assertEquals("10.000", presenter.uiState.value.pointRows[1].x)
        harness.store.machines.updateProfile(harness.profile.id) { profile ->
            profile.copy(x = profile.x.copy(label = "U"))
        }
        advanceUntilIdle()
        assertEquals("U", presenter.uiState.value.positionRows.first().label)
    }

    @Test
    fun theCurrentOriginFollowsTheMoveAndKeepsTheNames() = withPattern(PatternMachines.standardMill(), PatternKind.HoleGrid) { presenter, harness ->
        harness.inputs(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        grid(presenter, PositionSource.Current)
        assertEquals("0.000", presenter.uiState.value.pointRows.first().x)
        harness.inputs(AxisId.X to 5_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        val moved = presenter.uiState.value
        assertEquals("5.000", moved.pointRows.first().x)
        assertEquals("15.000", moved.pointRows[1].x)
        assertEquals(5_000.0, moved.previewOrigin!!.h)
        assertEquals(5_000.0, moved.positionRows.first { row -> row.axis == AxisLetter.X }.field.valueUm)
        assertEquals("Grid - 1", moved.pointRows.first().name)
        // A frame with the same position gives the same state object.
        harness.inputs(AxisId.X to 5_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        assertSame(moved, presenter.uiState.value)
    }

    @Test
    fun theCurrentPositionRowsChangeWithAnAxisThatIsOff() = withPattern(PatternMachines.standardMill(), PatternKind.HoleGrid) { presenter, harness ->
        harness.inputs(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        grid(presenter, PositionSource.Current)
        val before = presenter.uiState.value
        // Z is off: the points stay, the Z position row shows the new Z.
        harness.inputs(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to -4_000.0)
        val after = presenter.uiState.value
        assertNotSame(before, after)
        assertSame(before.pointRows, after.pointRows)
        assertSame(before.preview, after.preview)
        assertEquals(-4_000.0, after.positionRows.first { row -> row.axis == AxisLetter.Z }.field.valueUm)
    }
}
