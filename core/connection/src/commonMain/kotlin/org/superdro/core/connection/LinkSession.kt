package org.superdro.core.connection

import com.toxicbakery.logging.Arbor
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField
import org.superdro.core.protocol.AdapterFieldValues
import org.superdro.core.protocol.AdapterMessage
import org.superdro.core.protocol.AdapterParser
import org.superdro.core.protocol.AdapterTiming
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.protocol.CommandQueue
import org.superdro.core.protocol.CommandResult
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.LinkPhase
import org.superdro.core.protocol.ProtocolFrame
import org.superdro.core.protocol.QueueOutput
import org.superdro.core.protocol.ValueState
import org.superdro.core.readout.input.ChannelValue
import org.superdro.core.readout.input.InputConfiguration
import org.superdro.core.readout.input.InputHistory
import org.superdro.core.readout.input.InputSnapshot
import org.superdro.core.readout.input.InputStage
import org.superdro.core.readout.input.ProbeEvent
import org.superdro.core.util.Bytes
import org.superdro.core.util.Clock

/**
 * The [DeviceSession] of the connection manager.
 *
 * All state of the session is confined to one coroutine dispatcher with a parallelism of 1. The
 * reader, the writer, the timers, and the requests of the UI run on it, so no two of them change
 * the state at the same time. The open, the writes, and the close of a link run on the link I/O
 * dispatcher. The session collects [Transport.incoming] on its own dispatcher: the transport does
 * its blocking reads on its own thread.
 *
 * One timer loop runs all timers of the session: the reconnect delay, the restart sequence, the
 * queue timers, the 30 s limits, the no-data watchdog, the BEL byte, and the tick of the input
 * stage. At one time, the loop runs them in this order: the restart close, the reconnect, the
 * 30 s limits and the watchdog, the queue, the BEL byte, and the tick. Thus the state changes
 * before a write at the same time, and a queue write at the same time as the BEL byte goes out
 * first and starts the 500 ms again.
 *
 * Details of the rules:
 * - The `NO_DATA` recovery applies to SPP and TCP. USB, BLE, and the fake link keep the
 *   30 s `NoData` limit.
 * - A capability failure from the transport while the [CapabilitySource] tells "available" goes
 *   to `WaitingForDevice` with a fallback retry after the policy delay, so that a late capability
 *   event cannot stop the session.
 * - The time offset also resets when the session enters `NoData`, so that the first valid message
 *   after `NoData` does not get the old offset.
 * - An exception of the transport that is not a [LinkFailureException] is [LinkFailure.Lost].
 *   An exception in the code of the session is a defect: the session goes to `Failed`.
 */
internal class LinkSession(
    initial: DeviceRef,
    private val factories: Map<TransportKind, TransportFactory>,
    private val capabilities: CapabilitySource,
    private val clock: Clock,
    parentScope: CoroutineScope,
    private val linkIo: CoroutineDispatcher,
    private val policy: ReconnectPolicy,
    private val setups: Flow<DeviceSetup?>,
    private val recorder: StreamRecorder,
    private val timing: AdapterTiming,
) : DeviceSession {

    override val id: DeviceId = initial.id

    private val dispatcher: CoroutineDispatcher =
        ((parentScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default)
            .limitedParallelism(1)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(
        parentScope.coroutineContext + job + dispatcher + CoroutineName("session $id") +
            CoroutineExceptionHandler { _, error -> onFatal(error) },
    )
    private val log = Arbor.tag("DeviceSession")

    private val _device = MutableStateFlow(initial)
    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    private val _inputs = MutableStateFlow(InputSnapshot.EMPTY)
    private val _probeEvents = MutableSharedFlow<ProbeEvent>(extraBufferCapacity = PROBE_BUFFER)
    private val _samples = MutableSharedFlow<ChannelValue>(
        extraBufferCapacity = DeviceSession.SAMPLE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _adapterInfo = MutableStateFlow(AdapterInfo(twoWayCommunication = initial.twoWayCommunication))
    private val _messages = MutableSharedFlow<AdapterMessage>(extraBufferCapacity = MESSAGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val device: StateFlow<DeviceRef> = _device.asStateFlow()
    override val state: StateFlow<SessionState> = _state.asStateFlow()
    override val inputs: StateFlow<InputSnapshot> = _inputs.asStateFlow()
    override val probeEvents: SharedFlow<ProbeEvent> = _probeEvents.asSharedFlow()
    override val samples: SharedFlow<ChannelValue> = _samples.asSharedFlow()
    override val adapterInfo: StateFlow<AdapterInfo> = _adapterInfo.asStateFlow()
    override val messages: SharedFlow<AdapterMessage> = _messages.asSharedFlow()
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val stage = InputStage()
    override val history: InputHistory get() = stage.history

    /** One write of the session. [bel] marks the BEL byte. */
    private class PendingWrite(val bytes: ByteArray, val bel: Boolean)

    /** The state of one open link: a new parser, a new queue, and a new time offset. */
    private class Link(val number: Int, val transport: Transport, val openedAtMs: Long, var queue: CommandQueue) {
        val parser = AdapterParser()
        val pending = HashMap<Long, CompletableDeferred<CommandResult>>()
        val writes = Channel<PendingWrite>(Channel.UNLIMITED)
        var readerJob: Job? = null
        var writerJob: Job? = null
        var sink: RecordSink? = null
        var lastValidMs: Long = openedAtMs
        var lastWriteMs: Long? = null
        var belPending = false
        var connectedSinceMs: Long? = null
        var stateEnteredMs: Long = openedAtMs
        var identityWarned = false
        var queueTimerAtMs: Long? = null
        var nextTickMs: Long = openedAtMs + INPUT_TICK_MS
    }

    private var collectorsRunning = false
    private var fatal = false
    private var wanted = false
    private var failures = 0
    private var linkCount = 0
    private var link: Link? = null
    private var connectJob: Job? = null
    private var connectingTransport: Transport? = null
    private var retryAtMs: Long? = null
    private var waitRetryAtMs: Long? = null
    private var nextAttempt = 1

    /** The number of the last `Connecting`. `Reconnecting` holds it when that attempt fails. */
    private var attempt = 1
    private var restartCloseAtMs: Long? = null
    private var restartConnectAtMs: Long? = null
    private var restartAttempt = false
    private var restartState = AdapterRestartState.Idle
    private var restartNeeded: Set<InputChannel> = emptySet()
    private var adapterRestarts = 0
    private var busy: Set<InputChannel> = emptySet()
    private var detailsOpen = false
    private var values = AdapterValues()
    private var identity: AdapterIdentity? = null
    private var identityCheck: IdentityCheck = IdentityCheck.Pending
    private var setup: DeviceSetup? = null
    private var twoWay = initial.twoWayCommunication
    private var capability: LinkCapability = LinkCapability.Available
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // The requests of the manager.

    /** Starts the session: the collectors, then the first link. */
    fun start() {
        scope.launch { resume(_device.value) }
    }

    /** A user connect or the restore with [ref]. */
    fun connect(ref: DeviceRef) {
        scope.launch { resume(ref) }
    }

    /**
     * The record switch of the logs: the session takes [ref] with its
     * new record flag, and a link that is open or opens starts again at once, so that the new link
     * opens a record sink, or opens none. A session that the user paused only keeps [ref].
     */
    fun restartLink(ref: DeviceRef) {
        scope.launch { relink(ref) }
    }

    /** A user disconnect: the pause of the machine. */
    fun disconnect() {
        scope.launch { userDisconnect() }
    }

    /** Stops the session for good. */
    fun stop() {
        scope.launch {
            userDisconnect()
            job.cancel()
        }
    }

    // DeviceSession

    override suspend fun send(command: AdapterCommand): CommandResult =
        withContext(dispatcher) { submit(command) }.await()

    override suspend fun restartAdapter(): RestartResult {
        val connected = withContext(dispatcher) { link != null && _state.value is SessionState.Connected }
        if (!connected) return RestartResult.NoSession
        return when (val result = send(AdapterCommand.Reboot)) {
            CommandResult.Completed -> RestartResult.Accepted
            else -> RestartResult.NotAccepted(result)
        }
    }

    override fun openAdapterDetails() {
        scope.launch {
            detailsOpen = true
            link?.let { current -> handle(current, current.queue.openAdapterDetails(clock.nowMs())) }
            publishInfo()
        }
    }

    override fun closeAdapterDetails() {
        scope.launch {
            detailsOpen = false
            link?.queue?.closeAdapterDetails()
            publishInfo()
        }
    }

    override suspend fun <T> readHistory(block: (InputHistory) -> T): T = withContext(dispatcher) { block(stage.history) }

    // The life cycle.

    private fun startCollectors() {
        if (collectorsRunning) return
        collectorsRunning = true
        scope.launch { setups.distinctUntilChanged().collect { value -> applySetup(value) } }
        scope.launch {
            combine(_device, capabilities.state) { ref, caps -> caps.capabilityOf(ref.address) }
                .distinctUntilChanged()
                .collect { value -> onCapability(value) }
        }
        scope.launch { timerLoop() }
    }

    private fun resume(ref: DeviceRef) {
        val old = _device.value
        _device.value = ref
        if (fatal) {
            fatal = false
            collectorsRunning = false
        }
        startCollectors()
        val current = _state.value
        if (!wanted || current == SessionState.Disconnected || current is SessionState.Failed) {
            wanted = true
            failures = 0
            twoWay = computeTwoWay()
            scope.launch { if (wanted && link == null && connectJob == null) startLink(1) }
            return
        }
        if (old.address != ref.address) {
            log.i { "$id: new address ${ref.address}" }
            dropLink()
            failures = 0
            startLink(1)
            return
        }
        applyTwoWay()
        if (current is SessionState.WaitingForDevice) {
            // A user connect checks the capability again: the link opens when it is available.
            log.i { "$id: connect while waiting for ${current.cause}" }
            failures = 0
            startLink(1)
        }
    }

    private fun relink(ref: DeviceRef) {
        _device.value = ref
        val current = _state.value
        if (!wanted || current == SessionState.Disconnected || current is SessionState.Failed) return
        log.i { "$id: new link for the record switch (record ${ref.recordStream})" }
        dropLink()
        failures = 0
        startLink(1)
    }

    private fun userDisconnect() {
        wanted = false
        dropLink()
        retryAtMs = null
        waitRetryAtMs = null
        restartCloseAtMs = null
        restartConnectAtMs = null
        restartAttempt = false
        restartState = AdapterRestartState.Idle
        failures = 0
        setState(SessionState.Disconnected)
        publishInfo()
    }

    /** Stops the open or the link that runs. This is not a failure. */
    private fun dropLink() {
        connectJob?.cancel()
        connectJob = null
        connectingTransport?.let(::closeTransport)
        connectingTransport = null
        link?.let { current ->
            leaveConnected(clock.nowMs())
            closeLink(current)
        }
    }

    // The open of a link.

    private fun startLink(attempt: Int) {
        retryAtMs = null
        waitRetryAtMs = null
        if (!wanted) return
        val ref = _device.value
        val cap = capabilities.state.value.capabilityOf(ref.address)
        capability = cap
        if (cap is LinkCapability.Unavailable && cap.cause.waitsForCapability) {
            waitForDevice(cap.cause, fallback = false)
            return
        }
        val factory = factories[ref.address.kind]
        if (factory == null) {
            log.e { "$id: no transport factory for ${ref.address.kind}" }
            setState(SessionState.Failed(LinkFailure.Refused))
            return
        }
        this.attempt = attempt
        setState(SessionState.Connecting(attempt))
        identity = null
        identityCheck = IdentityCheck.Pending
        publishInfo()
        val transport = try {
            factory.create(ref.address)
        } catch (error: Exception) {
            log.w(error) { "$id: the factory cannot make a transport" }
            onOpenFailed(null, LinkFailure.Refused)
            return
        }
        connectingTransport = transport
        connectJob = scope.launch {
            val failure: LinkFailure? = try {
                withContext(linkIo) { transport.open() }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkFailureException) {
                error.failure
            } catch (error: Exception) {
                log.w(error) { "$id: the open failed" }
                LinkFailure.Refused
            }
            connectJob = null
            connectingTransport = null
            if (failure != null) onOpenFailed(transport, failure) else onOpened(transport)
        }
    }

    private fun onOpenFailed(transport: Transport?, failure: LinkFailure) {
        transport?.let(::closeTransport)
        log.i { "$id: the open failed: $failure" }
        if (restartAttempt) {
            // The connect 2000 ms after the ack of #reboot failed.
            restartAttempt = false
            restartState = AdapterRestartState.Failed
            emitEvent(SessionEvent.RestartFailed(clock.nowMs()))
            publishInfo()
        }
        onFailure(failure)
    }

    private fun onOpened(transport: Transport) {
        if (!wanted || _state.value !is SessionState.Connecting) {
            closeTransport(transport)
            return
        }
        val now = clock.nowMs()
        linkCount += 1
        val current = Link(linkCount, transport, now, CommandQueue(twoWay, timing, values))
        link = current
        if (restartAttempt) {
            restartAttempt = false
            restartNeeded = emptySet()
        }
        // A link that opens ends the restart sequence, also after a failed connect.
        restartState = AdapterRestartState.Idle
        val ref = _device.value
        current.sink = if (ref.recordStream) recorder.open(id, ref.address, current.number) else null
        stage.onLinkStart()
        _inputs.value = stage.snapshot
        setState(SessionState.AwaitingData)
        if (detailsOpen) handle(current, current.queue.openAdapterDetails(now))
        updateIdentity(current)
        publishInfo()
        log.i { "$id: link ${current.number} open" }
        current.writerJob = scope.launch { writer(current) }
        current.readerJob = scope.launch { reader(current) }
        poke()
    }

    // The failures and the reconnect policy.

    private fun onLinkLost(current: Link, failure: LinkFailure) {
        if (link !== current) return
        log.i { "$id: link ${current.number} lost: $failure" }
        leaveConnected(clock.nowMs())
        closeLink(current)
        onFailure(failure)
    }

    private fun onFailure(failure: LinkFailure) {
        if (!wanted) {
            setState(SessionState.Disconnected)
            return
        }
        val now = clock.nowMs()
        val restartAt = restartConnectAtMs
        if (restartAt != null) {
            // The restart sequence connects again 2000 ms after the ack, not after the policy delay.
            restartCloseAtMs = null
            nextAttempt = attempt + 1
            retryAtMs = maxOf(restartAt, now)
            setState(SessionState.Reconnecting(attempt, retryAtMs!!, failure))
            poke()
            return
        }
        if (failure.waitsForCapability) {
            val known = capability as? LinkCapability.Unavailable
            waitForDevice(failure, fallback = known == null)
            return
        }
        val unavailable = capability as? LinkCapability.Unavailable
        if (unavailable != null && unavailable.cause.waitsForCapability) {
            waitForDevice(unavailable.cause, fallback = false)
            return
        }
        when (val decision = policy.decide(failure, failures)) {
            is ReconnectDecision.Retry -> {
                // Reconnecting holds the attempt that failed. The next
                // state is Connecting(attempt + 1). The policy delay comes from the count of failures.
                failures = decision.failures
                nextAttempt = attempt + 1
                val at = now + decision.delayMs
                retryAtMs = at
                setState(SessionState.Reconnecting(attempt, at, failure))
            }
            is ReconnectDecision.WaitForCapability -> waitForDevice(decision.cause, fallback = false)
        }
        poke()
    }

    private fun waitForDevice(cause: LinkFailure, fallback: Boolean) {
        dropLink()
        retryAtMs = null
        waitRetryAtMs = if (fallback) {
            failures += 1
            clock.nowMs() + policy.delayMs(failures)
        } else {
            null
        }
        setState(SessionState.WaitingForDevice(cause))
        poke()
    }

    private fun onCapability(value: LinkCapability) {
        capability = value
        val current = _state.value
        when (value) {
            is LinkCapability.Unavailable -> {
                val active = current !is SessionState.WaitingForDevice && current != SessionState.Disconnected && current !is SessionState.Failed
                if (wanted && active && value.cause.waitsForCapability) {
                    log.i { "$id: capability lost: ${value.cause}" }
                    restartCloseAtMs = null
                    restartConnectAtMs = null
                    waitForDevice(value.cause, fallback = false)
                } else if (wanted && current is SessionState.WaitingForDevice) {
                    // For example DeviceGone, then PermissionMissing after an attach with no permission.
                    log.i { "$id: capability changed while waiting: ${value.cause}" }
                    if (value.cause.waitsForCapability) setState(SessionState.WaitingForDevice(value.cause))
                }
            }
            LinkCapability.Available -> if (wanted && current is SessionState.WaitingForDevice) {
                log.i { "$id: capability back" }
                failures = 0
                startLink(1)
            }
        }
    }

    // The link: read, write, close.

    private suspend fun reader(current: Link) {
        try {
            // No flowOn here: each transport does its blocking reads on its own thread. A flowOn
            // adds a second thread change and buffer for each chunk. Also, when the flow fails,
            // flowOn drops the chunks in its buffer, so the parser does not get the last bytes.
            current.transport.incoming
                .catch { error ->
                    throw when (error) {
                        is CancellationException, is LinkFailureException -> error
                        else -> LinkFailureException(LinkFailure.Lost, error.message, error)
                    }
                }
                .collect { bytes -> onChunk(current, bytes) }
            onLinkLost(current, LinkFailure.Lost)
        } catch (error: LinkFailureException) {
            onLinkLost(current, error.failure)
        }
    }

    private suspend fun writer(current: Link) {
        for (write in current.writes) {
            if (!twoWay) continue
            try {
                withContext(linkIo) { current.transport.write(write.bytes) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LinkFailureException) {
                onLinkLost(current, error.failure)
                return
            } catch (error: Exception) {
                log.w(error) { "$id: the write failed" }
                onLinkLost(current, LinkFailure.Lost)
                return
            }
            // The 500 ms of the BEL byte count from the end of a write that waited.
            val end = clock.nowMs()
            current.lastWriteMs = maxOf(current.lastWriteMs ?: end, end)
            if (write.bel) current.belPending = false
            poke()
        }
    }

    private fun closeLink(current: Link) {
        if (link === current) link = null
        val now = clock.nowMs()
        handle(current, current.queue.onLinkPhase(LinkPhase.Dropped, now))
        current.pending.values.forEach { result -> result.complete(CommandResult.Cancelled) }
        current.pending.clear()
        current.writes.close()
        current.readerJob?.cancel()
        current.writerJob?.cancel()
        current.sink?.close()
        current.sink = null
        closeTransport(current.transport)
        publishInfo()
    }

    private fun closeTransport(transport: Transport) {
        CoroutineScope(linkIo + NonCancellable).launch {
            try {
                transport.close()
            } catch (error: Exception) {
                log.w(error) { "$id: the close failed" }
            }
        }
    }

    private fun enqueueWrite(current: Link, bytes: ByteArray, bel: Boolean) {
        // No write with two-way off, and writes only in Connected.
        if (!twoWay || _state.value !is SessionState.Connected) {
            log.w { "$id: a write outside Connected was dropped" }
            return
        }
        current.lastWriteMs = clock.nowMs()
        if (bel) current.belPending = true
        current.writes.trySend(PendingWrite(bytes, bel))
    }

    // The inbound data.

    private suspend fun onChunk(current: Link, bytes: ByteArray) {
        val now = clock.nowMs()
        current.sink?.write(RecordedChunk(now - current.openedAtMs, bytes.copyOf()))
        // Each segment ends at `;`, so a state change in a message applies before the next message.
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == END_BYTE) {
                feed(current, bytes, start, index + 1 - start, now)
                start = index + 1
                if (link !== current) return
            }
        }
        if (start < bytes.size) feed(current, bytes, start, bytes.size - start, now)
        if (link === current) _inputs.value = stage.snapshot
    }

    private suspend fun feed(current: Link, bytes: ByteArray, offset: Int, length: Int, now: Long) {
        for (message in current.parser.feed(bytes, offset, length, now)) {
            if (link !== current) return
            onMessage(current, message, now)
        }
    }

    private suspend fun onMessage(current: Link, message: AdapterMessage, now: Long) {
        if (message.isValid) {
            current.lastValidMs = now
            when (_state.value) {
                SessionState.AwaitingData -> enterConnected(current, now)
                SessionState.NoData -> {
                    // The time offset resets at each change from NoData to Connected.
                    current.parser.resetTime()
                    enterConnected(current, now)
                }
                else -> Unit
            }
        }
        when (message) {
            is AdapterMessage.AxisCount -> when {
                message.isDirtyStatus -> log.v { "$id: dirty status ${message.count}" }
                message.carriesValue -> {
                    val events = stage.onAxisCount(message.letter, message.count.toLong(), message.timeMs)
                    stage.acceptedSample?.let { sample -> _samples.tryEmit(sample) }
                    emitProbes(events)
                }
                else -> Unit // A to F, R, and S carry no value.
            }
            is AdapterMessage.BinaryInput -> emitProbes(stage.onBinaryInput(message.letter, message.value, message.timeMs))
            is AdapterMessage.Timestamp, is AdapterMessage.Unknown -> Unit
            is AdapterMessage.Status, is AdapterMessage.Error, is AdapterMessage.Info, is AdapterMessage.Signature -> {
                _messages.tryEmit(message)
                handle(current, current.queue.onMessage(message, now))
            }
        }
    }

    private fun enterConnected(current: Link, now: Long) {
        current.connectedSinceMs = now
        setState(SessionState.Connected(now))
        handle(current, current.queue.onLinkPhase(LinkPhase.Connected, now))
        poke()
    }

    private fun leaveConnected(now: Long) {
        val since = link?.connectedSinceMs
        if (_state.value is SessionState.Connected && since != null) {
            failures = policy.failuresAfterConnected(failures, now - since)
            // After 10 s in Connected, a new series of attempts starts: this link counts as attempt 1.
            if (failures == 0 && now - since >= policy.resetAfterConnectedMs) attempt = 1
        }
    }

    private fun enterNoData(current: Link, now: Long) {
        leaveConnected(now)
        current.connectedSinceMs = null
        current.stateEnteredMs = now
        // The offset is old now. A reset here also gives the first valid message after NoData its
        // own arrival time; the change to Connected resets the offset again.
        current.parser.resetTime()
        setState(SessionState.NoData)
        handle(current, current.queue.onLinkPhase(LinkPhase.Waiting, now))
        if (_device.value.address.kind in NO_DATA_RECOVERY_KINDS) {
            // Close the link at NoData and open it again after 750 ms.
            log.i { "$id: no data, recovery in ${timing.noDataRecoveryMs} ms" }
            closeLink(current)
            nextAttempt = attempt + 1
            val at = now + timing.noDataRecoveryMs
            retryAtMs = at
            setState(SessionState.Reconnecting(attempt, at, LinkFailure.Lost))
        }
        poke()
    }

    private suspend fun emitProbes(events: List<ProbeEvent>) {
        events.forEach { event -> _probeEvents.emit(event) }
    }

    // The command queue.

    private fun submit(command: AdapterCommand): CompletableDeferred<CommandResult> {
        val result = CompletableDeferred<CommandResult>()
        if (!twoWay || (command.isConfiguration && setup?.machine?.adapterConfigurationCommands == false)) {
            // A configuration command needs the profile switch "Adapter configuration commands".
            // With no stored profile, the switch does not apply.
            result.complete(CommandResult.NotSent)
            return result
        }
        val current = link
        if (current == null) {
            result.complete(CommandResult.Cancelled)
            return result
        }
        val submission = current.queue.submit(command, clock.nowMs())
        current.pending[submission.requestId] = result
        handle(current, submission.outputs)
        return result
    }

    private fun handle(current: Link, outputs: List<QueueOutput>) {
        var restarted = false
        for (output in outputs) {
            when (output) {
                is QueueOutput.Write -> enqueueWrite(current, output.bytes, bel = false)
                is QueueOutput.Timer -> Unit
                is QueueOutput.Result -> current.pending.remove(output.requestId)?.complete(output.result)
                is QueueOutput.CommandFailed -> emitEvent(SessionEvent.CommandFailed(output.command, output.failure))
                is QueueOutput.RestartNeeded -> {
                    restartNeeded = restartNeeded + output.channel
                    emitEvent(SessionEvent.RestartNeeded(output.channel))
                }
                is QueueOutput.Reference -> emitEvent(SessionEvent.Reference(output.channel, output.state))
                is QueueOutput.DirtyFlags -> emitEvent(SessionEvent.DirtyFlags(output.mask, output.channels))
                is QueueOutput.RestartAdapter -> {
                    log.i { "$id: the adapter restarts" }
                    restartCloseAtMs = output.closeAtMs
                    restartConnectAtMs = output.connectAtMs
                    restartState = AdapterRestartState.Restarting
                }
                is QueueOutput.AdapterRestartDetected -> {
                    adapterRestarts += 1
                    restartNeeded = emptySet()
                    restarted = true
                    emitEvent(SessionEvent.AdapterRestarted(output.oldUptime, output.newUptime))
                }
                is QueueOutput.ValuesChanged -> values = output.values
                is QueueOutput.BusyChannels -> busy = output.channels
                is QueueOutput.Log -> log.d { "$id: ${output.text}" }
            }
        }
        current.queueTimerAtMs = current.queue.nextTimerAtMs
        if (link === current) updateIdentity(current)
        publishInfo()
        poke()
        if (restarted && link === current) queryAfterRestart(current)
    }

    /**
     * The values that are not constant are empty after an adapter restart. The session
     * queries again the values of the start queries that it needs: the feature mask and the six
     * modes. An open Adapter Details screen queries its group again.
     */
    private fun queryAfterRestart(current: Link) {
        val now = clock.nowMs()
        val queue = current.queue
        val known = queue.queuedCommands + listOfNotNull(queue.currentCommand)
        val commands = buildList {
            if (values.features.state == ValueState.Empty) add(AdapterCommand.GetField(AdapterField.Features))
            InputChannel.entries.forEach { channel ->
                if (values.modes.getValue(channel).state == ValueState.Empty) add(AdapterCommand.GetMode(channel))
            }
        }.filter { command -> command !in known }
        commands.forEach { command -> handle(current, queue.submit(command, now).outputs) }
        if (detailsOpen) handle(current, queue.openAdapterDetails(now))
    }

    // The setup and the two-way switch.

    private fun applySetup(value: DeviceSetup?) {
        setup = value
        val configuration = value?.let { InputConfiguration.from(it.machine, it.adapter) } ?: InputConfiguration()
        if (configuration != stage.configuration) {
            stage.reconfigure(configuration)
            _inputs.value = stage.snapshot
        }
        applyTwoWay()
        updateIdentity(link)
        publishInfo()
    }

    private fun computeTwoWay(): Boolean =
        _device.value.twoWayCommunication && (setup?.machine?.twoWayCommunication ?: true)

    private fun applyTwoWay() {
        val newValue = computeTwoWay()
        if (newValue == twoWay) return
        twoWay = newValue
        log.i { "$id: two-way communication ${if (newValue) "on" else "off"}" }
        val current = link
        if (current != null) {
            val now = clock.nowMs()
            handle(current, current.queue.onLinkPhase(LinkPhase.Dropped, now))
            current.pending.values.forEach { result -> result.complete(CommandResult.Cancelled) }
            current.pending.clear()
            current.belPending = false
            current.queue = CommandQueue(twoWay, timing, values)
            if (detailsOpen) handle(current, current.queue.openAdapterDetails(now))
            if (_state.value is SessionState.Connected) handle(current, current.queue.onLinkPhase(LinkPhase.Connected, now))
        }
        publishInfo()
        poke()
    }

    // The identity.

    private fun updateIdentity(current: Link?) {
        if (current != null) {
            val address = _device.value.address
            val linkStart = current.openedAtMs
            val mac = values.mac.takeIf { reportedSince(it.state, linkStart) }?.value?.let(AdapterFieldValues::macPlusTwo)
            val uuid = values.uuid.takeIf { reportedSince(it.state, linkStart) }?.value
            identity = when {
                address is AdapterAddress.BluetoothSpp && address.mac.isNotBlank() ->
                    AdapterIdentity(address.mac.lowercase(), IdentitySource.BtMac)
                mac != null -> AdapterIdentity(mac, IdentitySource.BtMac)
                uuid != null && values.mac.state !is ValueState.Fetching -> AdapterIdentity(uuid, IdentitySource.AdapterReported)
                // A query again does not remove the identity of this link.
                else -> identity
            }
            val actual = identity
            val expected = setup?.adapter?.identity?.takeIf { it.isNotBlank() }
            identityCheck = when {
                actual == null -> IdentityCheck.Pending
                expected == null -> IdentityCheck.NotStored
                actual.matches(expected) || expected.equals(mac, ignoreCase = true) || expected.equals(uuid, ignoreCase = true) ->
                    IdentityCheck.Confirmed
                else -> IdentityCheck.Differs(expected, actual)
            }
            val check = identityCheck
            if (check is IdentityCheck.Differs && !current.identityWarned) {
                current.identityWarned = true
                log.w { "$id: the adapter ${check.actual.value} is not the adapter ${check.expected} of the tab" }
                emitEvent(SessionEvent.IdentityDiffers(check.expected, check.actual))
            }
        }
    }

    private fun reportedSince(state: ValueState, timeMs: Long): Boolean = state is ValueState.Reported && state.atMs >= timeMs

    // The timers.

    private fun poke() {
        wake.trySend(Unit)
    }

    private suspend fun timerLoop() {
        var lastRun = Long.MIN_VALUE
        var runsAtSameTime = 0
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMs()
            val next = nextDueMs()
            if (next != null && next <= now) {
                runsAtSameTime = if (now == lastRun) runsAtSameTime + 1 else 0
                lastRun = now
                runDue(now)
                // A guard against a timer that does not move: wait 1 ms.
                if (runsAtSameTime > MAX_RUNS_AT_ONE_TIME) delay(1L)
                continue
            }
            if (next == null) wake.receive() else withTimeoutOrNull(next - now) { wake.receive() }
        }
    }

    private fun nextDueMs(): Long? {
        var next: Long? = null
        fun consider(value: Long?) {
            if (value != null && (next == null || value < next!!)) next = value
        }
        consider(restartCloseAtMs)
        val current = _state.value
        if (current is SessionState.Reconnecting) consider(retryAtMs)
        if (current is SessionState.WaitingForDevice) consider(waitRetryAtMs)
        val open = link ?: return next
        consider(open.queueTimerAtMs)
        when (current) {
            SessionState.AwaitingData -> consider(open.stateEnteredMs + timing.stateLimitMs)
            is SessionState.Connected -> {
                consider(open.lastValidMs + timing.noDataMs)
                consider(belDueMs(open))
            }
            SessionState.NoData -> consider(open.stateEnteredMs + timing.stateLimitMs)
            else -> Unit
        }
        consider(open.nextTickMs)
        return next
    }

    private fun belDueMs(current: Link): Long? {
        if (!twoWay || current.belPending) return null
        val since = current.connectedSinceMs ?: return null
        return maxOf(current.lastWriteMs ?: since, since) + timing.belPeriodMs
    }

    private suspend fun runDue(now: Long) {
        restartCloseAtMs?.let { at ->
            if (at <= now) {
                restartCloseAtMs = null
                restartClose(now)
            }
        }
        val current = _state.value
        if (current is SessionState.Reconnecting) {
            val at = retryAtMs
            if (at != null && at <= now) {
                if (restartConnectAtMs != null) {
                    restartConnectAtMs = null
                    restartAttempt = true
                }
                startLink(nextAttempt)
            }
        }
        if (current is SessionState.WaitingForDevice) {
            val at = waitRetryAtMs
            if (at != null && at <= now) startLink(attempt + 1)
        }
        val open = link ?: return
        when (_state.value) {
            SessionState.AwaitingData -> if (now >= open.stateEnteredMs + timing.stateLimitMs) {
                log.i { "$id: no valid message in ${timing.stateLimitMs} ms" }
                onLinkLost(open, LinkFailure.Lost)
                return
            }
            is SessionState.Connected -> if (now >= open.lastValidMs + timing.noDataMs) enterNoData(open, now)
            SessionState.NoData -> if (now >= open.stateEnteredMs + timing.stateLimitMs) {
                log.i { "$id: no data for ${timing.stateLimitMs} ms" }
                onLinkLost(open, LinkFailure.Lost)
                return
            }
            else -> Unit
        }
        if (link !== open) return
        open.queueTimerAtMs?.let { at ->
            if (at <= now) {
                open.queueTimerAtMs = null
                handle(open, open.queue.onTimer(now))
            }
        }
        if (link !== open) return
        belDueMs(open)?.let { at -> if (at <= now) enqueueWrite(open, BEL_BYTES, bel = true) }
        if (open.nextTickMs <= now) {
            open.nextTickMs = now + INPUT_TICK_MS
            emitProbes(stage.onTick(now))
            if (link === open) _inputs.value = stage.snapshot
        }
    }

    /** The restart: close the link 250 ms after the ack. The connect comes 2000 ms after the ack. */
    private fun restartClose(now: Long) {
        val connectAt = restartConnectAtMs ?: return
        link?.let { current ->
            leaveConnected(now)
            closeLink(current)
        }
        nextAttempt = attempt + 1
        retryAtMs = connectAt
        setState(SessionState.Reconnecting(attempt, connectAt, LinkFailure.Lost))
        poke()
    }

    // The output flows.

    private fun setState(value: SessionState) {
        if (_state.value != value) log.d { "$id: $value" }
        _state.value = value
    }

    private fun emitEvent(event: SessionEvent) {
        _events.tryEmit(event)
    }

    private fun publishInfo() {
        _adapterInfo.value = AdapterInfo(
            values = values,
            twoWayCommunication = twoWay,
            identity = identity,
            identityCheck = identityCheck,
            busyChannels = busy,
            restartNeeded = restartNeeded,
            restart = restartState,
            adapterRestarts = adapterRestarts,
            detailsOpen = detailsOpen,
            queueActive = link?.queue?.isActive == true,
        )
    }

    /** An exception in the code of the session stops only this session. */
    private fun onFatal(error: Throwable) {
        log.e(error) { "$id: the session stopped" }
        fatal = true
        collectorsRunning = false
        job.cancelChildren()
        connectJob = null
        connectingTransport?.let(::closeTransport)
        connectingTransport = null
        link?.let { current ->
            link = null
            current.pending.values.forEach { result -> result.complete(CommandResult.Cancelled) }
            current.pending.clear()
            current.writes.close()
            runCatching { current.sink?.close() }
            closeTransport(current.transport)
        }
        retryAtMs = null
        waitRetryAtMs = null
        restartCloseAtMs = null
        restartConnectAtMs = null
        setState(SessionState.Failed(LinkFailure.Lost))
        publishInfo()
    }

    /** True for `#set_mode`, `#ref_axis`, `#clear_counter`, and `#reboot`. */
    private val AdapterCommand.isConfiguration: Boolean
        get() = this is AdapterCommand.SetMode || this is AdapterCommand.RefAxis ||
            this is AdapterCommand.ClearCounter || this == AdapterCommand.Reboot

    companion object {
        /** The tick of the input stage while a link is open: the probe watchdog and the latch limit. */
        const val INPUT_TICK_MS: Long = 100L

        /** The link types with the `NO_DATA` recovery. */
        val NO_DATA_RECOVERY_KINDS: Set<TransportKind> = setOf(TransportKind.BluetoothSpp, TransportKind.Tcp)

        private const val PROBE_BUFFER = 1024
        private const val MESSAGE_BUFFER = 256
        private const val EVENT_BUFFER = 64
        private const val MAX_RUNS_AT_ONE_TIME = 1000
        private val END_BYTE: Byte = ProtocolFrame.END.code.toByte()
        private val BEL_BYTES: ByteArray = byteArrayOf(Bytes.BEL)
    }
}
