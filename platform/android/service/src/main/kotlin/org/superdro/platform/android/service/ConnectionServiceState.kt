package org.superdro.platform.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.superdro.core.connection.KeepAliveStatus

/** The life phase of [ConnectionService] in this process. */
enum class ServicePhase {
    /** No instance runs. */
    Stopped,

    /** The keep-alive asked Android for a start. The instance did not start yet. */
    Starting,

    /** An instance runs in the foreground. */
    Running,

    /** The instance called `stopSelf()`. Android did not destroy it yet. */
    Stopping,
}

/**
 * The state of [ConnectionService] that the service and [AndroidConnectionKeepAlive] share: one DI
 * singleton for each process. The service and the presenters share the connection manager, and the
 * UI does not bind to the service. Thus this object is the only
 * link between the keep-alive and the service.
 *
 * Each service instance gets a token in [onCreated]. Only the current instance can change the
 * phase. Thus an old instance that Android destroys after a new start cannot set [ServicePhase.Stopped].
 */
class ConnectionServiceState {

    private val lock = Any()
    private var token = 0
    private val mutablePhase = MutableStateFlow(ServicePhase.Stopped)
    private val mutableStatus = MutableStateFlow(KeepAliveStatus.Idle)

    /** The phase of the service. */
    val phase: StateFlow<ServicePhase> = mutablePhase.asStateFlow()

    /** The state for the tab warning ([org.superdro.core.connection.ConnectionKeepAlive.status]). */
    val status: StateFlow<KeepAliveStatus> = mutableStatus.asStateFlow()

    /** True when an instance runs or starts, so that a new summary needs no start. */
    val isActive: Boolean get() = phase.value == ServicePhase.Running || phase.value == ServicePhase.Starting

    /** The keep-alive asks Android for a start. */
    fun onStartRequested() = synchronized(lock) {
        mutablePhase.value = ServicePhase.Starting
    }

    /** Android refused the start or the call of `startForeground`. */
    fun onStartRefused(status: KeepAliveStatus) = synchronized(lock) {
        mutablePhase.value = ServicePhase.Stopped
        mutableStatus.value = status
    }

    /** The keep-alive did not start the service, because the prerequisite is not met. */
    fun onPrerequisiteMissing() = synchronized(lock) {
        if (!isActive) mutableStatus.value = KeepAliveStatus.PrerequisiteMissing
    }

    /** No session wants a link, and no instance runs. */
    fun onIdle() = synchronized(lock) {
        if (phase.value == ServicePhase.Stopped) mutableStatus.value = KeepAliveStatus.Idle
    }

    /** A new instance starts. Gives the token of the instance. */
    fun onCreated(): Int = synchronized(lock) {
        token += 1
        mutablePhase.value = ServicePhase.Running
        token
    }

    /** The instance with [instanceToken] is in the foreground. */
    fun onForeground(instanceToken: Int) = synchronized(lock) {
        if (instanceToken == token) {
            mutablePhase.value = ServicePhase.Running
            mutableStatus.value = KeepAliveStatus.Running
        }
    }

    /** The instance with [instanceToken] called `stopSelf()`. */
    fun onStopping(instanceToken: Int) = synchronized(lock) {
        if (instanceToken == token && mutablePhase.value == ServicePhase.Running) mutablePhase.value = ServicePhase.Stopping
    }

    /** Android destroyed the instance with [instanceToken]. */
    fun onDestroyed(instanceToken: Int) = synchronized(lock) {
        if (instanceToken == token && mutablePhase.value != ServicePhase.Starting) {
            mutablePhase.value = ServicePhase.Stopped
            if (mutableStatus.value == KeepAliveStatus.Running) mutableStatus.value = KeepAliveStatus.Idle
        }
    }
}
