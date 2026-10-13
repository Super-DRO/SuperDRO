package org.superdro.core.ui.functions.subdatums

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.resources.Res
import org.superdro.core.resources.sd_error_name
import org.superdro.core.resources.sd_tag_set
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.text.UiText

/** The sub-datum edit sheet and the bulk tag sheet. */
class SubDatumEditTest {

    private val scope = TestScope()
    private val harness = FunctionsHarness(scope)

    @AfterTest
    fun close() = harness.close()

    private suspend fun workspaceId(): String = harness.readout.workspace().id

    private fun edit(id: String?): SubDatumEditPresenter = SubDatumEditPresenter(id, harness.services, harness.presenter).also { presenter ->
        presenter.load(assertNotNull(harness.presenter.frame.value))
    }

    @Test
    fun theNameHasOneToFifteenCharactersAndAllTagsStay() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        harness.store.machines.insertSubDatums(workspaceId(), listOf(SubDatum(id = "s", name = "Hole 1", x = 1_000.0, y = 2_000.0, tags = listOf("a"))))
        advanceUntilIdle()
        val presenter = edit("s")
        assertEquals("Hole 1", presenter.uiState.value.name)
        assertEquals("1", presenter.uiState.value.x.text)
        presenter.onName("1234567890123456")
        assertFalse(presenter.save())
        assertEquals(UiText.of(Res.string.sd_error_name, 15), presenter.uiState.value.errors[SubDatumField.Name])
        presenter.onName(" ")
        assertFalse(presenter.save())
        presenter.onName("123456789012345")
        presenter.onTags("holes  left Holes   pocket_1")
        presenter.onText(SubDatumField.Y, "2+1/2")
        assertTrue(presenter.save())
        advanceUntilIdle()
        val stored = harness.readout.workspace().subDatums.single()
        assertEquals("123456789012345", stored.name)
        assertEquals(listOf("holes", "left", "pocket_1"), stored.tags)
        assertEquals(2_500.0, stored.y!!, 1e-9)
        presenter.close()
    }

    @Test
    fun aLatheXInTheDiameterModeShowsAndTakesADiameter() = scope.runTest {
        harness.start(
            MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true),
            states = mapOf(AxisId.X to AxisState(diameterMode = true)),
        )
        harness.store.machines.insertSubDatums(workspaceId(), listOf(SubDatum(id = "s", name = "Shoulder", x = 12_500.0, z = -30_000.0)))
        advanceUntilIdle()
        val presenter = edit("s")
        val state = presenter.uiState.value
        assertTrue(state.xDiameter)
        assertFalse(state.showY)
        assertEquals("25", state.x.text)
        presenter.onText(SubDatumField.X, "20")
        assertTrue(presenter.save())
        advanceUntilIdle()
        assertEquals(10_000.0, harness.readout.workspace().subDatums.single().x!!, 1e-9)
        presenter.close()
    }

    @Test
    fun theAngularCoordinateIsStored() = scope.runTest {
        val base = MachineProfile.create(MachineType.VerticalMill, "Mill")
        harness.start(base.copy(angular = base.angular.copy(enabled = true)))
        harness.store.machines.insertSubDatums(workspaceId(), listOf(SubDatum(id = "s", name = "S", x = 0.0)))
        advanceUntilIdle()
        val presenter = edit("s")
        assertTrue(presenter.uiState.value.showA)
        presenter.onText(SubDatumField.A, "45")
        assertTrue(presenter.save())
        advanceUntilIdle()
        assertEquals(162_000.0, harness.readout.workspace().subDatums.single().a!!, 1e-9)
        presenter.close()
    }

    @Test
    fun aNewCustomPointStartsAtTheCurrentPositionAndGetsTheAutomaticName() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        harness.inputs(AxisId.X to 10_000.0, AxisId.Y to 20_000.0, AxisId.Z to -1_000.0)
        val presenter = edit(null)
        val state = presenter.uiState.value
        assertTrue(state.newPoint)
        assertEquals(listOf("10", "20", "-1"), listOf(state.x.text, state.y.text, state.z.text))
        presenter.onText(SubDatumField.X, "++5")
        assertTrue(presenter.save())
        advanceUntilIdle()
        val stored = harness.readout.workspace().subDatums.single()
        assertEquals("Point 1", stored.name)
        assertEquals(5_000.0, stored.x!!, 1e-9)
        assertEquals(stored.id, harness.readout.workspace().activeSubDatumId)
        presenter.close()
    }

    @Test
    fun theCoordinatesOfAThreadPointDoNotChange() = scope.runTest {
        harness.start(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
        harness.store.machines.insertSubDatums(
            workspaceId(),
            listOf(SubDatum(id = "t", name = "Start", x = 5_000.0, z = 0.0, groupKind = SubDatumGroupKind.Thread)),
        )
        advanceUntilIdle()
        val presenter = edit("t")
        assertTrue(presenter.uiState.value.thread)
        presenter.onText(SubDatumField.X, "99")
        presenter.onName("Thread start")
        assertTrue(presenter.save())
        advanceUntilIdle()
        val stored = harness.readout.workspace().subDatums.single()
        assertEquals("Thread start", stored.name)
        assertEquals(5_000.0, stored.x)
        presenter.close()
    }

    @Test
    fun theBulkTagSetsTheTagOfTheSelectedPointsWithUndo() = scope.runTest {
        harness.start()
        harness.store.machines.insertSubDatums(
            workspaceId(),
            listOf(
                SubDatum(id = "a", name = "A", x = 1.0, tags = listOf("old")),
                SubDatum(id = "b", name = "B", x = 2.0),
                SubDatum(id = "c", name = "C", x = 3.0, tags = listOf("keep")),
            ),
        )
        advanceUntilIdle()
        val points = harness.readout.workspace().subDatums.filter { it.id in setOf("a", "b") }
        val presenter = TagSheetPresenter(points, listOf("old", "keep", "Old"), harness.services, harness.tab.undo)
        assertEquals(listOf("keep", "old"), presenter.uiState.value.suggestions)
        assertEquals(2, presenter.uiState.value.count)
        presenter.onText("Holes left")
        assertTrue(presenter.apply())
        runCurrent()
        val tagged = harness.readout.workspace().subDatums.associate { it.id to it.tags }
        assertEquals(listOf("Holes", "left"), tagged["a"])
        assertEquals(listOf("Holes", "left"), tagged["b"])
        assertEquals(listOf("keep"), tagged["c"])
        val offer = assertNotNull(harness.tab.undo.pending.value)
        assertEquals(UiText.of(Res.string.sd_tag_set, "Holes left", 2), offer.label)
        assertTrue(harness.tab.undo.undo(offer.id))
        runCurrent()
        val restored = harness.readout.workspace().subDatums.associate { it.id to it.tags }
        assertEquals(listOf("old"), restored["a"])
        assertEquals(emptyList(), restored["b"])
        presenter.close()
    }

    @Test
    fun theTagFieldIsNormalized() {
        assertEquals(listOf("a", "B_2", "c"), SubDatumNames.parseTags("  a  B_2 A c "))
        assertEquals(emptyList(), SubDatumNames.parseTags("   "))
        assertTrue(SubDatumNames.valid("x"))
        assertFalse(SubDatumNames.valid("x".repeat(16)))
    }
}
