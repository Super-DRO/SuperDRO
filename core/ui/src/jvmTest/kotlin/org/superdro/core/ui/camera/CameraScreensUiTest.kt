package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.CameraSelection
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.navigation.CameraRoute
import org.superdro.core.ui.navigation.GlobalNavigator
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.presenter.PresenterOwner
import org.superdro.core.ui.theme.AppTheme

/** The Compose UI tests of the camera screens. */
@OptIn(ExperimentalTestApi::class)
class CameraScreensUiTest {

    private val landscape = WindowLayout.of(1280.dp, 800.dp)

    private class RecordingNavigator(override val deviceId: DeviceId) : TabNavigator {
        val pushed = mutableListOf<TabRoute>()
        override fun push(route: TabRoute) {
            pushed += route
        }
        override fun pop() = Unit
        override fun pop(route: TabRoute) = Unit
        override fun replaceTop(route: TabRoute) = Unit
        override fun popToRoot() = Unit
    }

    private object NoGlobal : GlobalNavigator {
        override fun push(route: GlobalRoute) = Unit
        override fun pop() = Unit
        override fun pop(route: GlobalRoute) = Unit
        override fun clear() = Unit
    }

    private class Ui(val scope: TestScope) {
        val harness = CameraHarness(scope)
        lateinit var id: String

        fun run() = scope.testScheduler.advanceUntilIdle()

        fun step(ms: Long) {
            scope.testScheduler.advanceTimeBy(ms)
            scope.testScheduler.runCurrent()
        }

        /** Reads [block] on the test dispatcher of the store. A blocking read would wait for ever. */
        fun <T> read(block: suspend () -> T): T {
            var result: Result<T>? = null
            scope.launch { result = runCatching { block() } }
            step(10L)
            return result!!.getOrThrow()
        }

        fun start(saveMode: Boolean) {
            scope.launch {
                id = harness.start()
                if (saveMode) {
                    harness.cameraStore.saveSelection(CameraSelection(id, TestCameras.logitech.key, TestCameras.mjpeg720.key, harness.store.wallClock.now()))
                }
            }
            run()
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AppTheme(systemInDarkTheme = false) {
            CompositionLocalProvider(LocalWindowLayout provides landscape) { content() }
        }
    }

    /**
     * Gives frames to Compose and time to the presenters. The clock of Compose does not advance by
     * itself ([ComposeUiTest.mainClock] `autoAdvance` off), so an indicator with no end does not
     * stop the wait for idle.
     */
    private fun ComposeUiTest.settle(ui: Ui, ms: Long = 0L) {
        repeat(4) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            if (ms > 0) ui.step(ms / 4) else ui.scope.testScheduler.runCurrent()
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    @Test
    fun theSetupTestsTheModeAndSavesIt() = runComposeUiTest {
        mainClock.autoAdvance = false
        val ui = Ui(TestScope())
        ui.start(saveMode = false)
        val setup = ui.harness.ui.setup(ui.id)
        ui.run()
        setContent {
            Themed {
                val state by setup.state.collectAsState()
                CameraSetupScreen(state, setup, onBack = {})
            }
        }
        settle(ui)
        onNodeWithText("MJPEG 1920 × 1080, 30 fps").assertExists()
        onNodeWithTag(CAMERA_SETUP_RESULT_TAG).assertTextEquals("Not tested")
        onNodeWithTag(CAMERA_SETUP_SAVE_TAG).assertIsNotEnabled()
        onNodeWithTag(CAMERA_SETUP_TEST_TAG).performClick()
        // The preview view gives its target, then each attempt takes 3500 ms.
        settle(ui)
        onNodeWithText("Stop test").assertExists()
        settle(ui, 3_500L)
        settle(ui, 3_600L)
        onNodeWithTag(CAMERA_SETUP_RESULT_TAG).assertTextEquals("Video OK: 1920 × 1080 at 30.3 fps. The first picture came after 0.2 s.")
        onNodeWithTag(CAMERA_SETUP_SAVE_TAG).assertIsEnabled().performClick()
        settle(ui)
        assertTrue(setup.state.value.closed)
        val saved = ui.read { ui.harness.cameraStore.loadSelections(ui.id) }
        assertEquals(listOf(TestCameras.mjpeg1080.key), saved.map { row -> row.modeKey })
        setup.close()
        ui.harness.close()
    }

    @Test
    fun theCameraScreenPlaysTheVideoAndChangesTheCrosshairs() = runComposeUiTest {
        mainClock.autoAdvance = false
        val ui = Ui(TestScope())
        ui.start(saveMode = true)
        val readout = FakeTabReadout(DeviceId(ui.id))
        val video = ui.harness.ui.video(ui.id)
        val screen = ui.harness.ui.screen(ui.id, video, readout)
        ui.run()
        val events = object : CameraScreenEvents {
            override fun onPlayTapped() = video.onPlayTapped()
            override fun onSetup() = Unit
            override fun onViewLockToggled() = video.onViewLockToggled()
            override fun onCrosshairsShown(shown: Boolean) = video.setCrosshairsShown(shown)
            override fun onDimmed(dimmed: Boolean) = video.setDimmed(dimmed)
            override fun onCrosshairColor(color: org.superdro.core.camera.CrosshairColor) = video.setCrosshairColor(color)
            override fun onKeepOverlays(keep: Boolean) = video.setKeepOverlays(keep)
            override fun onStartCalibration(method: CalibrationMethod) = screen.onStartCalibration(method)
            override fun onCancelCalibration() = screen.onCancelCalibration()
            override fun onImageTouched(viewX: Double, viewY: Double, viewWidth: Double, viewHeight: Double) =
                screen.onImageTouched(viewX, viewY, viewWidth, viewHeight)
            override fun onDistanceText(text: String) = screen.onDistanceText(text)
            override fun onDistanceConfirmed() = screen.onDistanceConfirmed()
            override fun onClearScale() = screen.onClearScale()
            override fun onProbeSelected(id: String) = screen.onProbeSelected(id)
            override fun onDialogDismissed() = video.onDialogDismissed()
            override fun onDialogSetup() = video.onDialogDismissed()
            override fun onOpenSettings() = video.onDialogDismissed()
            override fun onTarget(target: org.superdro.core.camera.VideoTarget, width: Int, height: Int) = video.onTarget(target, width, height)
            override fun onTargetGone(target: org.superdro.core.camera.VideoTarget) = video.onTargetGone(target)
        }
        setContent {
            Themed {
                val videoState by video.state.collectAsState()
                val screenState by screen.state.collectAsState()
                CameraScreen(videoState, screenState, events, onBack = {})
            }
        }
        settle(ui)
        onNodeWithTag(CAMERA_STATUS_TAG).assertTextEquals("Video off")
        onNodeWithTag(CAMERA_PLAY_TAG).assertIsEnabled().performClick()
        settle(ui)
        settle(ui, 1_000L)
        onNodeWithTag(CAMERA_STATUS_TAG).assertTextEquals("Video on: 1280 × 720, 30.3 fps")
        assertTrue(video.state.value.viewLock.locked, "The video holds the view lock")
        onNodeWithText("Lock the view to the crosshairs").performScrollTo().performClick()
        settle(ui)
        assertTrue(!video.state.value.viewLock.locked)
        onNodeWithText("Dark red").performScrollTo().performClick()
        settle(ui, 30L)
        val color = ui.read { ui.harness.store.machines.profile(ui.id).first()!!.display.cameraCrosshairColor }
        assertEquals("cherry", color)
        onNodeWithTag(CAMERA_PLAY_TAG).performClick()
        settle(ui, 30L)
        onNodeWithTag(CAMERA_STATUS_TAG).assertTextEquals("Video off")
        screen.close()
        video.close()
        ui.harness.close()
    }

    @Test
    fun theCameraChipShowsWithACameraAndOpensTheCameraScreen() = runComposeUiTest {
        mainClock.autoAdvance = false
        val ui = Ui(TestScope())
        ui.start(saveMode = false)
        val navigator = RecordingNavigator(DeviceId(ui.id))
        val owner = PresenterOwner("tab")
        val scope = TabEntryScope(DeviceId(ui.id), navigator, NoGlobal, owner, { error("No readout in this test.") }, {})
        val di = DI { bindSingleton { ui.harness.ui } }
        setContent {
            ProvideDI(di) { Themed { CameraStatusItem(scope) } }
        }
        settle(ui)
        onNodeWithTag(CAMERA_CHIP_TAG).assertHeightIsAtLeast(48.dp)
        onNodeWithText("Camera").assertExists()
        onNodeWithContentDescription("Open the camera screen").performClick()
        assertEquals(listOf<TabRoute>(CameraRoute), navigator.pushed)
        ui.harness.backend.detach(TestCameras.logitech)
        settle(ui)
        onNodeWithTag(CAMERA_CHIP_TAG).assertDoesNotExist()
        owner.close()
        ui.harness.close()
    }

    @Test
    fun aGraphWithNoCameraUiShowsNoChip() = runComposeUiTest {
        mainClock.autoAdvance = false
        val navigator = RecordingNavigator(DeviceId("mill"))
        val scope = TabEntryScope(DeviceId("mill"), navigator, NoGlobal, PresenterOwner("tab"), { error("No readout.") }, {})
        setContent { ProvideDI(DI {}) { Themed { CameraStatusItem(scope) } } }
        waitForIdle()
        onNodeWithTag(CAMERA_CHIP_TAG).assertDoesNotExist()
    }
}
