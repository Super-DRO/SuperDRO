package org.superdro.core.ui.graphics.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.SubDatum
import org.superdro.core.resources.Res
import org.superdro.core.resources.refimage_enter_values
import org.superdro.core.resources.refimage_fit_cannot
import org.superdro.core.resources.refimage_fit_three
import org.superdro.core.resources.refimage_steep_angle
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText

/**
 * The reference image setup screen. The picture has 400 × 300 pixels and the view has the same
 * size, so the scale is 1 and the reticle shows the pixel `(200, 150) − pan`.
 */
class ReferenceImageSetupPresenterTest {

    private class Setup(val harness: GraphicsHarness, val presenter: ReferenceImageSetupPresenter) {
        fun close() {
            presenter.close()
            harness.close()
        }
    }

    private suspend fun TestScope.started(
        machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true),
        width: Int = 400,
        height: Int = 300,
    ): Setup {
        val harness = GraphicsHarness(this)
        harness.start(machine)
        harness.moveTo(100L, x = 12000L, y = 5000L, z = 0L)
        val image = assertNotNull(PlatformImages.decode(TestImages.gridPng(width, height)))
        val presenter = ReferenceImageSetupPresenter(harness.deviceId, harness.readout, harness.deps, image)
        presenter.onViewSize(width.toDouble(), height.toDouble())
        advanceUntilIdle()
        return Setup(harness, presenter)
    }

    /** Puts the reticle on the image pixel ([px], [py]) and sets the card [index] with the values [h] and [v] (mm). */
    private fun TestScope.set(presenter: ReferenceImageSetupPresenter, index: Int, px: Double, py: Double, h: String, v: String) {
        presenter.onResetView()
        presenter.onPan(200.0 - px, 150.0 - py)
        presenter.onHorizontalText(index, h)
        presenter.onVerticalText(index, v)
        presenter.onSetOrClear(index)
        advanceUntilIdle()
    }

    @Test
    fun twoReferencesGiveTheRectangleMapAndApplyStoresTheJpegAndTheMatrix() = runTest {
        val setup = started()
        val presenter = setup.presenter
        try {
            set(presenter, 0, 100.0, 50.0, "10", "20")
            set(presenter, 1, 300.0, 250.0, "50", "0")
            val state = presenter.uiState.value
            assertTrue(state.cards[0].isSet && state.cards[1].isSet)
            assertTrue(state.cards[2].visible && state.cards[3].visible)
            assertTrue(state.canApply)
            assertNull(state.fitMessage)
            assertNull(state.banner)
            presenter.onApply()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.closed)
            val stored = assertNotNull(setup.harness.workspace().referenceImage)
            assertTrue(stored.fileName.startsWith("images/") && stored.fileName.endsWith(".jpg"), stored.fileName)
            assertEquals(255, stored.alpha)
            assertEquals(9, stored.matrix.size)
            // sx = 40000 µm / 200 px, sy = −20000 µm / 200 px: the pixel (200, 150) is X 30 mm, Y 10 mm.
            val machine = Matrix3.of(stored.matrix).map(200.0, 150.0)
            assertEquals(30000.0, machine.x, 1e-6)
            assertEquals(10000.0, machine.y, 1e-6)
            val file = assertNotNull(setup.harness.store.storeIO.files[stored.fileName])
            val decoded = assertNotNull(PlatformImages.decode(file))
            assertEquals(400, decoded.width)
            assertEquals(300, decoded.height)
        } finally {
            setup.close()
        }
    }

    @Test
    fun aNewImageDeletesTheOldFile() = runTest {
        val setup = started()
        try {
            set(setup.presenter, 0, 100.0, 50.0, "10", "20")
            set(setup.presenter, 1, 300.0, 250.0, "50", "0")
            setup.presenter.onApply()
            advanceUntilIdle()
            val first = setup.harness.workspace().referenceImage!!.fileName
            val image = assertNotNull(PlatformImages.decode(TestImages.gridPng(400, 300)))
            val second = ReferenceImageSetupPresenter(setup.harness.deviceId, setup.harness.readout, setup.harness.deps, image)
            second.onViewSize(400.0, 300.0)
            advanceUntilIdle()
            set(second, 0, 100.0, 50.0, "0", "0")
            set(second, 1, 300.0, 250.0, "20", "20")
            second.onApply()
            advanceUntilIdle()
            val newName = setup.harness.workspace().referenceImage!!.fileName
            assertTrue(newName != first)
            assertFalse(first in setup.harness.store.storeIO.files, "the old file stays")
            assertTrue(newName in setup.harness.store.storeIO.files)
            second.close()
        } finally {
            setup.close()
        }
    }

    @Test
    fun aCardNeedsTheTwoValues() = runTest {
        val setup = started()
        try {
            setup.presenter.onHorizontalText(0, "10")
            setup.presenter.onSetOrClear(0)
            advanceUntilIdle()
            val card = setup.presenter.uiState.value.cards[0]
            assertFalse(card.isSet)
            assertEquals(UiText.Resource(Res.string.refimage_enter_values, listOf("X", "Y", 1)), card.error)
        } finally {
            setup.close()
        }
    }

    @Test
    fun threeReferencesCannotBeFitted() = runTest {
        val setup = started()
        try {
            set(setup.presenter, 0, 100.0, 50.0, "10", "20")
            set(setup.presenter, 1, 300.0, 250.0, "50", "0")
            set(setup.presenter, 2, 300.0, 50.0, "50", "20")
            val state = setup.presenter.uiState.value
            assertEquals(UiText.of(Res.string.refimage_fit_three), state.fitMessage)
            assertFalse(state.canApply)
            assertTrue(state.cards[3].active)
        } finally {
            setup.close()
        }
    }

    @Test
    fun fourPointsOnOneLineGiveASingularMatrixAndAreRefused() = runTest {
        val setup = started()
        try {
            set(setup.presenter, 0, 50.0, 150.0, "0", "0")
            set(setup.presenter, 1, 150.0, 150.0, "10", "5")
            set(setup.presenter, 2, 250.0, 150.0, "20", "7")
            set(setup.presenter, 3, 350.0, 150.0, "30", "12")
            val state = setup.presenter.uiState.value
            assertTrue(state.cards.all { card -> card.isSet })
            assertEquals(UiText.of(Res.string.refimage_fit_cannot), state.fitMessage)
            assertFalse(state.canApply)
        } finally {
            setup.close()
        }
    }

    @Test
    fun aSteepAngleGivesTheBanner() = runTest {
        val setup = started()
        try {
            // 200 mm for 200 px: 1000 µm for each pixel, from 600 to 2000.
            set(setup.presenter, 0, 100.0, 50.0, "0", "0")
            set(setup.presenter, 1, 300.0, 250.0, "200", "200")
            val state = setup.presenter.uiState.value
            assertTrue(state.canApply)
            assertEquals(UiText.Resource(Res.string.refimage_steep_angle, listOf("1.00")), state.banner)
            assertEquals("1.00", ReferenceImageSetupPresenter.mmPerPixel(1000.0))
        } finally {
            setup.close()
        }
    }

    @Test
    fun theReticlePixelInPreviewModeIsInImageCoordinates() = runTest {
        val setup = started()
        val presenter = setup.presenter
        try {
            set(presenter, 0, 100.0, 50.0, "10", "20")
            set(presenter, 1, 300.0, 250.0, "50", "0")
            presenter.onResetView()
            presenter.onPreview()
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertTrue(state.preview)
            assertTrue(state.contentMatrix != Matrix3.IDENTITY)
            val pixel = assertNotNull(state.reticlePixel)
            assertEquals(200.0, pixel.x, 1e-6)
            assertEquals(150.0, pixel.y, 1e-6)
            // "Set" in preview mode records the image pixel, not the preview pixel.
            presenter.onHorizontalText(2, "30")
            presenter.onVerticalText(2, "10")
            presenter.onSetOrClear(2)
            advanceUntilIdle()
            val reference = assertNotNull(presenter.uiState.value.cards[2].reference)
            assertEquals(200.0, reference.pixel.x, 1e-6)
            assertEquals(150.0, reference.pixel.y, 1e-6)
            presenter.onPreview()
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.preview)
        } finally {
            setup.close()
        }
    }

    @Test
    fun flipHorizontalMovesTheSetReferencePixel() = runTest {
        val setup = started()
        try {
            set(setup.presenter, 0, 100.0, 50.0, "10", "20")
            setup.presenter.onFlipHorizontal()
            advanceUntilIdle()
            val pixel = setup.presenter.uiState.value.cards[0].reference!!.pixel
            assertEquals(300.0, pixel.x, 1e-9)
            assertEquals(50.0, pixel.y, 1e-9)
            setup.presenter.onFlipVertical()
            advanceUntilIdle()
            val again = setup.presenter.uiState.value.cards[0].reference!!.pixel
            assertEquals(300.0, again.x, 1e-9)
            assertEquals(250.0, again.y, 1e-9)
            setup.presenter.onRotateRight()
            advanceUntilIdle()
            val state = setup.presenter.uiState.value
            assertEquals(300, state.imageWidth)
            assertEquals(400, state.imageHeight)
        } finally {
            setup.close()
        }
    }

    @Test
    fun grayUsesTheSearchedWeightsAndApplyStoresAGrayImage() = runTest {
        val setup = started()
        try {
            setup.presenter.onGray()
            advanceUntilIdle()
            val state = setup.presenter.uiState.value
            assertTrue(state.gray)
            assertNotNull(state.colorMatrix)
            set(setup.presenter, 0, 100.0, 50.0, "10", "20")
            set(setup.presenter, 1, 300.0, 250.0, "50", "0")
            setup.presenter.onApply()
            advanceUntilIdle()
            val stored = setup.harness.workspace().referenceImage!!
            val decoded = assertNotNull(PlatformImages.decode(setup.harness.store.storeIO.files.getValue(stored.fileName)))
            val pixel = decoded.pixel(100, 75)
            val r = pixel ushr 16 and 0xFF
            val g = pixel ushr 8 and 0xFF
            val b = pixel and 0xFF
            assertTrue(kotlin.math.abs(r - g) <= 3 && kotlin.math.abs(g - b) <= 3, "r=$r g=$g b=$b")
        } finally {
            setup.close()
        }
    }

    @Test
    fun theSyncButtonsCopyTheLiveValuesAndZeroSetsBothAbsoluteValues() = runTest {
        val setup = started()
        try {
            val state = setup.presenter.uiState.value
            assertEquals("12.000", state.horizontalValue)
            assertEquals("5.000", state.verticalValue)
            setup.presenter.onSyncHorizontal(0)
            setup.presenter.onSyncVertical(0)
            advanceUntilIdle()
            assertEquals("12.000", setup.presenter.uiState.value.cards[0].horizontalText)
            assertEquals("5.000", setup.presenter.uiState.value.cards[0].verticalText)
            setup.presenter.onZero()
            advanceUntilIdle()
            val frame = setup.harness.readout.frame.value!!
            assertEquals(0.0, frame.reading(AxisId.X).absolute, 1e-9)
            assertEquals(0.0, frame.reading(AxisId.Y).absolute, 1e-9)
            assertEquals("0.000", setup.presenter.uiState.value.horizontalValue)
        } finally {
            setup.close()
        }
    }

    @Test
    fun aLatheCardTakesZAndXFromASubDatum() = runTest {
        val setup = started(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true))
        try {
            val workspaceId = setup.harness.profile.activeWorkspaceId
            val point = setup.harness.store.machines.addSubDatum(workspaceId, SubDatum(name = "Shoulder", x = 5000.0, z = -10000.0)).valueOrNull()!!
            advanceUntilIdle()
            val state = setup.presenter.uiState.value
            assertEquals(AxisId.Z, state.horizontalAxis)
            assertEquals(AxisId.X, state.verticalAxis)
            assertEquals(listOf(SubDatumOption(point.id, "Shoulder")), state.subDatums)
            setup.presenter.onSubDatum(0, point.id)
            advanceUntilIdle()
            val card = setup.presenter.uiState.value.cards[0]
            assertEquals("-10.000", card.horizontalText)
            // The X field shows the diameter; the card stores the radius.
            assertEquals("10.000", card.verticalText)
            setup.presenter.onSetOrClear(0)
            advanceUntilIdle()
            val reference = setup.presenter.uiState.value.cards[0].reference!!
            assertEquals(-10000.0, reference.machine.x, 1e-9)
            assertEquals(5000.0, reference.machine.y, 1e-9)
        } finally {
            setup.close()
        }
    }

    @Test
    fun aLongPressOnCancelClearsAllReferences() = runTest {
        val setup = started()
        try {
            set(setup.presenter, 0, 100.0, 50.0, "10", "20")
            set(setup.presenter, 1, 300.0, 250.0, "50", "0")
            setup.presenter.onClearAll()
            advanceUntilIdle()
            val state = setup.presenter.uiState.value
            assertTrue(state.cards.none { card -> card.isSet })
            assertFalse(state.cards[2].visible)
            assertTrue(state.cards[0].active)
        } finally {
            setup.close()
        }
    }
}
