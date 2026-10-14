package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraViewSettings
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.camera.ModeLabel
import org.superdro.core.camera.ModeTestResult
import org.superdro.core.camera.ModeTestState
import org.superdro.core.camera.ModeTestUiState
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.VideoPlayState
import org.superdro.core.camera.VideoTarget
import org.superdro.core.camera.ViewLockState
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeType
import org.superdro.core.model.ThemeMode
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.ui.ScreenshotRenderer
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.theme.AppTheme

/**
 * Draws the camera setup screen and the camera screen to PNG files
 * (`core/ui/build/screenshots/camera-*.png`) in light and dark mode, in landscape (1280 × 800 dp)
 * and in portrait (800 × 1280 dp). The states are built directly.
 */
class CameraScreenshotTest {

    private val time = Instant.parse("2026-10-12T08:00:00Z")
    private val sizes = listOf(Triple("landscape", 1280, 800), Triple("portrait", 800, 1280))

    private object NoSetupEvents : CameraSetupEvents {
        override fun onCameraSelected(camera: CameraIdentity) = Unit
        override fun onFindCameras() = Unit
        override fun onModeSelected(mode: UvcMode) = Unit
        override fun onTestTapped() = Unit
        override fun onSave() = Unit
        override fun onOpenSettings() = Unit
        override fun onPreviewTarget(target: VideoTarget, width: Int, height: Int) = Unit
        override fun onPreviewTargetGone(target: VideoTarget) = Unit
    }

    private object NoScreenEvents : CameraScreenEvents {
        override fun onPlayTapped() = Unit
        override fun onSetup() = Unit
        override fun onViewLockToggled() = Unit
        override fun onCrosshairsShown(shown: Boolean) = Unit
        override fun onDimmed(dimmed: Boolean) = Unit
        override fun onCrosshairColor(color: CrosshairColor) = Unit
        override fun onKeepOverlays(keep: Boolean) = Unit
        override fun onStartCalibration(method: CalibrationMethod) = Unit
        override fun onCancelCalibration() = Unit
        override fun onImageTouched(viewX: Double, viewY: Double, viewWidth: Double, viewHeight: Double) = Unit
        override fun onDistanceText(text: String) = Unit
        override fun onDistanceConfirmed() = Unit
        override fun onClearScale() = Unit
        override fun onProbeSelected(id: String) = Unit
        override fun onDialogDismissed() = Unit
        override fun onDialogSetup() = Unit
        override fun onOpenSettings() = Unit
        override fun onTarget(target: VideoTarget, width: Int, height: Int) = Unit
        override fun onTargetGone(target: VideoTarget) = Unit
    }

    @Composable
    private fun Themed(dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.of(width.dp, height.dp)) { content() }
        }
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        listOf(false, true).forEach { dark ->
            sizes.forEach { (size, width, height) ->
                val file = ScreenshotRenderer.render("camera-$name-$size-${if (dark) "dark" else "light"}", width, height) {
                    Themed(dark, width, height, content)
                }
                assertTrue(file.length() > 0)
            }
        }
    }

    private val setupState = CameraSetupUiState(
        cameras = listOf(TestCameras.logitech, TestCameras.noName),
        camera = TestCameras.logitech,
        modes = listOf(
            ModeRow(TestCameras.mjpeg1080, ModeLabel.of(TestCameras.mjpeg1080, null)),
            ModeRow(
                TestCameras.mjpeg720,
                ModeLabel.of(TestCameras.mjpeg720, CameraModeResult(TestCameras.logitech.key, TestCameras.mjpeg720.key, CameraModeStatus.Good, 1280, 720, "MJPEG", 29.8, time)),
            ),
            ModeRow(
                TestCameras.yuy2Vga,
                ModeLabel.of(TestCameras.yuy2Vga, CameraModeResult(TestCameras.logitech.key, TestCameras.yuy2Vga.key, CameraModeStatus.Bad, 640, 480, "UNCOMPRESSED", null, time)),
            ),
        ),
        mode = TestCameras.mjpeg720,
        test = ModeTestUiState(
            mode = TestCameras.mjpeg720,
            state = ModeTestState.Ok,
            attempt = 1,
            progress = 100,
            result = ModeTestResult.WorkingStarted(1280, 720, "29.8", 1.2),
            previewOn = true,
        ),
        lastTest = CameraTexts.key("camera_setup_now"),
    )

    @Test
    fun theCameraSetupScreen() {
        render("setup") { CameraSetupScreen(setupState, NoSetupEvents, onBack = {}) }
        render("setup-blocked") {
            CameraSetupScreen(
                CameraSetupUiState(
                    cameras = listOf(TestCameras.logitech),
                    camera = TestCameras.logitech,
                    error = CameraTexts.message(CameraError(CameraErrorKind.CameraPermissionMissing, openAppSettings = true)),
                    openSettings = true,
                ),
                NoSetupEvents,
                onBack = {},
            )
        }
    }

    private val microscope = ProbeProfile(id = "scope", type = ProbeType.Camera, name = "Microscope", port = null, xOffsetUm = 5_000.0, yOffsetUm = -2_000.0)

    private val playing = CameraVideoUiState(
        play = VideoPlayState(wanted = true, paused = false, active = true),
        attached = true,
        configured = true,
        camera = TestCameras.logitech,
        mode = TestCameras.mjpeg720,
        videoWidth = 1280,
        videoHeight = 720,
        fps = 29.8,
        settings = CameraViewSettings(keepOverlays = false, crosshairColor = CrosshairColor.White),
        viewLock = ViewLockState(locked = true, heldByCamera = true),
        calibration = CameraCalibration(20.0, 1280, 720),
    )

    private val screenState = CameraScreenUiState(
        metric = true,
        probes = listOf(microscope),
        probe = microscope,
    )

    private val point = MutableStateFlow(CameraPointUiState(x = "15.000", y = "1.000", unit = DisplayUnit.Millimeter))

    @Test
    fun theCameraScreen() {
        render("screen") { CameraScreen(playing, screenState, point, NoScreenEvents, onBack = {}) }
        // A tall landscape window shows the whole control panel: the image scale and the point.
        listOf(false, true).forEach { dark ->
            ScreenshotRenderer.render("camera-screen-panel-${if (dark) "dark" else "light"}", 1280, 1400) {
                AppTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.System, systemInDarkTheme = false) {
                    CompositionLocalProvider(LocalWindowLayout provides WindowLayout(org.superdro.core.ui.adaptive.WidthClass.Expanded, landscape = true)) {
                        CameraScreen(playing, screenState.copy(message = CameraScreenPresenter.scaleText(20.0)), point, NoScreenEvents, onBack = {})
                    }
                }
            }
        }
        render("screen-off") {
            CameraScreen(
                CameraVideoUiState(attached = true, configured = true, camera = TestCameras.logitech),
                CameraScreenUiState(),
                MutableStateFlow(CameraPointUiState()),
                NoScreenEvents,
                onBack = {},
            )
        }
        render("screen-calibration") {
            CameraScreen(
                playing.copy(viewLock = ViewLockState(), calibration = null, settings = CameraViewSettings(crosshairColor = CrosshairColor.Cherry)),
                screenState.copy(
                    calibration = CalibrationSession(CalibrationMethod.KnownDistance, firstImage = org.superdro.core.camera.ImagePoint(400.0, 360.0)),
                ),
                point,
                NoScreenEvents,
                onBack = {},
            )
        }
    }
}
