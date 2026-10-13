package org.superdro.core.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import org.kodein.di.direct
import org.kodein.di.instanceOrNull
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.LocalDI
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.tabs.RequestSender

/**
 * Makes the presenters of the camera. `core:di` binds one object
 * (`cameraUiModule`). The entries get it with [rememberCameraUi].
 *
 * @property backend The USB camera of the platform.
 * @property gate The open state of "Camera setup".
 */
class CameraUi(
    val backend: UvcCameraBackend,
    private val cameras: CameraRepository,
    private val machines: MachineRepository,
    private val probes: ProbeRepository,
    val gate: CameraSetupGate,
    private val requests: RequestSender,
    private val context: PresenterContext,
    private val wallClock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /** The video of the tab of the machine profile [profileId]. The tab owns it. */
    fun video(profileId: String): CameraVideoPresenter =
        CameraVideoPresenter(profileId, backend, cameras, machines, gate, wallClock, context)

    /** The presenter of "Camera setup" of the machine profile [profileId]. */
    fun setup(profileId: String): CameraSetupPresenter =
        CameraSetupPresenter(profileId, backend, cameras, gate, requests, wallClock, timeZone, context)

    /** Opens the system settings of the app, for a blocked `CAMERA` permission. */
    fun openAppSettings() = requests.send(ConnectionRequest.OpenAppSettings)

    /** The presenter of the camera screen with the video [video] and the readout [readout] of the tab. */
    fun screen(profileId: String, video: CameraVideoPresenter, readout: TabReadout): CameraScreenPresenter =
        CameraScreenPresenter(profileId, video, readout, cameras, probes, machines, wallClock, context)
}

/** The name of the video presenter in the presenters of a tab. */
const val CAMERA_VIDEO_PRESENTER: String = "cameraVideo"

/** Gives the [CameraUi] of the DI graph, or null when the graph has no camera UI. */
@Composable
fun rememberCameraUi(): CameraUi? {
    val di = LocalDI.current
    return remember(di) { di.direct.instanceOrNull<CameraUi>() }
}

/**
 * Gives the video presenter of the tab, or null when the graph has no camera UI.
 * The camera screen and the graphical view of the tab get the same object.
 */
@Composable
fun TabEntryScope.rememberCameraVideo(): CameraVideoPresenter? {
    val ui = rememberCameraUi() ?: return null
    return remember(ui, deviceId) { tabPresenter(CAMERA_VIDEO_PRESENTER) { ui.video(deviceId.value) } }
}
