package org.superdro.core.camera

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Starts a stream with the retry rule of [CameraRetry]: a start that fails with an error of the
 * retry set goes again, a maximum of 3 attempts with a pause of 400 ms.
 */
object CameraStarter {

    /**
     * Calls [attemptStart] with the attempt number 1, 2, or 3 until it succeeds or gives an error
     * that does not go again.
     */
    suspend fun <T> withRetry(log: (String) -> Unit = {}, attemptStart: suspend (attempt: Int) -> Result<T>): Result<T> {
        var attempt = 1
        while (true) {
            val result = attemptStart(attempt)
            val error = (result.exceptionOrNull() as? CameraException)?.error ?: return result
            if (!CameraRetry.shouldRetry(error, attempt)) return result
            attempt += 1
            log(CameraRetry.logText(error, attempt))
            delay(CameraRetry.PAUSE_MS)
        }
    }
}

/**
 * The behavior of one video run of [FakeUvcCameraBackend].
 *
 * @property startErrors The start errors of the first attempts, one for each attempt. After the
 *   list, the start succeeds.
 * @property firstFrameDelayMs The time from the start to the first frame, or null for no frames.
 * @property frameIntervalMs The time between two frames.
 * @property endAfterMs The run ends with [endError] after this time, or never when null.
 */
data class FakeStream(
    val startErrors: List<CameraError> = emptyList(),
    val firstFrameDelayMs: Long? = 200L,
    val frameIntervalMs: Long = 33L,
    val endAfterMs: Long? = null,
    val endError: CameraError? = null,
)

/**
 * A [UvcCameraBackend] with no hardware, for the tests, the UI previews, and the demo mode. The
 * stream events use `delay`, so a test with virtual time controls them.
 */
class FakeUvcCameraBackend(
    cameras: List<CameraIdentity> = emptyList(),
    var modes: Map<String, List<UvcMode>> = emptyMap(),
    var accessResult: Result<Unit> = Result.success(Unit),
    var stream: (CameraIdentity, UvcMode) -> FakeStream = { _, _ -> FakeStream() },
) : UvcCameraBackend {
    private val _cameras = MutableStateFlow(CameraList.of(cameras))
    private val _events = MutableSharedFlow<CameraDeviceEvent>(extraBufferCapacity = 16)

    override val cameras: StateFlow<List<CameraIdentity>> = _cameras.asStateFlow()
    override val deviceEvents: SharedFlow<CameraDeviceEvent> = _events.asSharedFlow()

    /** The start attempts of each run, in order. */
    val startAttempts: MutableList<Pair<String, String>> = mutableListOf()

    /** The target of each call of [start], in order. */
    val startTargets: MutableList<VideoTarget> = mutableListOf()

    /** The number of calls of [stop]. */
    var stops: Int = 0
        private set

    /** Attaches [camera]. */
    fun attach(camera: CameraIdentity) {
        _cameras.value = CameraList.of(_cameras.value.filterNot { item -> item.deviceName == camera.deviceName } + camera)
        _events.tryEmit(CameraDeviceEvent.Attached(camera))
    }

    /** Detaches [camera]. */
    fun detach(camera: CameraIdentity) {
        _cameras.value = _cameras.value.filterNot { item -> item.deviceName == camera.deviceName }
        _events.tryEmit(CameraDeviceEvent.Detached(camera))
    }

    override suspend fun requestAccess(camera: CameraIdentity): Result<Unit> = accessResult

    override suspend fun listModes(camera: CameraIdentity): Result<List<UvcMode>> =
        modes[camera.key]?.let { list -> Result.success(list) }
            ?: Result.failure(CameraException(CameraError(CameraErrorKind.OpenFailed, "no modes")))

    override fun start(camera: CameraIdentity, mode: UvcMode, target: VideoTarget): Flow<StreamEvent> = flow {
        startTargets += target
        val behavior = stream(camera, mode)
        val started = CameraStarter.withRetry { attempt ->
            startAttempts += camera.key to mode.key
            behavior.startErrors.getOrNull(attempt - 1)
                ?.let { error -> Result.failure(CameraException(error)) }
                ?: Result.success(Unit)
        }
        val failure = started.exceptionOrNull() as? CameraException
        if (failure != null) {
            emit(StreamEvent.Ended(failure.error))
            return@flow
        }
        emit(StreamEvent.Started(mode.frameInterval))
        val firstDelay = behavior.firstFrameDelayMs
        if (firstDelay == null) {
            val end = behavior.endAfterMs ?: StreamRules.EMPTY_WAIT_LIMIT.toLong() * StreamRules.FRAME_WAIT_MS
            delay(end)
            emit(StreamEvent.Ended(behavior.endError ?: CameraError(CameraErrorKind.NoFrames, "no frames for $end ms")))
            return@flow
        }
        delay(firstDelay)
        emit(StreamEvent.FirstFrame(mode.width, mode.height, firstDelay))
        val statistics = StreamStatistics()
        var elapsed = firstDelay
        statistics.onFrame(elapsed)
        while (true) {
            val endAt = behavior.endAfterMs
            if (endAt != null && elapsed >= endAt) {
                emit(StreamEvent.Ended(behavior.endError))
                return@flow
            }
            delay(behavior.frameIntervalMs)
            elapsed += behavior.frameIntervalMs
            if (statistics.onFrame(elapsed)) {
                emit(StreamEvent.Stats(statistics.frames, mode.width, mode.height, statistics.fps))
            }
        }
    }

    override suspend fun stop() {
        stops += 1
    }

    override suspend fun close() {
        stops += 1
    }

    /** A video target with no surface. */
    object NoTarget : VideoTarget
}
