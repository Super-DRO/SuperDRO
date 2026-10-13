package org.superdro.core.ui.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.datetime.TimeZone
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.FakeStream
import org.superdro.core.camera.FakeUvcCameraBackend
import org.superdro.core.camera.UvcCodec
import org.superdro.core.camera.UvcMode
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.tabs.RecordingRequests
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.Clock

/** The test cameras and modes. */
object TestCameras {
    val logitech = CameraIdentity(0x046d, 0x0825, "Logitech", "C270", "/dev/bus/usb/001/004")
    val noName = CameraIdentity(0x1234, 0xabcd, null, null, "/dev/bus/usb/001/005")

    val mjpeg1080 = UvcMode(UvcCodec.Mjpeg, 1920, 1080, 333_333L, "MJPG")
    val mjpeg720 = UvcMode(UvcCodec.Mjpeg, 1280, 720, 333_333L, "MJPG")
    val mjpeg720Slow = UvcMode(UvcCodec.Mjpeg, 1280, 720, 666_666L, "MJPG")
    val yuy2Vga = UvcMode(UvcCodec.Uncompressed, 640, 480, 333_333L, "YUY2")
    val all = listOf(mjpeg720Slow, yuy2Vga, mjpeg1080, mjpeg720)
}

/** A [TabReadout] with a frame that the test sets. */
class FakeTabReadout(override val deviceId: DeviceId) : TabReadout {
    override val frame = MutableStateFlow<ReadoutFrame?>(null)
    override val sessionState: StateFlow<SessionState?> = MutableStateFlow(SessionState.Connected(0L))
    var unavailable: UiText? = null
    override fun positionUnavailable(): UiText? = unavailable
    override fun apply(result: OperationResult, label: UiText?) = Unit
    override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) = Unit
    override fun showMessage(text: UiText) = Unit
}

/**
 * The camera UI on a database in memory, with the fake camera back end. All work runs on the test
 * scheduler of [scope] (virtual time).
 */
class CameraHarness(private val scope: TestScope, cameras: List<CameraIdentity> = listOf(TestCameras.logitech)) {
    val store = UiStoreFixture(scope)
    val cameraStore = CameraRepository(store.writer)
    val probes = ProbeRepository(store.writer)
    val backend = FakeUvcCameraBackend(
        cameras = cameras,
        modes = mapOf(TestCameras.logitech.key to TestCameras.all, TestCameras.noName.key to listOf(TestCameras.yuy2Vga)),
    )
    val gate = CameraSetupGate()
    val requests = RecordingRequests()
    val clock = Clock { scope.testScheduler.currentTime }
    val context = PresenterContext(store.dispatcher, clock)
    val ui = CameraUi(backend, cameraStore, store.machines, probes, gate, requests, context, store.wallClock, TimeZone.UTC)
    lateinit var profile: MachineProfile

    /** Stores a metric mill and gives its ID. */
    suspend fun start(machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)): String {
        profile = store.machines.createProfile(machine).valueOrNull() ?: error("The profile was not stored.")
        scope.advanceUntilIdle()
        return profile.id
    }

    /** Sets the behavior of each video run. */
    fun stream(behavior: FakeStream) {
        backend.stream = { _, _ -> behavior }
    }

    fun close() = store.close()
}
