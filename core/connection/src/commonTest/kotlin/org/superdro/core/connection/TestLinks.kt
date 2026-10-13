package org.superdro.core.connection

import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterDialect
import org.superdro.core.util.Clock

/** The BEL byte as text. */
const val BEL: String = "\u0007"

/** A random source that always gives [value]. 0.5 gives the base delays exactly. */
class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextDouble(): Double = value
}

/**
 * A test transport. The test pushes the adapter bytes with [receive]. Each write goes to
 * [writes] with its time. A [responder] can give the answers of the adapter at the time of the
 * write.
 */
class TestLink(
    override val address: AdapterAddress,
    private val clock: Clock,
    var responder: ((String) -> List<String>)? = null,
) : Transport {

    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)

    /** The writes: the time and the text. */
    val writes = ArrayList<Pair<Long, String>>()

    /** The open throws this failure, or null for a good open. */
    var openFailure: LinkFailure? = null

    /** A write waits for this gate, or null for no wait. */
    var writeGate: CompletableDeferred<Unit>? = null

    /** A write throws this failure, or null for a good write. */
    var writeFailure: LinkFailure? = null

    var opened = false
        private set
    var closed = false
        private set
    var collections = 0
        private set

    override val incoming: Flow<ByteArray> = flow {
        collections += 1
        for (bytes in inbound) emit(bytes)
    }

    /** The adapter sends [text]. */
    fun receive(text: String) {
        inbound.trySend(text.encodeToByteArray())
    }

    /** The adapter sends [bytes]. */
    fun receive(bytes: ByteArray) {
        inbound.trySend(bytes)
    }

    /** The link fails with [failure]. */
    fun lose(failure: LinkFailure = LinkFailure.Lost) {
        inbound.close(LinkFailureException(failure))
    }

    /** The writes that are not the BEL byte. */
    val commands: List<Pair<Long, String>> get() = writes.filter { (_, text) -> text != BEL }

    /** The times of the BEL bytes. */
    val belTimes: List<Long> get() = writes.filter { (_, text) -> text == BEL }.map { (time, _) -> time }

    override suspend fun open() {
        openFailure?.let { failure -> throw LinkFailureException(failure) }
        opened = true
    }

    override suspend fun write(bytes: ByteArray) {
        writeGate?.await()
        writeFailure?.let { failure -> throw LinkFailureException(failure) }
        if (closed) throw LinkFailureException(LinkFailure.Lost)
        val text = bytes.decodeToString()
        writes += clock.nowMs() to text
        responder?.invoke(text)?.forEach(::receive)
    }

    override suspend fun close() {
        closed = true
        inbound.close()
    }
}

/** A factory of [TestLink] objects for all link types. */
class TestLinks(private val clock: Clock) : TransportFactory {

    /** The links in the order of their creation. */
    val links = ArrayList<TestLink>()

    /** The open failure of each next link, in order. An empty list gives good opens. */
    val openFailures = ArrayDeque<LinkFailure?>()

    /** The responder of each new link. */
    var responder: ((String) -> List<String>)? = null

    override fun create(address: AdapterAddress): Transport =
        TestLink(address, clock, responder).also { link ->
            link.openFailure = openFailures.removeFirstOrNull()
            links += link
        }

    /** The links to [address]. */
    fun to(address: AdapterAddress): List<TestLink> = links.filter { link -> link.address == address }

    val last: TestLink get() = links.last()
}

/** A capability source that the test changes. */
class TestCapabilities(initial: CapabilityState = CapabilityState()) : CapabilitySource {
    override val state: MutableStateFlow<CapabilityState> = MutableStateFlow(initial)
}

/** A wanted link source that the test changes. */
class TestWantedLinks(initial: List<WantedLink> = emptyList()) : WantedLinkSource {
    override val wantedLinks: MutableStateFlow<List<WantedLink>> = MutableStateFlow(initial)
}

/** A setup source with one flow for each device. */
class TestSetups : DeviceSetupSource {
    private val flows = HashMap<DeviceId, MutableStateFlow<DeviceSetup?>>()

    fun flow(id: DeviceId): MutableStateFlow<DeviceSetup?> = flows.getOrPut(id) { MutableStateFlow(null) }

    operator fun set(id: DeviceId, value: DeviceSetup?) {
        flow(id).value = value
    }

    override fun setup(id: DeviceId): Flow<DeviceSetup?> = flow(id)
}

/** A record sink in memory. */
class MemorySink : RecordSink {
    val chunks = ArrayList<RecordedChunk>()
    var closed = false

    override fun write(chunk: RecordedChunk) {
        chunks += chunk
    }

    override fun close() {
        closed = true
    }
}

/** The answers of a two-way adapter. */
fun twoWayAdapter(ack: Boolean = true): (String) -> List<String> = { text ->
    val acked = if (ack) listOf("\$0aa3;") else emptyList()
    when (text) {
        BEL -> emptyList()
        "\"#get:mac;\"" -> acked + "?05246f28a1b2c3;"
        "\"#get:uuid;\"" -> acked + "?02c0ffee00-1234-4abc-8def-0123456789ab;"
        "\"#get:features;\"" -> acked + "?0a1;"
        "\"#get:uptime;\"" -> acked + "?09e10;"
        "\"#get:model;\"" -> acked + "?04tda-example;"
        "\"#get:name;\"" -> acked + "?03Mill adapter;"
        "\"#get:version;\"" -> acked + "?061.2.3;"
        "\"#get:build;\"" -> acked + "?07b42;"
        "\"#get:date;\"" -> acked + "?0866000000;"
        else -> when {
            text.startsWith("#get_mode:") -> acked + "?01${text[10]}000f0002;"
            else -> acked
        }
    }
}

/** The test setup of the connection manager with virtual time. */
class Harness(
    val test: TestScope,
    val random: Random = FixedRandom(0.5),
    val recorder: StreamRecorder = StreamRecorder.None,
) {
    val clock: Clock = Clock { test.testScheduler.currentTime }
    val links = TestLinks(clock)
    val capabilities = TestCapabilities(USB_ATTACHED)
    val wanted = TestWantedLinks()
    val setups = TestSetups()
    val manager = ConnectionManager(
        factories = TransportKind.entries.associateWith { links },
        capabilities = capabilities,
        wantedLinks = wanted,
        clock = clock,
        scope = test.backgroundScope,
        linkIoDispatcher = StandardTestDispatcher(test.testScheduler),
        reconnectPolicy = ReconnectPolicy(random),
        deviceSetups = setups,
        streamRecorder = recorder,
        timing = AdapterDialect.DEFAULT_TIMING,
    )

    /** Records each state of [session]. */
    fun states(session: DeviceSession): List<SessionState> {
        val list = ArrayList<SessionState>()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { session.state.collect { list += it } }
        return list
    }

    /** Records each item of [flow]. */
    fun <T> record(flow: Flow<T>): List<T> {
        val list = ArrayList<T>()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { flow.collect { list += it } }
        return list
    }

    fun now(): Long = test.testScheduler.currentTime
}

/** A device for the tests. */
fun device(
    id: String,
    address: AdapterAddress = AdapterAddress.Tcp("10.0.0.5"),
    twoWay: Boolean = true,
    record: Boolean = false,
): DeviceRef = DeviceRef(DeviceId(id), "Machine $id", address, twoWay, record)

val SPP: AdapterAddress = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C5")
val USB: AdapterAddress = AdapterAddress.UsbSerial(vendorId = 0x10C4, productId = 0xEA60, serial = "A1")
val TCP: AdapterAddress = AdapterAddress.Tcp("10.0.0.5", 8080)

/** The capabilities with the USB adapter [USB] attached and permitted. */
val USB_ATTACHED: CapabilityState =
    CapabilityState(attachedUsb = listOf(AttachedUsbDevice(vendorId = 0x10C4, productId = 0xEA60, serial = "A1", permitted = true)))

/** A setup with the default encoder profiles (1 µm for each count) and X, Y, Z on. */
fun defaultSetup(
    adapterIdentity: String? = null,
    twoWay: Boolean = true,
    debounceMs: Long = 0L,
    configurationCommands: Boolean = true,
): DeviceSetup =
    DeviceSetup(
        machine = MachineProfile.create().copy(twoWayCommunication = twoWay, adapterConfigurationCommands = configurationCommands),
        adapter = AdapterProfile(identity = adapterIdentity, probeDebounceMs = debounceMs),
    )

/** A state flow of a fixed value. */
fun <T> fixed(value: T): StateFlow<T> = MutableStateFlow(value)
