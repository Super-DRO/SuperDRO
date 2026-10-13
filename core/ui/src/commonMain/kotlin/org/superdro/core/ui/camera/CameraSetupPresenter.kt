package org.superdro.core.ui.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraList
import org.superdro.core.camera.CameraModeMemory
import org.superdro.core.camera.ModeLabel
import org.superdro.core.camera.ModeTestRunner
import org.superdro.core.camera.ModeTestUiState
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.UvcModeRules
import org.superdro.core.camera.VideoTarget
import org.superdro.core.camera.modeToSave
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.tabs.RequestSender
import org.superdro.core.ui.text.UiText

/** One line of the mode list: the mode and its label values. */
@Immutable
data class ModeRow(val mode: UvcMode, val label: ModeLabel)

/**
 * The state of "Camera setup".
 *
 * @property cameras The attached cameras ([CameraList.of]).
 * @property camera The selected camera, or null.
 * @property loading True while the access and the mode list run.
 * @property error The error text at the top, or null.
 * @property openSettings True when only the app settings can give `CAMERA`: the screen shows "Open settings".
 * @property modes The lines of the mode list ([UvcModeRules.setupList]).
 * @property mode The selected mode, or null.
 * @property noModes True when the camera has no mode that SuperDRO can show.
 * @property test The state of the mode test ([ModeTestRunner]).
 * @property lastTest The value of "Last test" of the selected mode.
 * @property closed True after "Save": the screen closes.
 */
@Immutable
data class CameraSetupUiState(
    val cameras: List<CameraIdentity> = emptyList(),
    val camera: CameraIdentity? = null,
    val loading: Boolean = false,
    val error: UiText? = null,
    val openSettings: Boolean = false,
    val modes: List<ModeRow> = emptyList(),
    val mode: UvcMode? = null,
    val noModes: Boolean = false,
    val test: ModeTestUiState = ModeTestUiState(),
    val lastTest: UiText = CameraTexts.key("camera_setup_never"),
    val closed: Boolean = false,
) {
    /** The result text of the selected mode. */
    val result: UiText get() = CameraTexts.testResult(test.result)

    /** True when "Test" can start. */
    val canTest: Boolean get() = mode != null && !loading && !test.state.isRunning

    /** True when "Save" saves a mode. */
    val canSave: Boolean get() = !loading && modeToSave(test.state, mode, test.mode) != null
}

/** The events of the "Camera setup" screen. */
interface CameraSetupEvents {
    /** The user selects [camera] in the camera list. */
    fun onCameraSelected(camera: CameraIdentity)

    /** "Find cameras". */
    fun onFindCameras()

    /** The user selects [mode] in the mode list. */
    fun onModeSelected(mode: UvcMode)

    /** "Test" or "Stop test". */
    fun onTestTapped()

    /** "Save". */
    fun onSave()

    /** "Open settings". */
    fun onOpenSettings()

    /** The preview view gives [target] with the buffer size [width] × [height]. */
    fun onPreviewTarget(target: VideoTarget, width: Int, height: Int)

    /** The surface of the preview went away. */
    fun onPreviewTargetGone(target: VideoTarget)
}

/**
 * The presenter of "Camera setup".
 *
 * 1. The camera list comes from the back end. With no camera, the error is `camera_error_no_device`.
 * 2. For the selected camera, the access sequence runs: first `CAMERA` through the request bus,
 *    then the USB permission ([UvcCameraBackend.requestAccess]). A blocked `CAMERA` gives "Open
 *    settings".
 * 3. Then the mode list, the mode results, and the saved selection load. The first mode is
 *    [UvcModeRules.setupDefault].
 * 4. "Test" runs the mode test of [ModeTestRunner]: 2 attempts of 3500 ms and a restart check. The
 *    result goes to the mode memory of the camera model.
 * 5. "Save" writes the selection of the tab profile and the camera
 *    ([CameraModeMemory.select] keeps the image scale), and the screen closes.
 *
 * While the screen is open, [CameraSetupGate] stops the other video views. [close] stops the test.
 */
class CameraSetupPresenter(
    private val profileId: String,
    private val backend: UvcCameraBackend,
    private val cameraStore: CameraRepository,
    private val gate: CameraSetupGate,
    private val requests: RequestSender,
    private val wallClock: Clock,
    private val timeZone: TimeZone,
    context: PresenterContext,
) : ScopedPresenter(context), CameraSetupEvents {

    private val serial = (context.dispatcher as? CoroutineDispatcher)?.limitedParallelism(1) ?: context.dispatcher
    private val serialScope = CoroutineScope(scope.coroutineContext + serial)
    private val _state = MutableStateFlow(CameraSetupUiState())
    private var results: List<CameraModeResult> = emptyList()
    private var loadJob: Job? = null

    /** The video target of the preview. */
    val previewTargets = VideoTargetSlot()

    /** The state of the screen. */
    val state: StateFlow<CameraSetupUiState> = _state.asStateFlow()

    private val runner = ModeTestRunner(
        scope = serialScope,
        clock = clock,
        preview = { mode -> preview(mode) },
        store = { mode, status, fps -> storeResult(mode, status, fps) },
    )

    init {
        gate.opened()
        serialScope.launch { runner.ui.collect { test -> _state.update { current -> current.copy(test = test) } } }
        serialScope.launch {
            backend.cameras.collect { cameras ->
                val list = CameraList.of(cameras)
                val current = _state.value.camera
                _state.update { state -> state.copy(cameras = list) }
                val next = CameraList.select(list, current?.deviceName)
                if (next != current || current == null) load(next)
            }
        }
    }

    /** The user selects [camera] in the camera list. */
    override fun onCameraSelected(camera: CameraIdentity) {
        serialScope.launch { if (camera != _state.value.camera) load(camera) }
    }

    /** "Find cameras": the access and the mode list run again for the selected camera. */
    override fun onFindCameras() {
        serialScope.launch { load(CameraList.select(_state.value.cameras, _state.value.camera?.deviceName)) }
    }

    /** The user selects [mode] in the mode list. A test that runs stops. */
    override fun onModeSelected(mode: UvcMode) {
        serialScope.launch { select(mode) }
    }

    /** "Test", or "Stop test" while a test runs. */
    override fun onTestTapped() {
        serialScope.launch {
            if (_state.value.test.state.isRunning) runner.cancel() else if (_state.value.canTest) runner.test()
        }
    }

    /** "Save": writes the selection of the profile and the camera, then closes the screen. */
    override fun onSave() {
        serialScope.launch {
            val current = _state.value
            val camera = current.camera ?: return@launch
            val mode = modeToSave(current.test.state, current.mode, current.test.mode) ?: return@launch
            runner.close()
            val rows = CameraModeMemory.select(cameraStore.loadSelections(profileId), profileId, camera.key, mode, wallClock.now())
            CameraModeMemory.selectionFor(rows, profileId, camera.key)?.let { row -> cameraStore.saveSelection(row) }
            _state.update { state -> state.copy(closed = true) }
        }
    }

    /** "Open settings" for a blocked `CAMERA` permission. */
    override fun onOpenSettings() {
        requests.send(ConnectionRequest.OpenAppSettings)
    }

    /** The preview view gives [target] with the buffer size [width] × [height]. */
    override fun onPreviewTarget(target: VideoTarget, width: Int, height: Int) = previewTargets.set(target, width, height)

    /** The surface of the preview went away. */
    override fun onPreviewTargetGone(target: VideoTarget) = previewTargets.remove(target)

    private fun load(camera: CameraIdentity?) {
        loadJob?.cancel()
        if (_state.value.test.state.isRunning) runner.cancel()
        runner.close()
        results = emptyList()
        if (camera == null) {
            _state.update { state ->
                state.copy(
                    camera = null, loading = false, modes = emptyList(), mode = null, noModes = false, openSettings = false,
                    error = CameraTexts.key(CameraErrorKind.NoDevice.messageKey),
                )
            }
            return
        }
        _state.update { state ->
            state.copy(camera = camera, loading = true, error = null, openSettings = false, modes = emptyList(), mode = null, noModes = false)
        }
        loadJob = serialScope.launch {
            backend.requestAccess(camera).onFailure { failure ->
                val error = CameraTexts.errorOf(failure)
                _state.update { state -> state.copy(loading = false, error = CameraTexts.message(error), openSettings = CameraTexts.isBlocked(error)) }
                return@launch
            }
            val all = backend.listModes(camera).getOrElse { failure ->
                _state.update { state -> state.copy(loading = false, error = CameraTexts.message(CameraTexts.errorOf(failure))) }
                return@launch
            }
            results = cameraStore.loadModeResults(camera.key)
            val saved = cameraStore.selection(profileId, camera.key).first()
            val list = UvcModeRules.setupList(all)
            _state.update { state -> state.copy(loading = false, modes = rows(list), noModes = list.isEmpty()) }
            UvcModeRules.setupDefault(list, saved?.modeKey, results)?.let { mode -> select(mode) }
        }
    }

    private fun select(mode: UvcMode) {
        val camera = _state.value.camera ?: return
        val row = CameraModeMemory.resultFor(results, camera.key, mode)
        runner.select(mode, row)
        _state.update { state -> state.copy(mode = mode, lastTest = CameraTexts.lastTest(row?.updatedOn, wallClock.now(), timeZone)) }
    }

    private fun preview(mode: UvcMode) = flow {
        val camera = _state.value.camera ?: return@flow
        val target = previewTargets.await(mode.width, mode.height)
        emitAll(backend.start(camera, mode, target))
    }

    private suspend fun storeResult(mode: UvcMode, status: CameraModeStatus, measuredFps: Double?) {
        val camera = _state.value.camera ?: return
        val now = wallClock.now()
        results = CameraModeMemory.record(results, camera.key, mode, status, measuredFps, now)
        val row = CameraModeMemory.resultFor(results, camera.key, mode) ?: return
        cameraStore.saveModeResult(row)
        _state.update { state ->
            state.copy(
                modes = rows(state.modes.map { line -> line.mode }),
                lastTest = if (state.mode == mode) CameraTexts.lastTest(row.updatedOn, now, timeZone) else state.lastTest,
            )
        }
    }

    private fun rows(modes: List<UvcMode>): List<ModeRow> {
        val key = _state.value.camera?.key ?: return emptyList()
        return modes.map { mode -> ModeRow(mode, ModeLabel.of(mode, CameraModeMemory.resultFor(results, key, mode))) }
    }

    override fun close() {
        if (isClosed) return
        runner.close()
        gate.closed()
        super.close()
    }
}
