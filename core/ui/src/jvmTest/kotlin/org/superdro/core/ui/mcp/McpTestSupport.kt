package org.superdro.core.ui.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.superdro.core.dataoutput.ByteLink
import org.superdro.core.dataoutput.ListenSocket
import org.superdro.core.dataoutput.ListenSocketFactory
import org.superdro.core.dataoutput.WallClock
import org.superdro.core.mcp.McpSecrets
import org.superdro.core.mcp.McpToolSet
import org.superdro.core.model.AppSettings
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.McpConfig
import org.superdro.core.store.files.StagingFiles
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.McpCallRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.dataoutput.TestInterfaces
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.tabs.RecordingRequests
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.util.Clock
import org.superdro.core.util.InMemorySecretStore

/** Sockets with no network and a client side that can send, for the tests with virtual time. */
class McpTestSockets : ListenSocketFactory {
    val sockets = mutableListOf<McpTestListenSocket>()

    /** The sockets that are not closed. */
    val open: List<McpTestListenSocket> get() = sockets.filter { socket -> !socket.closed }

    override suspend fun bind(host: String, port: Int): ListenSocket = McpTestListenSocket(host, port).also { socket -> sockets += socket }
}

class McpTestListenSocket(override val host: String, override val port: Int) : ListenSocket {
    private val incoming = Channel<ByteLink>(Channel.UNLIMITED)
    var closed = false
        private set

    /** Connects a new client from [remoteHost]. */
    fun connect(remoteHost: String = "192.168.1.30"): McpTestLink = McpTestLink(remoteHost).also { link -> incoming.trySend(link) }

    override suspend fun accept(): ByteLink = incoming.receive()

    override fun close() {
        closed = true
        incoming.cancel()
    }
}

/** The server side of one test connection with the client functions [send] and [receivedText]. */
class McpTestLink(override val remoteHost: String) : ByteLink {
    private val toServer = Channel<ByteArray>(Channel.UNLIMITED)
    private val received = mutableListOf<ByteArray>()
    var closed = false
        private set

    /** The text that the client received. */
    val receivedText: String get() = received.fold(ByteArray(0)) { all, bytes -> all + bytes }.decodeToString()

    /** The client sends one HTTP request with the body [body] and the extra [headers]. */
    fun request(method: String, path: String, body: String? = null, headers: List<Pair<String, String>> = emptyList(), host: String = "192.168.1.20:5810") {
        val text = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (body != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
            }
            append("\r\n")
            if (body != null) append(body)
        }
        toServer.trySend(text.encodeToByteArray())
    }

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = toServer.receiveCatching().getOrNull() ?: return -1
        chunk.copyInto(buffer, 0, 0, minOf(chunk.size, buffer.size))
        return minOf(chunk.size, buffer.size)
    }

    override suspend fun write(bytes: ByteArray) {
        check(!closed) { "The link is closed." }
        received += bytes.copyOf()
    }

    override fun close() {
        closed = true
        toServer.close()
    }
}

/**
 * The MCP server on a database in memory, with test sockets, a secret store in memory, and
 * virtual time. All work runs on the test scheduler of [scope].
 */
class McpHarness(val scope: TestScope, toolSets: (McpHarness) -> List<McpToolSet> = { harness -> listOf(harness.readTools()) }) {
    val store = UiStoreFixture(scope)
    val interfaces = TestInterfaces()
    val sockets = McpTestSockets()
    val secretStore = InMemorySecretStore()
    val requests = RecordingRequests()
    val clock = Clock { scope.testScheduler.currentTime }
    val wallClock = WallClock { WALL_START_MS + scope.testScheduler.currentTime }
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private var nextId = 1
    val secrets = McpSecrets(secretStore, random = { count -> ByteArray(count) { index -> (index * 7 + nextId).toByte() }.also { nextId++ } })
    val calls = McpCallRepository(store.writer)
    val drawings = DrawingRepository(store.writer)
    val files = McpFiles(
        uploads = StagingFiles(store.storeIO, McpFiles.UPLOADS, store.wallClock, dispatcher),
        downloads = StagingFiles(store.storeIO, McpFiles.DOWNLOADS, store.wallClock, dispatcher),
        random = { count -> ByteArray(count) { index -> (index + nextId).toByte() }.also { nextId++ } },
    )
    var online: Set<String> = emptySet()
    var selected: String? = null
    val toolSets: List<McpToolSet> = toolSets(this)
    val runner = McpRunner(
        settings = store.appSettings,
        networkInterfaces = interfaces,
        secrets = secrets,
        secretsDispatcher = dispatcher,
        sockets = sockets,
        files = files,
        toolSets = { this.toolSets },
        calls = calls,
        clock = clock,
        wallClock = wallClock,
        appVersion = "26.43.1",
        dispatcher = dispatcher,
    )

    /** The read tool set on the stores of this harness. */
    fun readTools(): McpToolSet = readToolSet(
        ReadToolDeps(
            machines = store.machines,
            tools = store.tools,
            drawings = drawings,
            onlineMachines = { online },
            selectedProfileId = { selected },
            config = { store.appSettings.settings.value.mcp },
            appVersion = "26.43.1",
        ),
    )

    /** Stores a new profile and gives it. */
    suspend fun profile(type: MachineType = MachineType.VerticalMill, name: String = "Mill", change: (MachineProfile) -> MachineProfile = { it }): MachineProfile =
        store.machines.createProfile(change(MachineProfile.create(type, name))).valueOrNull() ?: error("The profile was not stored.")

    /** Writes the MCP configuration. */
    fun configure(transform: (McpConfig) -> McpConfig) {
        store.appSettings.update { settings: AppSettings -> settings.copy(mcp = transform(settings.mcp)) }
    }

    /** The stored MCP configuration. */
    val config: McpConfig get() = store.appSettings.settings.value.mcp

    /** Starts the runner in the background scope of the test. */
    fun start() {
        runner.start(scope.backgroundScope)
        settle()
    }

    /** Runs the work that is due now, and a short time more. */
    fun settle(ms: Long = 10L) {
        scope.runCurrent()
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** The presenter context of the virtual time. */
    fun presenterContext(): PresenterContext = PresenterContext(dispatcher, clock)

    /** Gives the presenter of the settings screen. */
    fun presenter(): McpSettingsPresenter = McpSettingsPresenter(runner, store.appSettings, interfaces.interfaces, calls, requests, presenterContext())

    /** Gives the presenter of the status chip. */
    fun chip(): McpChipPresenter = McpChipPresenter(runner, store.appSettings, requests, presenterContext())

    /** Connects a client to the first open socket. */
    fun connect(remoteHost: String = "192.168.1.30"): McpTestLink = sockets.open.first().connect(remoteHost)

    /** The headers of a request with the token. */
    suspend fun authorization(): List<Pair<String, String>> = listOf("Authorization" to "Bearer ${runner.token(create = true)}")

    fun close() = store.close()

    companion object {
        /** The wall clock at the virtual time 0: 2026-10-23T09:00:00Z. */
        const val WALL_START_MS: Long = 1792746000000L
    }
}
