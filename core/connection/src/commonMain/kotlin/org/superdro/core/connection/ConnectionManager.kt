package org.superdro.core.connection

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterDialect
import org.superdro.core.protocol.AdapterTiming
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * The owner of all device sessions: a singleton of the DI graph. The UI and the connection service
 * use the same object. The manager has no "active device" and no limit on the number of sessions.
 *
 * Each session runs in its own child scope of [scope] with a child `SupervisorJob`. An exception
 * in one session cancels only that session. The sessions continue in the background.
 *
 * @param factories The transport factory of each link type.
 * @param capabilities The capability state of the links. A session waits in
 *   `WaitingForDevice` until its capability comes back.
 * @param wantedLinks The remembered machines that are not paused. [restore] reads them.
 * @param clock The session clock: the arrival times, the time base, and the timers.
 * @param scope The scope of the manager: `SupervisorJob() + Dispatchers.Default + handler`.
 * @param linkIoDispatcher The dispatcher of the link reads and writes. Android gives the
 *   dispatcher with the high priority threads of the service.
 * @param reconnectPolicy The reconnect delays.
 * @param deviceSetups The stored setup of each device: the input configuration and the adapter
 *   identity.
 * @param streamRecorder The sink of the recorded byte streams.
 * @param timing The protocol times.
 */
class ConnectionManager(
    private val factories: Map<TransportKind, TransportFactory>,
    private val capabilities: CapabilitySource,
    private val wantedLinks: WantedLinkSource,
    private val clock: Clock = MonotonicClock(),
    private val scope: CoroutineScope = defaultScope(),
    private val linkIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val deviceSetups: DeviceSetupSource = DeviceSetupSource.None,
    private val streamRecorder: StreamRecorder = StreamRecorder.None,
    private val timing: AdapterTiming = AdapterDialect.DEFAULT_TIMING,
) {

    private val log = Arbor.tag("ConnectionManager")
    private val _sessions = MutableStateFlow<Map<DeviceId, LinkSession>>(emptyMap())

    /** The sessions, in the order of their start. A session stays until [remove]. */
    val sessions: StateFlow<Map<DeviceId, DeviceSession>> = _sessions.asStateFlow()

    /** Gives the session of [id], or null. */
    fun session(id: DeviceId): DeviceSession? = _sessions.value[id]

    private val _recording = MutableStateFlow<Set<DeviceId>>(emptySet())

    /**
     * The devices whose links the sessions record.
     * The manager keeps the record flag of each device: each new link of the device records, also
     * a link of a later [connect] with a [DeviceRef] of the default `recordStream = false` (the
     * restore, the remembered machines, a settings change). Only [setRecordStream] with false, a
     * user disconnect ([disconnect], [disconnectAll]), and [remove] stop the record.
     */
    val recording: StateFlow<Set<DeviceId>> = _recording.asStateFlow()

    /**
     * The record switch of the logs for the device [id]. A change of the flag starts
     * a new link at once when the session runs. The session does not go to `Disconnected`, so the
     * tab of the machine stays.
     */
    fun setRecordStream(id: DeviceId, on: Boolean) {
        var changed = false
        _recording.update { old ->
            val next = if (on) old + id else old - id
            changed = next != old
            next
        }
        val session = _sessions.value[id] ?: return
        val device = session.device.value
        if (changed || device.recordStream != on) session.restartLink(device.copy(recordStream = on))
    }

    /**
     * Starts or resumes the session of [device] (a user connect, or the restore). A new session
     * goes to `Connecting(1)`. A session in `Disconnected` (a paused machine) connects again. For
     * a session that runs, a new address or a new two-way switch starts a new link at once.
     */
    fun connect(device: DeviceRef): DeviceSession {
        if (device.recordStream) _recording.update { old -> old + device.id }
        val ref = device.copy(recordStream = device.id in _recording.value)
        var created: LinkSession? = null
        val map = _sessions.updateAndGet { old ->
            if (device.id in old) {
                old
            } else {
                val session = created ?: newSession(ref).also { created = it }
                old + (device.id to session)
            }
        }
        val session = map.getValue(device.id)
        if (session === created) {
            log.i { "New session ${device.id} (${device.transport})" }
            session.start()
        } else {
            session.connect(ref)
        }
        return session
    }

    /**
     * A user disconnect: the pause of a remembered machine. The session closes its link,
     * stops its reconnect loop, and goes to `Disconnected`. It stays in [sessions], and
     * [connect] resumes it. The other sessions do not change.
     */
    fun disconnect(id: DeviceId) {
        _recording.update { old -> old - id }
        _sessions.value[id]?.disconnect()
    }

    /** "Disconnect all": each session goes to `Disconnected`. */
    fun disconnectAll() {
        _recording.value = emptySet()
        _sessions.value.values.forEach { session -> session.disconnect() }
    }

    /**
     * Connects each remembered machine that is not paused, for all link types. The function reads
     * the current list of [WantedLinkSource] one time. A device that has a session that is not
     * `Disconnected` or `Failed` does not change.
     */
    fun restore(): Job = scope.launch {
        val links = wantedLinks.wantedLinks.first()
        log.i { "Restore of ${links.size} links" }
        links.forEach { link ->
            val state = _sessions.value[link.deviceId]?.state?.value
            if (state == null || state == SessionState.Disconnected || state is SessionState.Failed) connect(link.toDeviceRef())
        }
    }

    /** The user removes a remembered machine: the session stops and leaves [sessions]. */
    fun remove(id: DeviceId) {
        _recording.update { old -> old - id }
        var removed: LinkSession? = null
        _sessions.update { old ->
            removed = old[id]
            old - id
        }
        removed?.stop()
    }

    /**
     * "Restart Now" for the device [id]. With no session, or with no open link, the
     * result is [RestartResult.NoSession] and nothing goes out. Else see [DeviceSession.restartAdapter].
     */
    suspend fun restartAdapter(id: DeviceId): RestartResult =
        _sessions.value[id]?.restartAdapter() ?: RestartResult.NoSession

    /** Stops and removes all sessions. The owner of [scope] stops the scope. */
    fun close() {
        val all = _sessions.value.values
        _sessions.value = emptyMap()
        _recording.value = emptySet()
        all.forEach { session -> session.stop() }
    }

    private fun newSession(device: DeviceRef): LinkSession = LinkSession(
        initial = device,
        factories = factories,
        capabilities = capabilities,
        clock = clock,
        parentScope = scope,
        linkIo = linkIoDispatcher,
        policy = reconnectPolicy,
        setups = deviceSetups.setup(device.id),
        recorder = streamRecorder,
        timing = timing,
    )

    companion object {
        /** Gives the production scope: `SupervisorJob() + Dispatchers.Default + handler`. */
        fun defaultScope(): CoroutineScope {
            val log = Arbor.tag("ConnectionManager")
            return CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName("connections") +
                    CoroutineExceptionHandler { _, error -> log.e(error) { "A connection coroutine failed" } },
            )
        }
    }
}
