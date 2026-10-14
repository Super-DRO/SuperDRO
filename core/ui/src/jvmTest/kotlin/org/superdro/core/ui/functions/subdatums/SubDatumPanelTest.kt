package org.superdro.core.ui.functions.subdatums

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_blocked_message
import org.superdro.core.resources.fn_blocker_thread
import org.superdro.core.resources.sd_deleted
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.subscribe
import org.superdro.core.ui.functions.TestEnvironment
import org.superdro.core.ui.functions.common.loadString
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.Theme
import org.superdro.core.model.PartRotation

/** The presenter of the sub-datum panel: the "+" rule, the activation, the navigation, the filter, the delete, and the export. */
class SubDatumPanelTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    /** A new panel presenter. The test collects its state, as the open panel does. */
    private fun panel(): SubDatumPanelPresenter =
        SubDatumPanelPresenter(harness.profile.id, harness.services, harness.presenter, harness.tab.undo)
            .also { panel -> scope.subscribe(panel.uiState) }

    private suspend fun workspaceId(): String = harness.readout.workspace().id

    private suspend fun points(vararg points: SubDatum) {
        harness.store.machines.insertSubDatums(workspaceId(), points.toList())
    }

    private fun mill(use3d: Boolean = true, angular: Boolean = false, activate: Boolean = true): MachineProfile {
        val base = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(use3dSubDatums = use3d)
        return base.copy(
            angular = base.angular.copy(enabled = angular),
            display = base.display.copy(activateNewSubDatum = activate),
        )
    }

    @Test
    fun plusAddsTheCurrentPositionOfAMillWith3dAndTheAngularAxis() = scope.runTest {
        harness.start(mill(use3d = true, angular = true))
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 2_000.0, AxisId.Z to -500.0, AxisId.Angular to 3_600.0)
        val panel = panel()
        advanceUntilIdle()
        val first = assertIs<AddPointOutcome.Added>(panel.addCurrentPoint()).subDatum
        assertEquals("Point 1", first.name)
        assertEquals(listOf(1_000.0, 2_000.0, -500.0, 3_600.0), listOf(first.x, first.y, first.z, first.a))
        advanceUntilIdle()
        // "Activate New Subdatum" is on.
        assertEquals(first.id, harness.readout.workspace().activeSubDatumId)
        val second = assertIs<AddPointOutcome.Added>(panel.addCurrentPoint()).subDatum
        assertEquals("Point 2", second.name)
        assertEquals(2, harness.readout.workspace().lastOrdinal)
        panel.close()
    }

    @Test
    fun plusWithAPartRotationStoresThePointThatShowsAtTheTool() = scope.runTest {
        val base = mill(use3d = false)
        val rotated = base.copy(
            workspaces = base.workspaces.map { workspace ->
                workspace.copy(partRotation = PartRotation(active = true, angleArcSec = 90.0 * 3600.0))
            },
        )
        harness.start(rotated)
        harness.inputs(AxisId.X to 100_000.0, AxisId.Y to 0.0, AxisId.Z to 0.0)
        val panel = panel()
        advanceUntilIdle()
        val point = assertIs<AddPointOutcome.Added>(panel.addCurrentPoint()).subDatum
        // α = 90° (φ = −90°) about (0, 0): the inverse display transform stores (0, 100) mm.
        assertEquals(0.0, point.x!!, 1e-6)
        assertEquals(100_000.0, point.y!!, 1e-6)
        advanceUntilIdle()
        // The active new point shows at the tool, so the incremental X and Y read 0.
        val frame = harness.presenter.frame.value!!
        assertEquals(point.id, frame.workspace.activeSubDatumId)
        assertTrue(frame.reading(AxisId.X).incremental)
        assertEquals(0.0, frame.reading(AxisId.X).shown, 1e-6)
        assertEquals(0.0, frame.reading(AxisId.Y).shown, 1e-6)
        panel.close()
    }

    @Test
    fun plusLeavesOutZWith3dOffAndDoesNotActivateWhenTheSettingIsOff() = scope.runTest {
        harness.start(mill(use3d = false, activate = false))
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 2_000.0, AxisId.Z to -500.0)
        val panel = panel()
        advanceUntilIdle()
        val point = assertIs<AddPointOutcome.Added>(panel.addCurrentPoint()).subDatum
        assertEquals(1_000.0, point.x)
        assertEquals(2_000.0, point.y)
        assertNull(point.z)
        assertNull(point.a)
        advanceUntilIdle()
        assertNull(harness.readout.workspace().activeSubDatumId)
        panel.close()
    }

    @Test
    fun plusOnALatheGivesXAndZAndNoY() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(use3dSubDatums = false))
        harness.inputs(AxisId.X to 6_000.0, AxisId.Y to 2_000.0, AxisId.Z to -30_000.0)
        val panel = panel()
        advanceUntilIdle()
        val point = assertIs<AddPointOutcome.Added>(panel.addCurrentPoint()).subDatum
        // The stored lathe X is a radius: the diameter display does not change it.
        assertEquals(6_000.0, point.x)
        assertNull(point.y)
        assertEquals(-30_000.0, point.z)
        panel.close()
    }

    @Test
    fun plusShowsTheBlockerAndTheLiveCheck() = scope.runTest {
        harness.start(mill())
        harness.inputs(AxisId.X to 1_000.0, AxisId.Y to 2_000.0, AxisId.Z to 0.0)
        harness.store.machines.updateWorkspace(workspaceId()) { it.copy(armedThreadId = "thread-1") }
        val panel = panel()
        advanceUntilIdle()
        val blocked = assertIs<AddPointOutcome.Blocked>(panel.addCurrentPoint())
        assertEquals(UiText.of(Res.string.fn_blocked_message, UiText.of(Res.string.fn_blocker_thread)), blocked.message)
        harness.store.machines.updateWorkspace(workspaceId()) { it.copy(armedThreadId = null) }
        harness.readout.session.state.value = SessionState.Disconnected
        advanceUntilIdle()
        val unavailable = assertIs<AddPointOutcome.Unavailable>(panel.addCurrentPoint())
        assertEquals("No adapter connected", unavailable.message.loadString(TestEnvironment))
        assertEquals(emptyList(), harness.readout.workspace().subDatums)
        panel.close()
    }

    @Test
    fun aTapActivatesAPointAndTheReadoutShowsTheDistanceToIt() = scope.runTest {
        harness.start(mill())
        harness.inputs(AxisId.X to 30_000.0)
        points(SubDatum(id = "s1", name = "S1", x = 25_400.0))
        val panel = panel()
        advanceUntilIdle()
        panel.onTap("s1")
        advanceUntilIdle()
        assertEquals("s1", harness.readout.workspace().activeSubDatumId)
        // abs − c = 30000 − 25400 = 4600 µm = 0.1811 in.
        assertEquals("0.1811", harness.readout.text(AxisId.X))
        assertTrue(panel.uiState.value.rows.single().active)
        panel.onTap("s1")
        advanceUntilIdle()
        assertNull(harness.readout.workspace().activeSubDatumId)
        panel.close()
    }

    @Test
    fun theNavigationActivatesThePointsAndSkipsTheGuides() = scope.runTest {
        harness.start(mill())
        points(SubDatum(id = "a", name = "A", x = 1.0), SubDatum(id = "b", name = "B", x = 2.0), SubDatum(id = "c", name = "C", x = 3.0))
        harness.store.machines.saveGuide(workspaceId(), Guide(id = "g", axis = AxisId.X, valueUm = 10.0))
        val panel = panel()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "g"), panel.uiState.value.rows.map { it.id })
        suspend fun active() = harness.readout.workspace().activeSubDatumId
        panel.onNext()
        advanceUntilIdle()
        assertEquals("a", active())
        panel.onLast()
        advanceUntilIdle()
        assertEquals("c", active())
        panel.onNext()
        advanceUntilIdle()
        assertEquals("c", active())
        panel.onPrevious()
        advanceUntilIdle()
        assertEquals("b", active())
        panel.onFirst()
        advanceUntilIdle()
        assertEquals("a", active())
        panel.close()
    }

    @Test
    fun theTagFilterHidesTheTagsAndTheUntaggedPoints() = scope.runTest {
        harness.start(mill())
        points(
            SubDatum(id = "1", name = "One", x = 1.0, tags = listOf("holes", "left")),
            SubDatum(id = "2", name = "Two", x = 2.0, tags = listOf("Grid")),
            SubDatum(id = "3", name = "Three", x = 3.0),
        )
        val panel = panel()
        advanceUntilIdle()
        val state = panel.uiState.value
        // The tags are sorted and keep all tags of a point.
        assertEquals(listOf(null, "Grid", "holes", "left"), state.filterRows.map { it.tag })
        assertEquals(listOf(TagMarkUi("holes", 1), TagMarkUi("left", 2)), state.rows.first().tags)
        panel.onFilterToggle()
        panel.onFilterRow("Grid")
        runCurrent()
        assertEquals(listOf("1", "3"), panel.uiState.value.rows.map { it.id })
        panel.onFilterRow(null)
        runCurrent()
        assertEquals(listOf("1"), panel.uiState.value.rows.map { it.id })
        panel.onFilterRow("holes")
        runCurrent()
        // "One" still shows: its tag "left" is not hidden.
        assertEquals(listOf("1"), panel.uiState.value.rows.map { it.id })
        panel.onFilterLongPress()
        runCurrent()
        assertEquals(4, panel.uiState.value.filterRows.size)
        assertFalse(panel.uiState.value.filterOn)
        panel.onFilterLongPress()
        runCurrent()
        assertEquals(emptyList(), panel.uiState.value.rows)
        panel.onFilterToggle()
        runCurrent()
        assertEquals(3, panel.uiState.value.rows.size)
        panel.close()
    }

    @Test
    fun eachTagColorHasAContrastOfThreeOrMoreOnTheSurface() {
        val surfaces = listOf(
            Theme.lightScheme.surface,
            Theme.lightScheme.surfaceContainerLow,
            Theme.darkScheme.surface,
            Theme.darkScheme.surfaceContainerLow,
            Theme.lightScheme.tertiaryContainer,
            Theme.darkScheme.tertiaryContainer,
        )
        assertEquals(23, TagColors.BASE.size)
        for (surface in surfaces) {
            for (index in 0 until 30) {
                val contrast = TagColors.contrast(TagColors.color(index, surface), surface)
                assertTrue(contrast >= 3.0, "index $index on $surface: $contrast")
            }
        }
        // A color with enough contrast does not change: on the dark surface, the pastel colors stay.
        assertEquals(androidx.compose.ui.graphics.Color(0xFFB2FFFF.toInt()), TagColors.color(0, Theme.darkScheme.surface))
    }

    @Test
    fun theDeleteOfOneTheSelectedAndAllHasUndoForTenSeconds() = scope.runTest {
        harness.start(mill())
        points(SubDatum(id = "a", name = "A", x = 1.0), SubDatum(id = "b", name = "B", x = 2.0), SubDatum(id = "c", name = "C", x = 3.0))
        harness.store.machines.saveGuide(workspaceId(), Guide(id = "g", axis = AxisId.Y, valueUm = 5.0))
        val panel = panel()
        advanceUntilIdle()
        panel.onTap("a")
        advanceUntilIdle()
        // One point (the active point): the undo brings it back and activates it again.
        panel.onDelete("a")
        runCurrent()
        assertEquals(listOf("b", "c", "g"), panel.uiState.value.rows.map { it.id })
        val one = assertNotNull(harness.tab.undo.pending.value)
        assertEquals(UiText.of(Res.string.sd_deleted, 1), one.label)
        assertTrue(harness.tab.undo.undo(one.id))
        runCurrent()
        assertEquals(listOf("a", "b", "c", "g"), panel.uiState.value.rows.map { it.id })
        assertEquals("a", harness.readout.workspace().activeSubDatumId)
        // The selected rows.
        panel.onToggleSelected("b")
        panel.onToggleSelected("g")
        runCurrent()
        assertEquals(setOf("b", "g"), panel.uiState.value.selectedIds)
        panel.onDeleteSelected()
        runCurrent()
        assertEquals(listOf("a", "c"), panel.uiState.value.rows.map { it.id })
        assertFalse(panel.uiState.value.selecting)
        // All: the 10 s pass, so the undo goes away.
        panel.onDeleteAll()
        runCurrent()
        assertTrue(panel.uiState.value.empty)
        assertEquals(0, harness.readout.workspace().lastOrdinal)
        assertNotNull(harness.tab.undo.pending.value)
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(harness.tab.undo.pending.value)
        panel.close()
    }

    @Test
    fun setOriginHereMovesTheAbsoluteOriginToThePoint() = scope.runTest {
        harness.start(mill(use3d = true), states = mapOf(AxisId.X to AxisState(metric = true), AxisId.Y to AxisState(metric = true)))
        harness.inputs(AxisId.X to 30_000.0, AxisId.Y to 10_000.0, AxisId.Z to 0.0)
        points(SubDatum(id = "s", name = "S", x = 25_400.0, y = 5_000.0))
        val panel = panel()
        advanceUntilIdle()
        panel.onSetOrigin("s")
        runCurrent()
        assertEquals("4.600", harness.readout.text(AxisId.X))
        assertEquals("5.000", harness.readout.text(AxisId.Y))
        assertNotNull(harness.presenter.uiState.value.undo)
        panel.close()
    }

    @Test
    fun theExportWritesTheSelectionOrAllShownRowsWithNoDevice() = scope.runTest {
        harness.start(mill().copy(metricDefault = true))
        points(SubDatum(id = "a", name = "A", x = 1_000.0, y = 2_000.0, tags = listOf("t1", "t2")), SubDatum(id = "b", name = "B", x = 3_000.0, y = 4_000.0))
        harness.store.machines.saveGuide(workspaceId(), Guide(id = "g", axis = AxisId.X, valueUm = 10_000.0))
        val panel = panel()
        advanceUntilIdle()
        val messages = mutableListOf<UiText>()
        panel.onToggleSelected("b")
        runCurrent()
        panel.onExport(messages::add)
        advanceUntilIdle()
        val selected = harness.filePicker.created.single().third.decodeToString()
        assertTrue(selected.startsWith("# superdro: sub-datums\r\n"), selected)
        assertFalse("device" in selected)
        assertTrue("name,note,tags,x,y,z\r\nB,,,3.000,4.000,\r\n" in selected, selected)
        assertFalse("A," in selected)
        panel.onDeselect()
        runCurrent()
        panel.onExport(messages::add)
        advanceUntilIdle()
        val all = harness.filePicker.created.last().third.decodeToString()
        assertTrue("A,,t1 t2,1.000,2.000,\r\n" in all, all)
        assertTrue("Guide,,,10.000,,\r\n" in all, all)
        assertEquals("superdro-sub_datums-Mill-mill-20261012_080000.csv", harness.filePicker.created.last().first)
        assertEquals(2, messages.size)
        panel.close()
    }

    @Test
    fun aThreadPointShowsItsGroupKind() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe"))
        points(SubDatum(id = "t", name = "Thread 1 - Start", x = 5_000.0, z = 0.0, groupKind = SubDatumGroupKind.Thread, groupRef = "thread:z_start"))
        val panel = panel()
        advanceUntilIdle()
        assertEquals(SubDatumGroupKind.Thread, panel.uiState.value.rows.single().kind)
        panel.close()
    }

    @Test
    fun the3dSwitchChangesTheProfileAndActivatesThePointAgain() = scope.runTest {
        harness.start(mill(use3d = false), states = mapOf(AxisId.Z to AxisState(metric = true)))
        harness.inputs(AxisId.X to 0.0, AxisId.Y to 0.0, AxisId.Z to 1_000.0)
        points(SubDatum(id = "s", name = "S", x = 0.0, y = 0.0, z = 400.0))
        val panel = panel()
        advanceUntilIdle()
        panel.onTap("s")
        advanceUntilIdle()
        assertEquals("1.000", harness.readout.text(AxisId.Z))
        panel.onToggle3d()
        advanceUntilIdle()
        assertTrue(panel.uiState.value.use3d)
        // With 3D on, Z shows the distance to the point: 1000 − 400 µm.
        assertEquals("0.600", harness.readout.text(AxisId.Z))
        panel.close()
    }

    @Test
    fun theMenuOfAPointOffersThePairCommandsWithTheActivePoint() = scope.runTest {
        harness.start(mill())
        points(SubDatum(id = "a", name = "A", x = 1.0, y = 1.0), SubDatum(id = "b", name = "B", x = 2.0, y = 2.0), SubDatum(id = "c", name = "C", x = 3.0))
        val panel = panel()
        advanceUntilIdle()
        panel.onLongPress("b")
        runCurrent()
        assertNull(panel.uiState.value.menu?.pairWithId)
        panel.onTap("a")
        advanceUntilIdle()
        panel.onLongPress("b")
        runCurrent()
        assertEquals("a", panel.uiState.value.menu?.pairWithId)
        // "C" has no Y: no pair commands.
        panel.onLongPress("c")
        runCurrent()
        assertNull(panel.uiState.value.menu?.pairWithId)
        panel.close()
    }
}
