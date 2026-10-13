package org.superdro.platform.android.camera

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A reply of the camera process to one request. */
sealed interface CameraReply {
    /** The reply of `listModes`: the native mode list. */
    class Modes(val values: IntArray) : CameraReply

    /** The camera accepted the start. */
    data class StartAccepted(val negotiatedInterval: Long) : CameraReply

    /** The camera refused the start. [message] ends with "(code)". */
    data class StartRefused(val message: String) : CameraReply

    /** The camera process stopped while the request waited. */
    data object ProcessDied : CameraReply
}

/**
 * The requests and the replies over Binder with tokens. A
 * token comes from a counter that starts at 1. The request is one-way. The caller waits for the
 * reply with the same token, with a time limit.
 *
 * The class has no Android type, so a JVM unit test can examine it.
 */
class RequestBroker {
    private val nextToken = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<CameraReply>>()

    /** The number of requests that wait. */
    val waiting: Int get() = pending.size

    /** Gives a new token. */
    fun newToken(): Int = nextToken.getAndIncrement()

    /**
     * Sends a request with [token] and waits [timeoutMs] for the reply. Gives null when no reply
     * came in time. [send] runs after the wait is registered, so a fast reply does not go away.
     */
    suspend fun request(token: Int, timeoutMs: Long, send: (token: Int) -> Unit): CameraReply? {
        val reply = CompletableDeferred<CameraReply>()
        pending[token] = reply
        return try {
            send(token)
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            pending.remove(token)
        }
    }

    /** Gives the reply of [token]. A reply with no waiting request goes away. Gives false then. */
    fun complete(token: Int, reply: CameraReply): Boolean = pending[token]?.complete(reply) ?: false

    /**
     * The camera process stopped: each waiting request gets [CameraReply.ProcessDied]. Gives the
     * number of requests.
     */
    fun failAll(): Int {
        val all = pending.values.toList()
        all.forEach { reply -> reply.complete(CameraReply.ProcessDied) }
        return all.size
    }
}

/**
 * Keeps the current run of the stream. The run is the token of the
 * `startStream` request. The events of a different run go away.
 */
class RunFilter {
    @Volatile
    var current: Int = 0
        private set

    /** Sets the run of a new start. */
    fun start(run: Int) {
        current = run
    }

    /** Clears the run after a stop. */
    fun clear(run: Int) {
        if (current == run) current = 0
    }

    /** True when an event of [run] is for the current run. */
    fun accepts(run: Int): Boolean = run != 0 && run == current

    /** The log text of an event that goes away. */
    fun ignoredText(run: Int): String = "stream event for run $run ignored (current is $current)"
}

/** The states of the bind to the camera service. */
enum class ServiceState { Unbound, Binding, Bound, Failed }

/** The events of the bind to the camera service. */
enum class ServiceEvent {
    /** `bindService` gave true. */
    BindStarted,

    /** `bindService` gave false or threw. */
    BindRefused,
    Connected,
    Disconnected,
    BindingDied,
    NullBinding,

    /** The death recipient: the camera process died. */
    BinderDied,
}

/** The transitions of [ServiceState]. */
object ServiceStates {
    fun next(state: ServiceState, event: ServiceEvent): ServiceState = when (event) {
        ServiceEvent.BindStarted -> if (state == ServiceState.Bound) state else ServiceState.Binding
        ServiceEvent.BindRefused -> ServiceState.Failed
        ServiceEvent.Connected -> ServiceState.Bound
        ServiceEvent.Disconnected -> ServiceState.Binding
        ServiceEvent.BindingDied -> ServiceState.Unbound
        ServiceEvent.NullBinding -> ServiceState.Failed
        ServiceEvent.BinderDied -> ServiceState.Binding
    }

    /** The log text of an event. */
    fun logText(event: ServiceEvent): String = when (event) {
        ServiceEvent.BindStarted -> "camera service bind started"
        ServiceEvent.BindRefused -> "bindService refused"
        ServiceEvent.Connected -> "camera service connected"
        ServiceEvent.Disconnected -> "camera service disconnected"
        ServiceEvent.BindingDied -> "camera binding died, the next use binds again"
        ServiceEvent.NullBinding -> "camera service returned a null binding"
        ServiceEvent.BinderDied -> "camera process died"
    }

    /** True when the next use must call `bindService` again. */
    fun needsBind(state: ServiceState): Boolean = state == ServiceState.Unbound || state == ServiceState.Failed
}
