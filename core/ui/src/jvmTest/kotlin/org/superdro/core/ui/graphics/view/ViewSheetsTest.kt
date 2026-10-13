package org.superdro.core.ui.graphics.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.PointUm
import org.superdro.core.functions.graphics.GuideOrientation
import org.superdro.core.model.AngularAxisConfig
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.PartRotation
import org.superdro.core.resources.Res
import org.superdro.core.resources.graphics_add_guide_duplicate
import org.superdro.core.resources.graphics_add_guide_invalid
import org.superdro.core.resources.graphics_rotate_angle_limit
import org.superdro.core.resources.graphics_rotate_no_angular
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText

/** The tests of the "Add guide" dialog and of the rotate part sheet. */
class ViewSheetsTest {

    private suspend fun TestScope.tabOf(harness: GraphicsHarness): GraphicalTabPresenter {
        val tab = GraphicalTabPresenter(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        return tab
    }

    @Test
    fun theAddGuideDialogStartsAtTheTouchPositionAndAddsTheGuide() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start(machine = ViewSamples.mill)
            harness.moveTo(10L, x = 2_000L, y = 3_000L, z = 0L)
            val tab = tabOf(harness)
            tab.requestAddGuide(PointUm(12_345.0, -6_000.0))
            val dialog = AddGuidePresenter(tab)
            assertEquals(GuideOrientation.Vertical, dialog.uiState.value.orientation)
            assertEquals(AxisId.X, dialog.uiState.value.axis)
            assertEquals("12.345", dialog.uiState.value.text)
            dialog.onOrientation(GuideOrientation.Horizontal)
            assertEquals(AxisId.Y, dialog.uiState.value.axis)
            assertEquals("-6.000", dialog.uiState.value.text)
            // `++x` uses the absolute position: 3.000 + 1.5 = 4.5 mm.
            dialog.onText("++1.5")
            assertTrue(dialog.onAdd())
            advanceUntilIdle()
            assertEquals(listOf(AxisId.Y to 4_500.0), harness.workspace().guides.map { guide -> guide.axis to guide.valueUm })
            assertEquals(null, tab.addGuide.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun theAddGuideDialogRefusesABadValueAndADuplicate() = runTest {
        val harness = GraphicsHarness(this)
        try {
            harness.start(machine = ViewSamples.mill)
            harness.moveTo(10L, x = 2_000L, y = 3_000L, z = 0L)
            val tab = tabOf(harness)
            tab.requestAddGuide(PointUm(0.0, 0.0))
            val dialog = AddGuidePresenter(tab)
            dialog.onText("2 +")
            assertFalse(dialog.onAdd())
            assertEquals(UiText.of(Res.string.graphics_add_guide_invalid), dialog.uiState.value.error)
            dialog.onSync()
            assertEquals("2.000", dialog.uiState.value.text)
            assertTrue(dialog.onAdd())
            advanceUntilIdle()
            val again = AddGuidePresenter(tab)
            again.onText("2")
            assertFalse(again.onAdd())
            assertEquals(UiText.of(Res.string.graphics_add_guide_duplicate), again.uiState.value.error)
        } finally {
            harness.close()
        }
    }

    @Test
    fun rotatePartStoresTheTypedAngleAndThePivotAndClearsIt() {
        val readout = FakeTabReadout(ViewSamples.frame(ViewSamples.mill, ViewSamples.millWorkspace(), x = 4_000.0, y = -2_000.0))
        val sheet = RotatePartPresenter(readout)
        sheet.onAngle("30")
        assertTrue(sheet.onRotate())
        val rotation = readout.frame.value!!.workspace.partRotation
        assertEquals(PartRotation(active = true, angleArcSec = 108_000.0, pivotXUm = 4_000.0, pivotYUm = -2_000.0), rotation)
        // The readouts do not change.
        assertEquals(4_000.0, readout.frame.value!!.reading(AxisId.X).absolute)
        val active = RotatePartPresenter(readout)
        assertTrue(active.uiState.value.active)
        active.onClear()
        assertEquals(PartRotation(), readout.frame.value!!.workspace.partRotation)
    }

    @Test
    fun rotatePartRefusesAnAngleOutOfTheLimit() {
        val readout = FakeTabReadout(ViewSamples.frame(ViewSamples.mill))
        val sheet = RotatePartPresenter(readout)
        sheet.onAngle("400")
        assertFalse(sheet.onRotate())
        assertEquals(UiText.of(Res.string.graphics_rotate_angle_limit), sheet.uiState.value.error)
        sheet.onSource(RotationSource.AngularEncoder)
        sheet.onOffset("10")
        assertFalse(sheet.onRotate())
        assertEquals(UiText.of(Res.string.graphics_rotate_no_angular), sheet.uiState.value.error)
    }

    @Test
    fun aRotationLinkedToTheAngularEncoderLocksTheAngularAxisToAbsolute() {
        val profile = ViewSamples.mill.copy(angular = AngularAxisConfig(enabled = true))
        val workspace = profile.workspaces.first().copy(
            axisStates = profile.workspaces.first().axisStates + (AxisId.Angular to AxisState(relative = true, incrementalOffset = 3_600.0)),
        )
        val readout = FakeTabReadout(ViewSamples.frame(profile, workspace, angular = 36_000.0))
        assertTrue(readout.frame.value!!.reading(AxisId.Angular).incremental)
        val sheet = RotatePartPresenter(readout)
        assertTrue(sheet.uiState.value.encoderAvailable)
        sheet.onSource(RotationSource.AngularEncoder)
        sheet.onOffset("5")
        assertTrue(sheet.onRotate())
        val frame = assertNotNull(readout.frame.value)
        assertTrue(frame.workspace.partRotation.linkedToAngularEncoder)
        assertEquals(18_000.0, frame.workspace.partRotation.encoderOffsetArcSec)
        // The angular axis reads absolute while the rotation uses it.
        assertTrue(frame.angularLocked)
        assertFalse(frame.reading(AxisId.Angular).incremental)
        // The view turns by φ = −(A − offset) = −(10° − 5°).
        val scene = GraphicalSceneBuilder.build(frame)
        assertEquals(-5.0 * 3600.0, scene.displayFrame.partRotation!!.phiArcSec, 1e-6)
    }
}
