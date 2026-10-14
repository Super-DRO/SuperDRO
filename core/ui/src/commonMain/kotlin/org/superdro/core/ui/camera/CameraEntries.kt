package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.camera.VideoTarget
import org.superdro.core.resources.Res
import org.superdro.core.resources.camera_screen_title
import org.superdro.core.resources.camera_setup_title
import org.superdro.core.ui.navigation.CameraRoute
import org.superdro.core.ui.navigation.CameraSetupRoute
import org.superdro.core.ui.navigation.EntryKind
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.PlaceholderEntry
import org.superdro.core.ui.navigation.RouteArea
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.rememberPresenter

/**
 * The entries of the USB camera: the camera setup screen
 * (`CameraSetupRoute`) and the camera screen (`CameraRoute`), and the camera chip of the
 * status strip. The central provider ([org.superdro.core.ui.navigation.appEntryRegistry]) calls
 * this function after the entries of the graphics area, so that these entries replace the
 * placeholders of the graphics area.
 */
fun EntryRegistry.cameraEntries() {
    tab<CameraSetupRoute>(EntryKind.Screen, RouteArea.Graphics) { route -> CameraSetupEntry(this, route) }
    tab<CameraRoute>(EntryKind.Screen, RouteArea.Graphics) { route -> CameraScreenEntry(this, route) }
    statusStripItem { CameraStatusItem(this) }
}

/** The camera setup screen of the tab. The back gesture and "Cancel" close it, and the test stops. */
@Composable
private fun CameraSetupEntry(scope: TabEntryScope, route: CameraSetupRoute) {
    val ui = rememberCameraUi()
    if (ui == null) {
        PlaceholderEntry(EntryKind.Screen, Res.string.camera_setup_title) { scope.navigator.pop(route) }
        return
    }
    val presenter = rememberPresenter(SETUP_PRESENTER) { ui.setup(scope.deviceId.value) }
    val state by presenter.state.collectAsState()
    LaunchedEffect(state.closed) { if (state.closed) scope.navigator.pop(route) }
    CameraSetupScreen(state = state, events = presenter, onBack = { scope.navigator.pop(route) })
}

/** The camera screen of the tab. */
@Composable
private fun CameraScreenEntry(scope: TabEntryScope, route: CameraRoute) {
    val ui = rememberCameraUi()
    val video = scope.rememberCameraVideo()
    if (ui == null || video == null) {
        PlaceholderEntry(EntryKind.Screen, Res.string.camera_screen_title) { scope.navigator.pop(route) }
        return
    }
    val presenter = rememberPresenter(SCREEN_PRESENTER) { ui.screen(scope.deviceId.value, video, scope.readout) }
    val videoState by video.state.collectAsState()
    val screenState by presenter.state.collectAsState()
    val events = remember(video, presenter, scope) { PresenterCameraEvents(video, presenter, ui, scope) }
    CameraScreen(video = videoState, screen = screenState, point = presenter.point, events = events, onBack = { scope.navigator.pop(route) })
}

/** The [CameraScreenEvents] of the presenters of the camera screen. */
private class PresenterCameraEvents(
    private val video: CameraVideoPresenter,
    private val screen: CameraScreenPresenter,
    private val ui: CameraUi,
    private val scope: TabEntryScope,
) : CameraScreenEvents {
    override fun onPlayTapped() = video.onPlayTapped()
    override fun onSetup() = scope.navigator.push(CameraSetupRoute)
    override fun onViewLockToggled() = video.onViewLockToggled()
    override fun onCrosshairsShown(shown: Boolean) = video.setCrosshairsShown(shown)
    override fun onDimmed(dimmed: Boolean) = video.setDimmed(dimmed)
    override fun onCrosshairColor(color: CrosshairColor) = video.setCrosshairColor(color)
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
    override fun onDialogSetup() {
        video.onDialogDismissed()
        scope.navigator.push(CameraSetupRoute)
    }
    override fun onOpenSettings() {
        video.onDialogDismissed()
        ui.openAppSettings()
    }
    override fun onTarget(target: VideoTarget, width: Int, height: Int) = video.onTarget(target, width, height)
    override fun onTargetGone(target: VideoTarget) = video.onTargetGone(target)
}

private const val SETUP_PRESENTER = "cameraSetup"
private const val SCREEN_PRESENTER = "cameraScreen"
