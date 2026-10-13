package org.superdro.platform.android.camera

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.camera.CameraError
import org.superdro.core.camera.CameraErrorKind
import org.superdro.core.camera.CameraTimeouts
import org.superdro.core.camera.StreamEvent

/** A stream event of one run. */
data class RunEvent(val run: Int, val event: StreamEvent)

/**
 * The UI process side of the camera service: the
 * bind, the death recipient, the [ICameraClient] stub, the tokens, and the time limits. The UI
 * does not wait for the camera process on the main thread.
 */
class CameraRemote(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val broker = RequestBroker()
    private val runs = RunFilter()
    private val service = MutableStateFlow<ICameraService?>(null)
    private val _events = MutableSharedFlow<RunEvent>(extraBufferCapacity = 256)
    private val _processDied = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** The state of the bind. */
    @Volatile
    var state: ServiceState = ServiceState.Unbound
        private set

    /** The native status of the camera process: null until the process reports. */
    @Volatile
    var nativeAvailable: Boolean? = null
        private set

    /** The stream events of all runs. [RunFilter] keeps only the events of the current run. */
    val events: SharedFlow<RunEvent> = _events.asSharedFlow()

    /** An event each time that the camera process dies. */
    val processDied: SharedFlow<Unit> = _processDied.asSharedFlow()

    /** The token allocation and the waits for the replies. */
    val requests: RequestBroker get() = broker

    /** The current run. */
    val currentRun: RunFilter get() = runs

    private val client = object : ICameraClient.Stub() {
        override fun onNativeStatus(loaded: Boolean, error: String?) {
            nativeAvailable = loaded
            if (!loaded) log.w { "camera process has no native layer: $error" }
        }

        override fun onModes(token: Int, modes: IntArray?) {
            broker.complete(token, CameraReply.Modes(modes ?: IntArray(0)))
        }

        override fun onStartAccepted(token: Int, negotiatedInterval: Int) {
            broker.complete(token, CameraReply.StartAccepted(negotiatedInterval.toLong() and 0xFFFF_FFFFL))
        }

        override fun onStartRefused(token: Int, message: String?) {
            broker.complete(token, CameraReply.StartRefused(message ?: ""))
        }

        override fun onFirstFrame(run: Int, width: Int, height: Int) {
            publish(run, StreamEvent.FirstFrame(width, height, startDelayMs = -1L))
        }

        override fun onStreamEnded(run: Int, errorKind: String?, detail: String?) {
            publish(run, StreamEvent.Ended(CameraWire.errorOf(errorKind, detail)))
            runs.clear(run)
        }

        override fun onStats(run: Int, frames: Int, width: Int, height: Int, fps: Double) {
            publish(run, StreamEvent.Stats(frames, width, height, fps))
        }

        override fun onDiagnostic(run: Int, text: String?) {
            log.d { "camera run $run: $text" }
            publish(run, StreamEvent.Diagnostic(text ?: ""))
        }
    }

    private val deathRecipient = IBinder.DeathRecipient { onProcessDied() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = ICameraService.Stub.asInterface(binder) ?: return
            transition(ServiceEvent.Connected)
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
            runCatching { remote.register(client) }
            service.value = remote
            scope.launch(Dispatchers.IO) { checkIsolation(remote) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
            transition(ServiceEvent.Disconnected)
        }

        override fun onBindingDied(name: ComponentName?) {
            service.value = null
            transition(ServiceEvent.BindingDied)
            runCatching { context.unbindService(this) }
        }

        override fun onNullBinding(name: ComponentName?) {
            transition(ServiceEvent.NullBinding)
            runCatching { context.unbindService(this) }
        }
    }

    /** Starts the bind when the state needs it. The call does not wait. */
    fun startBind() {
        if (!ServiceStates.needsBind(state)) return
        val started = try {
            context.bindService(Intent(context, CameraService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (failure: RuntimeException) {
            log.w(failure) { "bindService threw" }
            false
        }
        transition(if (started) ServiceEvent.BindStarted else ServiceEvent.BindRefused)
    }

    /** Waits for the service for [CameraTimeouts.SERVICE_BIND_MS]. Gives null after the limit. */
    suspend fun awaitService(): ICameraService? {
        startBind()
        return withTimeoutOrNull(CameraTimeouts.SERVICE_BIND_MS) { service.filterNotNull().first() }
            ?: run {
                log.w { "camera service did not bind within ${CameraTimeouts.SERVICE_BIND_MS} ms" }
                null
            }
    }

    /** The service, if it is bound now. */
    fun boundService(): ICameraService? = service.value

    /**
     * Ends [run] in the UI process with [error], for example after a detach. The stream flow of the
     * run then ends.
     */
    fun end(run: Int, error: CameraError?) {
        if (!runs.accepts(run)) return
        _events.tryEmit(RunEvent(run, StreamEvent.Ended(error)))
        runs.clear(run)
    }

    private fun publish(run: Int, event: StreamEvent) {
        if (!runs.accepts(run)) {
            log.d { runs.ignoredText(run) }
            return
        }
        _events.tryEmit(RunEvent(run, event))
    }

    private fun onProcessDied() {
        service.value = null
        transition(ServiceEvent.BinderDied)
        val waiting = broker.failAll()
        if (waiting > 0) log.w { "camera process died with a request outstanding" }
        val run = runs.current
        if (run != 0) {
            _events.tryEmit(RunEvent(run, StreamEvent.Ended(CameraError(CameraErrorKind.CameraServiceUnavailable, CameraWire.PROCESS_DIED))))
            runs.clear(run)
        }
        nativeAvailable = null
        _processDied.tryEmit(Unit)
    }

    private fun checkIsolation(remote: ICameraService) {
        val pid = runCatching { remote.pid }.getOrNull() ?: return
        if (pid == Process.myPid()) {
            log.e { "the camera service runs in this process ($pid): the isolation is not in effect" }
        } else {
            log.d { "camera service in process $pid" }
        }
    }

    private fun transition(event: ServiceEvent) {
        state = ServiceStates.next(state, event)
        log.d { ServiceStates.logText(event) }
    }

    private companion object {
        val log = Arbor.tag("CameraRemote")
    }
}
