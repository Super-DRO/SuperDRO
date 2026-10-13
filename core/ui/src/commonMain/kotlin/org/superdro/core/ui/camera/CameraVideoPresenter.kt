package org.superdro.core.ui.camera

import androidx.compose.runtime.Immutable
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.camera.CameraDeviceEvent
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorDialog
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraIdentity
import org.superdro.core.camera.CameraMemory
import org.superdro.core.camera.CameraModeMemory
import org.superdro.core.camera.CameraOverlay
import org.superdro.core.camera.CameraViewSettings
import org.superdro.core.camera.CrosshairColor
import org.superdro.core.camera.ImageCalibrationMath
import org.superdro.core.camera.OverlayLayers
import org.superdro.core.camera.PlayChoice
import org.superdro.core.camera.StreamEvent
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.camera.UvcMode
import org.superdro.core.camera.VideoPlayEffect
import org.superdro.core.camera.VideoPlayRules
import org.superdro.core.camera.VideoPlayState
import org.superdro.core.camera.VideoTarget
import org.superdro.core.camera.ViewLockState
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraSelection
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText
import org.superdro.core.model.GraphicalDisplay

/**
 * The dialog of the video.
 *
 * @property offersSetup True: the buttons are "Camera setup" and "Cancel". False: one button "OK".
 * @property openSettings True: the button "Open settings" opens the app settings (a blocked `CAMERA`).
 */
@Immutable
data class CameraVideoDialog(val message: UiText, val offersSetup: Boolean, val openSettings: Boolean = false)

/**
 * The state of the video of one tab. The camera screen and the backdrop of
 * the graphical view show it.
 *
 * @property play The play request and the stop conditions ([VideoPlayRules]).
 * @property attached True when a UVC camera is attached.
 * @property configured True when the camera of the tab is "configured" ([CameraModeMemory.isConfigured]).
 * @property camera The camera of the run, or the camera that a start selects.
 * @property mode The mode of the run, or null when no run starts or runs.
 * @property videoWidth The width of the frames, or the mode width before the first frame.
 * @property fps The measured rate of the run, or null.
 * @property settings `camera_keep_overlays` and `camera_crosshair_color` of the machine profile.
 * @property viewLock The view lock: the video holds it ([ViewLockState]). Outside the video it is
 *   the stored `GraphicalDisplay.viewLocked` of the machine profile.
 * @property crosshairsShown `c_state_cross_hairs_shown`: the stored `GraphicalDisplay.crosshairsShown`.
 * @property dimmed `c_state_cross_hairs_dimmed` ("Half opacity"): the stored `GraphicalDisplay.crosshairsDimmed`.
 * @property calibration The image scale of the camera on this machine, or null.
 * @property dialog The open dialog, or null.
 */
@Immutable
data class CameraVideoUiState(
    val play: VideoPlayState = VideoPlayState(paused = true),
    val attached: Boolean = false,
    val configured: Boolean = false,
    val camera: CameraIdentity? = null,
    val mode: UvcMode? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val fps: Double? = null,
    val settings: CameraViewSettings = CameraViewSettings(),
    val viewLock: ViewLockState = ViewLockState(),
    val crosshairsShown: Boolean = true,
    val dimmed: Boolean = false,
    val calibration: CameraCalibration? = null,
    val dialog: CameraVideoDialog? = null,
) {
    /** True after the first frame, until the stop ("video active"). */
    val active: Boolean get() = play.active

    /** True while the user wants the video and no frame came yet. */
    val starting: Boolean get() = play.wanted && !play.active

    /** True when the video view must exist: the user wants the video and the setup is closed. */
    val viewWanted: Boolean get() = play.wanted && !play.setupOpen

    /** The image pixels for each mm of the frame size, or null with no scale or a scale of a different aspect ratio. */
    val imagePixelsPerMm: Double? get() = ImageCalibrationMath.pixelsPerMmFor(calibration, videoWidth, videoHeight)

    /** True when the scale is for a different aspect ratio: the mode needs its own calibration. */
    val calibrationForOtherMode: Boolean get() = calibration != null && videoWidth > 0 && videoHeight > 0 && imagePixelsPerMm == null

    /** The crosshair color as `0xAARRGGBB` ([CameraOverlay.crosshairArgb]). */
    fun crosshairArgb(themeCursorArgb: Long): Long = CameraOverlay.crosshairArgb(active, settings, themeCursorArgb, dimmed)

    /** The layers of the graphical view that show ([CameraOverlay.visibleLayers]). The crosshairs follow [crosshairsShown]. */
    fun visibleLayers(shown: OverlayLayers = OverlayLayers()): OverlayLayers =
        CameraOverlay.visibleLayers(active, settings, shown.copy(crosshairs = shown.crosshairs && crosshairsShown))

    /** True when the background of the graphical view is transparent. */
    val transparentBackground: Boolean get() = CameraOverlay.transparentBackground(active)

    /** True when the view keeps the tool position at the view center. */
    val followMode: Boolean get() = CameraOverlay.followMode(active, viewLock.locked)

    /** True when a drag pans the view. */
    val panAllowed: Boolean get() = CameraOverlay.panAllowed(viewLock.locked, active)

    /** True when a double tap zooms to fit. */
    val zoomToFitAllowed: Boolean get() = CameraOverlay.zoomToFitAllowed(active)

    /** True when a drawing load zooms to the drawing. */
    val zoomToDrawingAllowed: Boolean get() = CameraOverlay.zoomToDrawingAllowed(viewLock.locked, active)
}

/**
 * The video of one tab. The tab owns it, so the camera screen and the
 * backdrop of the graphical view use
 * the same object (`TabEntryScope.rememberCameraVideo`).
 *
 * - The play button follows [VideoPlayRules]. A start selects the camera
 *   ([CameraModeMemory.cameraForPlay]), gets the access (first `CAMERA`, then the USB permission),
 *   reads the modes, and selects the mode ([CameraModeMemory.playChoice]): only the saved mode or
 *   a GOOD mode starts.
 * - The video view gives its target to [targets]. With no target the video pauses, and it starts
 *   again when the target comes back.
 * - On the first frame, the mode is GOOD and the selection of the profile
 *   ([CameraModeMemory.onFirstFrame]), and the video holds the view lock.
 * - A detach of the camera or a stream error stops the video and shows the dialog of the kind
 *   ([CameraErrorDialog]).
 * - "Camera setup" stops the video while it is open ([CameraSetupGate]).
 *
 * All changes of the state run on one serial dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraVideoPresenter(
    val profileId: String,
    private val backend: UvcCameraBackend,
    private val cameraStore: CameraRepository,
    private val machines: MachineRepository,
    gate: CameraSetupGate,
    private val wallClock: Clock,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val serial = (context.dispatcher as? CoroutineDispatcher)?.limitedParallelism(1) ?: context.dispatcher
    private val _state = MutableStateFlow(CameraVideoUiState())
    private var runJob: Job? = null

    /** The video target of the views of this tab. */
    val targets = VideoTargetSlot()

    /** The state of the video. */
    val state: StateFlow<CameraVideoUiState> = _state.asStateFlow()

    private val selections: StateFlow<List<CameraSelection>> =
        cameraStore.selections(profileId).stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch(serial) {
            combine(backend.cameras, selections) { cameras, rows -> cameras to rows }
                .flatMapLatest { (cameras, rows) ->
                    val camera = CameraModeMemory.cameraForPlay(cameras, rows, profileId, wallClock.now())?.camera
                    if (camera == null) {
                        flowOf(PlayCameraInfo(cameras.isNotEmpty(), null, false, null))
                    } else {
                        cameraStore.modeResults(camera.key).map { results ->
                            PlayCameraInfo(
                                attached = true,
                                camera = camera,
                                configured = CameraModeMemory.isConfigured(camera, rows, results, profileId),
                                calibration = CameraModeMemory.selectionFor(rows, profileId, camera.key)?.calibration,
                            )
                        }
                    }
                }
                .collect { info ->
                    _state.update { current ->
                        val runCamera = current.camera?.takeIf { current.mode != null }
                        val calibration = if (runCamera != null && runCamera.key != info.camera?.key) {
                            CameraModeMemory.selectionFor(selections.value, profileId, runCamera.key)?.calibration
                        } else {
                            info.calibration
                        }
                        current.copy(
                            attached = info.attached,
                            configured = info.configured,
                            camera = runCamera ?: info.camera,
                            calibration = calibration,
                        )
                    }
                }
        }
        scope.launch(serial) {
            machines.profile(profileId).filterNotNull()
                .map { profile -> CameraViewSettings(profile.display.cameraKeepOverlays, CrosshairColor.fromKey(profile.display.cameraCrosshairColor)) }
                .distinctUntilChanged()
                .collect { settings -> _state.update { current -> current.copy(settings = settings) } }
        }
        // The crosshair switch, the half opacity, and the view lock are the stored values of the
        // display pane of the graphical view. While the video holds the
        // lock, the stored lock is the state that comes back at the stop.
        scope.launch(serial) {
            machines.profile(profileId).filterNotNull()
                .map { profile -> profile.graphicalDisplay }
                .distinctUntilChanged()
                .collect { display ->
                    _state.update { current ->
                        val lock = current.viewLock
                        current.copy(
                            crosshairsShown = display.crosshairsShown,
                            dimmed = display.crosshairsDimmed,
                            viewLock = if (lock.heldByCamera) lock.copy(lockedBeforeCamera = display.viewLocked) else lock.copy(locked = display.viewLocked),
                        )
                    }
                }
        }
        scope.launch(serial) {
            gate.open.collect { open ->
                val play = _state.value.play
                apply(if (open) VideoPlayRules.onSetupOpened(play) else VideoPlayRules.onSetupClosed(play))
            }
        }
        scope.launch(serial) {
            // The run follows the surface itself, not only its presence. A view can give a new surface with no gap
            // (the camera screen after the graphical view, or a new SurfaceView of the same view). The run then
            // stops on the old surface and starts again on the new one, so that it never draws into a surface
            // that does not show or that goes away (a "render failed" error with no fault of the mode).
            var last: VideoTarget? = null
            targets.current.map { slot -> slot?.target }.distinctUntilChanged { a, b -> a === b }.collect { target ->
                if (target == null) {
                    apply(VideoPlayRules.onPause(_state.value.play))
                } else {
                    if (last != null) apply(VideoPlayRules.onPause(_state.value.play))
                    apply(VideoPlayRules.onResume(_state.value.play))
                }
                last = target
            }
        }
        scope.launch(serial) {
            backend.deviceEvents.collect { event ->
                val current = _state.value
                if (event is CameraDeviceEvent.Detached && current.mode != null && event.camera.deviceName == current.camera?.deviceName) {
                    apply(VideoPlayRules.onDetached(current.play))
                }
            }
        }
    }

    private data class PlayCameraInfo(
        val attached: Boolean,
        val camera: CameraIdentity?,
        val configured: Boolean,
        val calibration: CameraCalibration?,
    )

    /** The play button: start or stop the video ([VideoPlayRules.onPlayTapped]). */
    fun onPlayTapped() {
        scope.launch(serial) {
            val current = _state.value
            apply(VideoPlayRules.onPlayTapped(current.play, current.configured))
        }
    }

    /** The view gives [target] with the buffer size [width] × [height]. */
    fun onTarget(target: VideoTarget, width: Int, height: Int) = targets.set(target, width, height)

    /** The surface of [target] went away. */
    fun onTargetGone(target: VideoTarget) = targets.remove(target)

    /**
     * "Lock the view to the crosshairs". During the video the change is temporary: the old state
     * comes back at the stop. Else the machine profile stores it (`GraphicalDisplay.viewLocked`).
     */
    fun onViewLockToggled() {
        scope.launch(serial) {
            val lock = _state.value.viewLock
            _state.update { current -> current.copy(viewLock = current.viewLock.toggled()) }
            if (!lock.heldByCamera) writeDisplay { display -> display.copy(viewLocked = !lock.locked) }
        }
    }

    /** "Show or hide" of the crosshairs: the machine profile stores it (`GraphicalDisplay.crosshairsShown`). */
    fun setCrosshairsShown(shown: Boolean) {
        scope.launch(serial) {
            _state.update { current -> current.copy(crosshairsShown = shown) }
            writeDisplay { display -> display.copy(crosshairsShown = shown) }
        }
    }

    /** "Half opacity" of the crosshairs: the machine profile stores it (`GraphicalDisplay.crosshairsDimmed`). */
    fun setDimmed(dimmed: Boolean) {
        scope.launch(serial) {
            _state.update { current -> current.copy(dimmed = dimmed) }
            writeDisplay { display -> display.copy(crosshairsDimmed = dimmed) }
        }
    }

    /**
     * The camera row of the display pane of the graphical view: [wanted] true
     * starts the video, false stops it. A request that the video already has does nothing.
     */
    fun setWanted(wanted: Boolean) {
        scope.launch(serial) {
            val current = _state.value
            if (current.play.wanted != wanted) apply(VideoPlayRules.onPlayTapped(current.play, current.configured))
        }
    }

    private suspend fun writeDisplay(change: (GraphicalDisplay) -> GraphicalDisplay) {
        machines.updateProfile(profileId) { profile -> profile.copy(graphicalDisplay = change(profile.graphicalDisplay)) }
    }

    /** Writes `camera_crosshair_color` of the machine profile. */
    fun setCrosshairColor(color: CrosshairColor) {
        scope.launch(serial) {
            machines.updateProfile(profileId) { profile -> profile.copy(display = profile.display.copy(cameraCrosshairColor = color.key)) }
        }
    }

    /** Writes `camera_keep_overlays` of the machine profile. */
    fun setKeepOverlays(keep: Boolean) {
        scope.launch(serial) {
            machines.updateProfile(profileId) { profile -> profile.copy(display = profile.display.copy(cameraKeepOverlays = keep)) }
        }
    }

    /** The user closed the dialog. */
    fun onDialogDismissed() {
        scope.launch(serial) { _state.update { current -> current.copy(dialog = null) } }
    }

    private fun apply(transition: Pair<VideoPlayState, List<VideoPlayEffect>>) {
        val (play, effects) = transition
        _state.update { current -> current.copy(play = play) }
        effects.forEach { effect ->
            when (effect) {
                VideoPlayEffect.Start -> startRun()
                VideoPlayEffect.Stop -> stopRun()
                is VideoPlayEffect.ShowError -> showError(effect.error)
            }
        }
    }

    private fun showError(error: CameraError) {
        val dialog = CameraErrorDialog.of(error.kind)
        val message = if (dialog.messageKey == error.kind.messageKey) CameraTexts.message(error) else CameraTexts.key(dialog.messageKey)
        _state.update { current -> current.copy(dialog = CameraVideoDialog(message, dialog.offersSetup, CameraTexts.isBlocked(error))) }
    }

    private fun stopRun() {
        runJob?.cancel()
        runJob = null
        _state.update { current ->
            current.copy(mode = null, fps = null, viewLock = current.viewLock.onVideoStopped(), play = current.play.copy(active = false))
        }
    }

    private fun startRun() {
        runJob?.cancel()
        runJob = scope.launch(serial) {
            val rows = cameraStore.loadSelections(profileId)
            val choice = CameraModeMemory.cameraForPlay(backend.cameras.value, rows, profileId, wallClock.now())
            if (choice == null) {
                noStart(CameraVideoDialog(CameraTexts.key(CameraErrorKind.NoDevice.messageKey), offersSetup = false))
                return@launch
            }
            if (choice.touchedSelections != null) choice.selection?.let { row -> cameraStore.saveSelection(row) }
            val camera = choice.camera
            _state.update { current -> current.copy(camera = camera) }
            backend.requestAccess(camera).onFailure { failure ->
                apply(VideoPlayRules.onStreamError(_state.value.play, CameraTexts.errorOf(failure)))
                return@launch
            }
            val modes = backend.listModes(camera).getOrElse { failure ->
                apply(VideoPlayRules.onStreamError(_state.value.play, CameraTexts.errorOf(failure)))
                return@launch
            }
            val results = cameraStore.loadModeResults(camera.key)
            when (val play = CameraModeMemory.playChoice(camera, modes, rows, results, profileId)) {
                is PlayChoice.Start -> run(play.camera, play.mode)
                PlayChoice.NoCamera -> noStart(CameraVideoDialog(CameraTexts.key(CameraErrorKind.NoDevice.messageKey), offersSetup = false))
                PlayChoice.WrongCamera -> noStart(CameraVideoDialog(CameraTexts.key(WRONG_CAMERA_KEY), offersSetup = true))
                PlayChoice.NeedsSetup -> noStart(CameraVideoDialog(CameraTexts.key(CameraErrorDialog.NEEDS_SETUP_KEY), offersSetup = true))
            }
        }
    }

    private fun noStart(dialog: CameraVideoDialog) {
        _state.update { current -> current.copy(play = VideoPlayRules.onNoStart(current.play), mode = null, dialog = dialog) }
    }

    private suspend fun run(camera: CameraIdentity, mode: UvcMode) {
        _state.update { current ->
            current.copy(
                camera = camera,
                mode = mode,
                videoWidth = mode.width,
                videoHeight = mode.height,
                fps = null,
                calibration = CameraModeMemory.selectionFor(selections.value, profileId, camera.key)?.calibration,
            )
        }
        val target = targets.await(mode.width, mode.height)
        backend.start(camera, mode, target).collect { event ->
            when (event) {
                is StreamEvent.FirstFrame -> {
                    _state.update { current ->
                        current.copy(
                            play = VideoPlayRules.onFirstFrame(current.play),
                            viewLock = current.viewLock.onVideoStarted(),
                            videoWidth = event.width.takeIf { width -> width > 0 } ?: current.videoWidth,
                            videoHeight = event.height.takeIf { height -> height > 0 } ?: current.videoHeight,
                        )
                    }
                    writeFirstFrame(camera, mode)
                }
                is StreamEvent.Stats -> _state.update { current ->
                    current.copy(
                        fps = event.fps.takeIf { rate -> rate > 0.0 } ?: current.fps,
                        videoWidth = event.width.takeIf { width -> width > 0 } ?: current.videoWidth,
                        videoHeight = event.height.takeIf { height -> height > 0 } ?: current.videoHeight,
                    )
                }
                is StreamEvent.Ended -> {
                    val error = event.error
                    if (error != null) {
                        apply(VideoPlayRules.onStreamError(_state.value.play, error))
                    } else {
                        // The camera process ended the run with no error: the request goes off.
                        _state.update { current ->
                            current.copy(play = VideoPlayRules.onNoStart(current.play), mode = null, fps = null, viewLock = current.viewLock.onVideoStopped())
                        }
                    }
                }
                is StreamEvent.Started, is StreamEvent.Diagnostic -> Unit
            }
        }
    }

    private suspend fun writeFirstFrame(camera: CameraIdentity, mode: UvcMode) {
        val memory = CameraMemory(cameraStore.loadModeResults(camera.key), cameraStore.loadSelections(profileId))
        val updated = CameraModeMemory.onFirstFrame(memory, profileId, camera, mode, wallClock.now())
        CameraModeMemory.resultFor(updated.results, camera.key, mode)?.let { row -> cameraStore.saveModeResult(row) }
        CameraModeMemory.selectionFor(updated.selections, profileId, camera.key)?.let { row -> cameraStore.saveSelection(row) }
    }

    override fun close() {
        runJob?.cancel()
        super.close()
    }

    private companion object {
        const val WRONG_CAMERA_KEY = "camera_play_wrong_camera"
    }
}
