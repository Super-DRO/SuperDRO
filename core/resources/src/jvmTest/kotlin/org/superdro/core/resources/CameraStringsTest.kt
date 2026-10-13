package org.superdro.core.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString

/**
 * The English texts of `strings_camera.xml` are the texts that `core:camera` gives for the log
 * (`ModeLabel.englishText`, `ModeTestResult.englishText`). The camera notices are a resource.
 */
@OptIn(ExperimentalResourceApi::class)
class CameraStringsTest {

    @Test
    fun theModeLineHasTheRateOfTheInterval() = runTest {
        assertEquals("MJPEG 1920 × 1080, 15 fps", getString(TestEnvironment, Res.string.camera_mode_format, "MJPEG", 1920, 1080, "15"))
        assertEquals(
            "H.264 1280 × 720, 30 fps (measured 24.9 fps)",
            getString(TestEnvironment, Res.string.camera_mode_format_measured, "H.264", 1280, 720, "30", "24.9"),
        )
        assertEquals("YUY2 640 × 480", getString(TestEnvironment, Res.string.camera_mode_format_no_rate, "YUY2", 640, 480))
    }

    @Test
    fun theTestResultsAreExact() = runTest {
        assertEquals(
            "Video OK: 1280 × 720 at 30.3 fps. The first picture came after 0.2 s.",
            getString(TestEnvironment, Res.string.camera_test_working_started, 1280, 720, "30.3", "0.2"),
        )
        assertEquals("Video OK: 1280 × 720 at 15 fps.", getString(TestEnvironment, Res.string.camera_test_working, 1280, 720, "15"))
        assertEquals("Video OK: 1280 × 720.", getString(TestEnvironment, Res.string.camera_test_working_no_fps, 1280, 720))
        assertEquals("This mode gives no video.", getString(TestEnvironment, Res.string.camera_test_bad))
        assertEquals("You stopped the test.", getString(TestEnvironment, Res.string.camera_test_stopped))
        assertEquals("Not tested", getString(TestEnvironment, Res.string.camera_test_untested))
        assertEquals("Camera setup", getString(TestEnvironment, Res.string.camera_setup_title))
    }

    @Test
    fun onlyTheDeviceCodeShowsThePoweredHubText() = runTest {
        assertTrue("power supply" in getString(TestEnvironment, Res.string.camera_error_powered_hub))
        assertTrue("power" !in getString(TestEnvironment, Res.string.camera_error_native))
        assertEquals(
            "The saved video mode did not start. Open the camera setup and test a mode.",
            getString(TestEnvironment, Res.string.camera_play_needs_setup),
        )
    }

    @Test
    fun theSixCrosshairColorsHaveLabels() = runTest {
        val labels = listOf(
            Res.string.camera_color_default,
            Res.string.camera_color_white,
            Res.string.camera_color_black,
            Res.string.camera_color_cherry,
            Res.string.camera_color_dark_green,
            Res.string.camera_color_dark_magenta,
        ).map { label -> getString(TestEnvironment, label) }
        assertEquals(listOf("Theme color", "White", "Black", "Dark red", "Dark green", "Dark magenta"), labels)
    }

    @Test
    fun theCameraNoticesNameBothLibrariesAndTheLgplRights() = runTest {
        val notices = Res.readBytes("files/notices_camera.txt").decodeToString()
        assertTrue("libusb 1.0.30" in notices)
        assertTrue("libuvc 0.0.8" in notices)
        assertTrue("libusb1.0.so" in notices)
        assertTrue("right to replace" in notices)
        assertTrue("LESSER GENERAL PUBLIC LICENSE" in Res.readBytes("files/licenses/libusb_lgpl_2_1.txt").decodeToString())
        assertTrue("Ken Tossell" in Res.readBytes("files/licenses/libuvc_bsd.txt").decodeToString())
        assertTrue("Johannes Erdfelt" in Res.readBytes("files/licenses/libusb_authors.txt").decodeToString())
    }
}
