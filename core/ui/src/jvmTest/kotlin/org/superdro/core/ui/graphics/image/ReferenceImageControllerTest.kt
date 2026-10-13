package org.superdro.core.ui.graphics.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReferenceImage
import org.superdro.core.resources.Res
import org.superdro.core.resources.refimage_decode_failed
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.PickedFile

/** The reference image controller of a tab. */
class ReferenceImageControllerTest {

    private suspend fun TestScope.started(): Pair<GraphicsHarness, ReferenceImageController> {
        val harness = GraphicsHarness(this)
        harness.start(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true))
        val controller = ReferenceImageController(harness.deviceId, harness.readout, harness.deps)
        advanceUntilIdle()
        return harness to controller
    }

    private suspend fun TestScope.storeImage(harness: GraphicsHarness, name: String, alpha: Int) {
        val raster = assertNotNull(PlatformImages.decode(TestImages.gridPng(120, 80)))
        harness.store.storeIO.files[name] = PlatformImages.encodeJpeg(raster)
        harness.updateWorkspace { workspace -> workspace.copy(referenceImage = ReferenceImage(name, ReferenceImage.IDENTITY, alpha)) }
        advanceUntilIdle()
    }

    @Test
    fun aSelectedFileOpensTheSetupScreenWithTheDecodedPicture() = runTest {
        val (harness, controller) = started()
        try {
            harness.filePicker.picks += PickedFile("grid.png", TestImages.gridPng(400, 300))
            controller.selectFile()
            advanceUntilIdle()
            assertEquals(listOf("image/jpeg", "image/jpg", "image/png"), harness.filePicker.mimeRequests.single())
            val request = assertIs<ReferenceImageRequest.OpenSetup>(controller.request.value)
            val image = assertNotNull(controller.takePendingImage())
            assertEquals(400, image.width)
            assertEquals(300, image.height)
            assertNull(controller.takePendingImage())
            controller.onRequestHandled(request.id)
            assertNull(controller.request.value)
        } finally {
            controller.close()
            harness.close()
        }
    }

    @Test
    fun aPhotoOfTheCameraAppOpensTheSetupScreen() = runTest {
        val (harness, controller) = started()
        try {
            harness.filePicker.photos += PickedFile("photo.jpg", PlatformImages.encodeJpeg(assertNotNull(PlatformImages.decode(TestImages.gridPng(64, 48)))))
            controller.takePhoto()
            advanceUntilIdle()
            assertIs<ReferenceImageRequest.OpenSetup>(controller.request.value)
            assertEquals(64, controller.takePendingImage()?.width)
        } finally {
            controller.close()
            harness.close()
        }
    }

    @Test
    fun aFileThatIsNotAnImageGivesAMessage() = runTest {
        val (harness, controller) = started()
        try {
            harness.filePicker.picks += PickedFile("notes.txt", "notes".encodeToByteArray())
            controller.selectFile()
            advanceUntilIdle()
            val request = assertIs<ReferenceImageRequest.Message>(controller.request.value)
            assertEquals(UiText.of(Res.string.refimage_decode_failed), request.text)
            assertNull(controller.takePendingImage())
        } finally {
            controller.close()
            harness.close()
        }
    }

    @Test
    fun theOpacityButtonCyclesTheAlpha() = runTest {
        val (harness, controller) = started()
        try {
            storeImage(harness, "images/a.jpg", alpha = 0)
            val alphas = mutableListOf<Int>()
            repeat(3) {
                controller.cycleAlpha()
                advanceUntilIdle()
                alphas += harness.workspace().referenceImage!!.alpha
            }
            assertEquals(listOf(255, 127, 0), alphas)
            controller.setAlpha(127)
            advanceUntilIdle()
            assertEquals(127, harness.workspace().referenceImage!!.alpha)
        } finally {
            controller.close()
            harness.close()
        }
    }

    @Test
    fun theViewGetsTheDecodedStoredImageWithItsMatrixAndAlpha() = runTest {
        val (harness, controller) = started()
        try {
            storeImage(harness, "images/a.jpg", alpha = 127)
            val image = assertNotNull(controller.image.value)
            assertEquals("images/a.jpg", image.fileName)
            assertEquals(120, image.width)
            assertEquals(80, image.height)
            assertEquals(127, image.alpha)
            assertEquals(120, image.bitmap.width)
        } finally {
            controller.close()
            harness.close()
        }
    }

    @Test
    fun theDeleteRemovesTheFileAndTheMatrix() = runTest {
        val (harness, controller) = started()
        try {
            storeImage(harness, "images/a.jpg", alpha = 255)
            assertTrue("images/a.jpg" in harness.store.storeIO.files)
            controller.delete()
            advanceUntilIdle()
            assertNull(harness.workspace().referenceImage)
            assertFalse("images/a.jpg" in harness.store.storeIO.files)
            assertNull(controller.image.value)
        } finally {
            controller.close()
            harness.close()
        }
    }
}
