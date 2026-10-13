@file:OptIn(ExperimentalCoroutinesApi::class)

package org.superdro.validation.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.ReconnectPolicy
import org.superdro.core.connection.SessionEvent
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.WantedLink
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.util.Clock

/**
 * A scripted byte link for the session tests. The test sends the adapter bytes with [send]. Each
 * write of the session goes to [writes] with its time. A [responder] can answer each write at the
 * time of the write.
 */
class ScriptTransport(
    override val address: AdapterAddress,
    private val clock: Clock,
    private val onOpen: suspend () -> Unit,
    private val responder: ((String) -> String?)?,
) : Transport {

    data class Write(val timeMs: Long, val bytes: List<Byte>) {
        val text: String get() = bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
        val isBel: Boolean get() = bytes == listOf<Byte>(0x07)
    }

    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val _writes = ArrayList<Write>()

    val writes: List<Write> get() = _writes.toList()

    /** The writes that are not the BEL byte. */
    val commands: List<Write> get() = writes.filterNot { it.isBel }

    /** The times of the BEL bytes. */
    val belTimes: List<Long> get() = writes.filter { it.isBel }.map { it.timeMs }

    var opened = false
        private set
    var closed = false
        private set
    var closedAtMs: Long? = null
        private set

    /** The next write throws this exception. */
    var writeFailure: Throwable? = null

    override val incoming: Flow<ByteArray> = flow { for (chunk in inbound) emit(chunk) }

    override suspend fun open() {
        onOpen()
        opened = true
    }

    override suspend fun write(bytes: ByteArray) {
        writeFailure?.let { throw it }
        if (closed) throw LinkFailureException(LinkFailure.Lost, "closed")
        val write = Write(clock.nowMs(), bytes.toList())
        _writes += write
        responder?.invoke(write.text)?.let { answer -> send(answer) }
    }

    override suspend fun close() {
        if (!closed) closedAtMs = clock.nowMs()
        closed = true
        inbound.close()
    }

    /** The adapter sends [text] now. */
    fun send(text: String) {
        inbound.trySend(text.encodeToByteArray())
    }

    /** The link fails with [failure]. */
    fun lose(failure: Throwable = LinkFailureException(LinkFailure.Lost, "lost")) {
        inbound.close(failure)
    }
}

/** Makes one [ScriptTransport] for each link. */
class ScriptFactory(private val clock: Clock) : TransportFactory {

    private val _transports = ArrayList<ScriptTransport>()
    private val _createdAt = ArrayList<Long>()

    val transports: List<ScriptTransport> get() = _transports.toList()

    /** The time of each `create`. */
    val createdAt: List<Long> get() = _createdAt.toList()

    /** The next opens fail with these failures, one for each open. */
    val openFailures = ArrayDeque<Throwable>()

    /** The opens wait for this gate when it is not null. */
    var openGate: CompletableDeferred<Unit>? = null

    /** The answers of the adapter to each write. */
    var responder: ((String) -> String?)? = null

    val last: ScriptTransport get() = _transports.last()

    override fun create(address: AdapterAddress): Transport {
        val transport = ScriptTransport(
            address = address,
            clock = clock,
            onOpen = {
                openGate?.await()
                openFailures.removeFirstOrNull()?.let { throw it }
            },
            responder = { text -> responder?.invoke(text) },
        )
        _transports += transport
        _createdAt += clock.nowMs()
        return transport
    }

    fun refuseNext(count: Int, failure: LinkFailure = LinkFailure.Refused) {
        repeat(count) { openFailures.addLast(LinkFailureException(failure, "refused")) }
    }
}

/**
 * A connection manager with scripted transports and virtual time. The reconnect policy has no
 * random variation, so that the times of the tests are exact.
 */
class SessionHarness(
    val scope: TestScope,
    policy: ReconnectPolicy = ReconnectPolicy(variation = 0.0),
    deviceSetups: DeviceSetupSource = DeviceSetupSource.None,
    streamRecorder: StreamRecorder = StreamRecorder.None,
    extraFactories: Map<TransportKind, TransportFactory> = emptyMap(),
) {
    val clock: Clock = Clock { scope.testScheduler.currentTime }
    val factory = ScriptFactory(clock)
    val capabilities = MutableStateFlow(CapabilityState())
    val wanted = MutableStateFlow<List<WantedLink>>(emptyList())

    val manager = ConnectionManager(
        factories = TransportKind.entries.associateWith { factory as TransportFactory } + extraFactories,
        capabilities = object : CapabilitySource {
            override val state: StateFlow<CapabilityState> = capabilities
        },
        wantedLinks = object : WantedLinkSource {
            override val wantedLinks: Flow<List<WantedLink>> = wanted
        },
        clock = clock,
        scope = scope.backgroundScope,
        linkIoDispatcher = StandardTestDispatcher(scope.testScheduler),
        reconnectPolicy = policy,
        deviceSetups = deviceSetups,
        streamRecorder = streamRecorder,
    )

    val now: Long get() = scope.testScheduler.currentTime

    /** Moves the virtual time to [timeMs] and runs all tasks up to that time. */
    fun advanceTo(timeMs: Long) {
        require(timeMs >= now) { "The time cannot go back from $now to $timeMs." }
        scope.advanceTimeBy(timeMs - now)
        scope.runCurrent()
    }

    /** Runs the tasks of the current time. */
    fun settle() = scope.runCurrent()

    fun connect(ref: DeviceRef): DeviceSession {
        val session = manager.connect(ref)
        scope.runCurrent()
        return session
    }

    /** Records each state of [session] with its time. */
    fun record(session: DeviceSession): MutableList<Pair<Long, SessionState>> {
        val list = ArrayList<Pair<Long, SessionState>>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            session.state.collect { state -> list += now to state }
        }
        return list
    }

    /** Records each event of [session] with its time. */
    fun events(session: DeviceSession): MutableList<Pair<Long, SessionEvent>> {
        val list = ArrayList<Pair<Long, SessionEvent>>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            session.events.collect { event -> list += now to event }
        }
        return list
    }

    /** Starts [block] in the background and gives its result when it ends. */
    fun <T> start(block: suspend () -> T): CompletableDeferred<T> {
        val result = CompletableDeferred<T>()
        scope.backgroundScope.launch { result.complete(block()) }
        scope.runCurrent()
        return result
    }

    companion object {
        val SPP = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")
        val USB = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "0001")
        val TCP = AdapterAddress.Tcp("192.168.4.1", 8080)
        val FAKE = AdapterAddress.Fake("script")

        fun ref(address: AdapterAddress, id: String = "mill", twoWay: Boolean = true, record: Boolean = false): DeviceRef =
            DeviceRef(DeviceId(id), "Machine $id", address, twoWayCommunication = twoWay, recordStream = record)

        /** The answers of a genuine adapter for a write text, or null. */
        fun adapterAnswer(text: String): String? = when (text) {
            "#reboot:1000;" -> "\$0aa3;"
            else -> if (text.startsWith("#clear_counter:")) "\$0aa3;" else QueueDriver.goodAdapter(text)
        }
    }
}
