package org.superdro.core.ui.camera

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.camera.FrameRateText
import org.superdro.core.camera.ImageCalibrationMath
import org.superdro.core.camera.MachinePointUm
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeProfile
import org.superdro.core.readout.DisplayUnit
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/**
 * The state of the camera screen beside the video.
 *
 * @property calibration The calibration in progress, or null.
 * @property message The text of the last calibration action, or null.
 * @property distanceText The typed distance of [CalibrationMethod.KnownDistance].
 * @property metric True when the X axis shows mm: the unit of the typed distance.
 * @property probes The camera probe profiles of the machine.
 * @property probe The camera profile of the point under the crosshairs, or null.
 */
@Immutable
data class CameraScreenUiState(
    val calibration: CalibrationSession? = null,
    val message: UiText? = null,
    val distanceText: String = "",
    val metric: Boolean = true,
    val probes: List<ProbeProfile> = emptyList(),
    val probe: ProbeProfile? = null,
)

/**
 * The point under the crosshairs. It changes with each readout frame, so it has its own flow:
 * only the point section of the camera screen collects it.
 *
 * @property x The X of the point in the readout format, or null.
 * @property y The Y of the point, or null.
 * @property unit The unit of [x] and [y], or null.
 */
@Immutable
data class CameraPointUiState(
    val x: String? = null,
    val y: String? = null,
    val unit: DisplayUnit? = null,
)

/**
 * The presenter of the camera screen: the image calibration and the point under
 * the crosshairs. The video is in the tab presenter [video].
 */
class CameraScreenPresenter(
    private val profileId: String,
    val video: CameraVideoPresenter,
    private val readout: TabReadout,
    private val cameraStore: CameraRepository,
    probes: ProbeRepository,
    machines: MachineRepository,
    private val wallClock: Clock,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val serial = (context.dispatcher as? CoroutineDispatcher)?.limitedParallelism(1) ?: context.dispatcher
    private val _state = MutableStateFlow(CameraScreenUiState())
    private val _point = MutableStateFlow(CameraPointUiState())
    private var chosenProbeId: String? = null

    /** The state of the screen. */
    val state: StateFlow<CameraScreenUiState> = _state.asStateFlow()

    /** The point under the crosshairs. */
    val point: StateFlow<CameraPointUiState> = _point.asStateFlow()

    init {
        scope.launch(serial) {
            combine(probes.library(profileId), machines.profile(profileId)) { library, profile -> library to profile }
                .collect { (library, profile) ->
                    val cameras = CameraCrosshairPoint.cameraProfiles(library)
                    val probe = cameras.firstOrNull { item -> item.id == chosenProbeId }
                        ?: profile?.let { value -> CameraCrosshairPoint.preferred(library, value.probeRoles) }
                        ?: cameras.firstOrNull()
                    _state.update { current -> current.copy(probes = cameras, probe = probe) }
                    updatePoint()
                }
        }
        scope.launch(serial) { readout.frame.collect { updatePoint() } }
    }

    /** The user selects the camera profile [id]. */
    fun onProbeSelected(id: String) {
        scope.launch(serial) {
            chosenProbeId = id
            _state.update { current -> current.copy(probe = current.probes.firstOrNull { item -> item.id == id } ?: current.probe) }
            updatePoint()
        }
    }

    /** Starts a calibration with [method]. It needs the video, and the machine move needs a connected adapter. */
    fun onStartCalibration(method: CalibrationMethod) {
        scope.launch(serial) {
            val video = video.state.value
            if (!video.active || video.videoWidth <= 0 || video.videoHeight <= 0) {
                _state.update { current -> current.copy(calibration = null, message = CameraTexts.key(CameraCalibrationFlow.NEEDS_VIDEO_KEY)) }
                return@launch
            }
            if (method == CalibrationMethod.MachineMove) {
                readout.positionUnavailable()?.let { text ->
                    _state.update { current -> current.copy(calibration = null, message = text) }
                    return@launch
                }
            }
            _state.update { current -> current.copy(calibration = CalibrationSession(method), message = null, distanceText = "") }
        }
    }

    /** Stops the calibration with no change. */
    fun onCancelCalibration() {
        scope.launch(serial) { _state.update { current -> current.copy(calibration = null, message = null, distanceText = "") } }
    }

    /** A touch on the video at ([viewX], [viewY]) in a view of [viewWidth] × [viewHeight] px. */
    fun onImageTouched(viewX: Double, viewY: Double, viewWidth: Double, viewHeight: Double) {
        scope.launch(serial) {
            val session = _state.value.calibration ?: return@launch
            val video = video.state.value
            val point = CameraCalibrationFlow.imagePoint(viewX, viewY, viewWidth, viewHeight, video.videoWidth, video.videoHeight)
            handle(CameraCalibrationFlow.onTouch(session, point, machinePosition(), video.videoWidth, video.videoHeight, wallClock.now()))
        }
    }

    /** The user types the distance. */
    fun onDistanceText(text: String) {
        _state.update { current -> current.copy(distanceText = text) }
    }

    /** The user confirms the typed distance. */
    fun onDistanceConfirmed() {
        scope.launch(serial) {
            val current = _state.value
            val session = current.calibration ?: return@launch
            val video = video.state.value
            handle(CameraCalibrationFlow.onDistance(session, current.distanceText, current.metric, video.videoWidth, video.videoHeight, wallClock.now()))
        }
    }

    /** "Clear the scale": removes the image scale of the camera on this machine. */
    fun onClearScale() {
        scope.launch(serial) {
            val camera = video.state.value.camera ?: return@launch
            cameraStore.saveCalibration(profileId, camera.key, null, wallClock.now())
            _state.update { current -> current.copy(calibration = null, message = null) }
        }
    }

    private suspend fun handle(outcome: CalibrationOutcome) {
        when (outcome) {
            is CalibrationOutcome.Next -> _state.update { current -> current.copy(calibration = outcome.session, message = null) }
            is CalibrationOutcome.Problem ->
                _state.update { current -> current.copy(calibration = outcome.session, message = CameraTexts.key(outcome.messageKey)) }
            is CalibrationOutcome.Done -> {
                val camera = video.state.value.camera ?: return
                cameraStore.saveCalibration(profileId, camera.key, outcome.calibration, wallClock.now())
                _state.update { current ->
                    current.copy(calibration = null, distanceText = "", message = scaleText(outcome.calibration.pixelsPerMm))
                }
            }
        }
    }

    private fun machinePosition(): MachinePointUm? {
        if (readout.positionUnavailable() != null) return null
        val frame = readout.frame.value ?: return null
        val x = frame.reading(AxisId.X).takeIf { reading -> reading.hasValue }?.machinePosition ?: return null
        val y = frame.reading(AxisId.Y).takeIf { reading -> reading.hasValue }?.machinePosition ?: return null
        return MachinePointUm(x, y)
    }

    private fun updatePoint() {
        val frame = readout.frame.value
        val probe = _state.value.probe
        val point = frame?.let { value -> CameraCrosshairPoint.of(value, probe) }
        // The screen state changes only with the unit; the point changes with each frame.
        _state.update { current -> current.copy(metric = frame?.metric(AxisId.X) ?: current.metric) }
        _point.value = CameraPointUiState(
            x = point?.xUm?.let { x -> CameraCrosshairPoint.text(frame, AxisId.X, x) },
            y = point?.yUm?.let { y -> CameraCrosshairPoint.text(frame, AxisId.Y, y) },
            unit = frame?.converter(AxisId.X)?.unit,
        )
    }

    companion object {
        /** The text "%1$s pixels for each mm" of [pixelsPerMm]. */
        fun scaleText(pixelsPerMm: Double): UiText =
            UiText.Key("camera_calibration_result", "%1\$s pixels for each mm", listOf(FrameRateText.format(pixelsPerMm, 2) ?: "0"))

        /**
         * The text of the image scale of [video]: the scale of the frame size, the text of a scale
         * for a different aspect ratio, or `camera_calibration_none`.
         */
        fun scaleState(video: CameraVideoUiState): UiText {
            val perMm = video.imagePixelsPerMm
            return when {
                video.calibration == null -> CameraTexts.key("camera_calibration_none")
                video.calibrationForOtherMode -> CameraTexts.key("camera_calibration_other_mode")
                perMm != null -> scaleText(perMm)
                else -> scaleText(video.calibration.pixelsPerMm)
            }
        }

        /** The text of the image rotation in degrees, or null for a rotation of less than 0.01°. */
        fun rotationText(video: CameraVideoUiState): UiText? {
            val degrees = (video.calibration?.rotationArcSec ?: return null) / ImageCalibrationMath.ARC_SEC_PER_DEGREE
            if (abs(degrees) < 0.005) return null
            val magnitude = FrameRateText.format(abs(degrees), 2) ?: return null
            return UiText.Key("camera_ui_rotation", "Image rotation %1\$s°", listOf(if (degrees < 0) "-$magnitude" else magnitude))
        }
    }
}
