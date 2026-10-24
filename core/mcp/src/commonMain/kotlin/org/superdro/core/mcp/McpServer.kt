package org.superdro.core.mcp

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.superdro.core.dataoutput.ByteLink
import org.superdro.core.dataoutput.ListenSocket
import org.superdro.core.dataoutput.ListenSocketFactory
import org.superdro.core.dataoutput.PortRegistry
import org.superdro.core.dataoutput.hostAndPort
import org.superdro.core.model.McpScope
import org.superdro.core.util.Clock

/** The type of an error of the MCP server. */
enum class McpErrorType {
    /** The server cannot listen on an address and the port. */
    PortBindFailed,

    /** The server stopped at an `accept()` error. A new server starts after 5 s. */
    AcceptFailed,

    /** The selected interface has no address. */
    NoAddress,

    /** A data output of this app uses the port. */
    PortTaken,

    /** Two tools have one name, or two resources have one URI. */
    DuplicateName,
}

/**
 * One error of the MCP server. The UI makes the message from the type and the values.
 *
 * @property atMs The time of the error on the clock of the server.
 * @property detail The message of the cause, or the owner of a taken port, or null.
 */
data class McpError(
    val type: McpErrorType,
    val atMs: Long,
    val host: String? = null,
    val port: Int? = null,
    val detail: String? = null,
)

/** The state of the MCP server. */
sealed interface McpServerState {
    /** The server does not run. No socket is open. */
    data object Stopped : McpServerState

    /** The server binds its sockets. */
    data object Starting : McpServerState

    /** The server listens and has no request in work. */
    data object Ready : McpServerState

    /** The server has one or more requests in work. */
    data object Active : McpServerState

    /** The server stopped at [error]. A new server starts at [retryAtMs]. */
    data class Failed(val error: McpError, val retryAtMs: Long) : McpServerState
}

/** The result class of one call, for the audit log. */
enum class McpCallOutcome { Ok, Error, Refused, Timeout, Unauthorized }

/**
 * One call for the audit log.
 *
 * @property atMs The start of the call on the clock of the server.
 * @property tool The tool of a `tools/call`, or null.
 * @property method The JSON-RPC method, or the HTTP method and path for a request outside `/mcp`.
 * @property argumentsJson The arguments of a write tool, or null. A read tool is logged with its name only.
 */
data class McpCallRecord(
    val atMs: Long,
    val client: McpClient,
    val tool: String?,
    val method: String,
    val argumentsJson: String?,
    val result: McpCallOutcome,
    val durationMs: Long,
)

/**
 * The MCP server: the HTTP listener on the sockets of the data output, the routes, the checks,
 * and the protocol.
 *
 * - The server has sockets only between [start] and [stop]. [stop] closes the server sockets and
 *   all connections at once.
 * - It listens on each address that [bindHosts] gives, on [port].
 * - Routes: `POST /mcp`, `GET /mcp` gives 405, `POST /upload`, `GET /download/{fileId}`, `GET /`
 *   gives one plain text line. Each other path gives 404.
 * - Each request on `/mcp`, `/upload`, and `/download/` passes the `Origin` check (403), the
 *   `Host` check (403), the rate limit (429), and the bearer check (401). Ten failed token checks
 *   from one address in one minute lock the address for one minute.
 * - A bind failure, an `accept()` error, no address, a taken port, or a duplicate name gives
 *   [McpServerState.Failed] and a new server after [retryDelayMs].
 *
 * Call [start], [stop], and [restart] on one thread.
 *
 * @param token Gives the bearer token bytes. Null while the token is not there: each request gets 401.
 * @param toolSets Gives the tool sets at each request.
 * @param scopes Gives the scopes that are on.
 * @param onCall Gets the record of each call.
 * @param onError Gets each error.
 * @param ports The port registry of the app, or null. The server claims [port] with the owner `"mcp"`.
 * @param appVersion The version in `GET /` and in the server info.
 */
class McpServer(
    private val scope: CoroutineScope,
    private val sockets: ListenSocketFactory,
    private val bindHosts: suspend () -> List<String>,
    private val port: Int,
    private val token: () -> ByteArray?,
    toolSets: () -> List<McpToolSet>,
    scopes: () -> Set<McpScope>,
    private val files: McpFileStore,
    private val clock: Clock,
    private val onCall: (McpCallRecord) -> Unit = {},
    private val onError: (McpError) -> Unit = {},
    private val ports: PortRegistry? = null,
    appVersion: String = "dev",
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    private val protocol = McpProtocol(appVersion, toolSets, scopes)
    private val toolSets = toolSets
    private val versionLine = "SuperDRO $appVersion MCP server"

    private val _state = MutableStateFlow<McpServerState>(McpServerState.Stopped)

    /** The state of the server. */
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    private val _listening = MutableStateFlow<List<String>>(emptyList())

    /** The bound addresses as `host:port` (`[host]:port` for IPv6), while the server listens. */
    val listening: StateFlow<List<String>> = _listening.asStateFlow()

    private val _clients = MutableStateFlow<List<String>>(emptyList())

    /** The addresses of the clients with a request in the last 60 s. */
    val clients: StateFlow<List<String>> = _clients.asStateFlow()

    @Volatile
    private var current: Run? = null

    /** Starts the server. A second call has no effect while the server runs. */
    fun start() {
        if (current != null) return
        val run = Run()
        current = run
        _state.value = McpServerState.Starting
        run.job = scope.launch { run.serve() }
    }

    /** Stops the server: it closes the server sockets and all connections at once. */
    fun stop() {
        val run = current ?: return
        current = null
        run.close()
        _state.value = McpServerState.Stopped
        _listening.value = emptyList()
        _clients.value = emptyList()
    }

    /** Stops the server and starts a new one, for example after a change of the configuration. */
    fun restart() {
        stop()
        start()
    }

    /** One start of the server: its job, its sockets, its connections, and its client table. */
    private inner class Run {
        var job: Job? = null
        private val listeners = MutableStateFlow<List<ListenSocket>>(emptyList())
        private val links = MutableStateFlow<List<ByteLink>>(emptyList())
        private val inFlight = MutableStateFlow(0)

        private val tableLock = Mutex()
        private val lastSeen = mutableMapOf<String, Long>()
        private val requestTimes = mutableMapOf<String, ArrayDeque<Long>>()
        private val authFailures = mutableMapOf<String, ArrayDeque<Long>>()
        private val lockedUntil = mutableMapOf<String, Long>()
        private var claimed = false

        @Volatile
        private var closed = false

        fun close() {
            closed = true
            closeSockets()
            releasePort()
            job?.cancel()
        }

        private fun releasePort() {
            if (claimed) {
                claimed = false
                ports?.release(port, PORT_OWNER)
            }
        }

        private fun closeSockets() {
            val bound = listeners.value
            listeners.value = emptyList()
            bound.forEach { listener -> runCatching { listener.close() } }
            val open = links.value
            links.value = emptyList()
            open.forEach { link -> runCatching { link.close() } }
            publishLists()
        }

        private fun setState(transform: (McpServerState) -> McpServerState) {
            _state.update { state -> if (current === this && !closed) transform(state) else state }
        }

        private fun publishLists() {
            val hosts = listeners.value.map { listener -> hostAndPort(listener.host, listener.port) }
            _listening.update { value -> if (current === this && !closed) hosts else value }
        }

        private fun publishClients(nowMs: Long) {
            val recent = lastSeen.filter { (_, seen) -> nowMs - seen <= McpLimits.CLIENT_AGE_MS }.keys.sorted()
            _clients.update { value -> if (current === this && !closed) recent else value }
        }

        suspend fun serve() {
            while (true) {
                setState { McpServerState.Starting }
                val duplicate = runCatching { McpProtocol.checkNames(toolSets()) }.exceptionOrNull()
                if (duplicate != null) {
                    fail(McpError(McpErrorType.DuplicateName, clock.nowMs(), detail = duplicate.message))
                    continue
                }
                val holder = ports?.claim(port, PORT_OWNER)
                if (holder != null) {
                    fail(McpError(McpErrorType.PortTaken, clock.nowMs(), port = port, detail = holder))
                    continue
                }
                claimed = ports != null
                val hosts = bindHosts()
                if (hosts.isEmpty()) {
                    releasePort()
                    fail(McpError(McpErrorType.NoAddress, clock.nowMs(), port = port))
                    continue
                }
                val bound = mutableListOf<ListenSocket>()
                try {
                    var bindError: McpError? = null
                    for (host in hosts) {
                        try {
                            bound += sockets.bind(host, port)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            val error = McpError(McpErrorType.PortBindFailed, clock.nowMs(), host, port, exception.message)
                            bindError = error
                            onError(error)
                        }
                    }
                    checkActive()
                    if (bound.isEmpty()) {
                        releasePort()
                        fail(bindError ?: McpError(McpErrorType.PortBindFailed, clock.nowMs(), port = port), report = false)
                        continue
                    }
                    listeners.value = bound.toList()
                    publishLists()
                    updateActivity()
                    val error = acceptAll(bound)
                    closeSockets()
                    releasePort()
                    fail(error)
                } finally {
                    bound.forEach { listener -> runCatching { listener.close() } }
                }
            }
        }

        private suspend fun checkActive() {
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("The server stopped.")
        }

        private suspend fun acceptAll(bound: List<ListenSocket>): McpError = coroutineScope {
            val failure = CompletableDeferred<McpError>()
            val clientScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
            val loops = bound.map { listener ->
                launch {
                    while (true) {
                        val link = try {
                            listener.accept()
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            checkActive()
                            failure.complete(McpError(McpErrorType.AcceptFailed, clock.nowMs(), listener.host, listener.port, exception.message))
                            return@launch
                        }
                        clientScope.launch { serveLink(link) }
                    }
                }
            }
            val pruner = launch {
                while (true) {
                    delay(McpLimits.CLIENT_AGE_MS / 2)
                    tableLock.withLock { prune(clock.nowMs()) }
                }
            }
            val error = failure.await()
            pruner.cancel()
            loops.forEach { loop -> loop.cancel() }
            clientScope.cancel()
            error
        }

        private suspend fun serveLink(link: ByteLink) {
            links.update { list -> list + link }
            if (closed) link.close()
            try {
                HttpConnection(
                    link = link,
                    bodyLimit = { head -> if (head.path == PATH_UPLOAD) McpLimits.UPLOAD_BYTES else McpLimits.MCP_BODY_BYTES },
                    handler = { request -> handle(request, link.remoteHost) },
                ).serve()
            } finally {
                link.close()
                links.update { list -> list - link }
            }
        }

        /** The reply to one request. */
        private suspend fun handle(request: HttpRequest, host: String): HttpReply {
            val path = request.path
            if (path == "/" && request.method == "GET") return full(HttpResponse.text(200, versionLine))
            if (path == PATH_MCP && request.method != "POST") return full(HttpResponse.text(405, "Use POST", listOf("Allow" to "POST")))
            val protected = path == PATH_MCP || path == PATH_UPLOAD || path.startsWith(PATH_DOWNLOAD)
            if (!protected) return full(HttpResponse.text(404, "Not found"))

            val nowMs = clock.nowMs()
            val gate = tableLock.withLock {
                prune(nowMs)
                lastSeen[host] = nowMs
                publishClients(nowMs)
                when {
                    (lockedUntil[host] ?: 0L) > nowMs -> 429
                    !admit(host, nowMs) -> 429
                    else -> 0
                }
            }
            if (gate == 429) return full(HttpResponse.text(429, "Too many requests", listOf("Retry-After" to "1")))

            val origin = request.headers["Origin"]
            if (origin != null && !originAllowed(origin)) return full(HttpResponse.text(403, "The Origin is not allowed"))
            val hostHeader = request.headers["Host"] ?: return full(HttpResponse.text(400, "The Host header is missing"))
            if (!hostAllowed(hostHeader)) return full(HttpResponse.text(403, "The Host is not allowed"))

            if (!authorized(request)) {
                tableLock.withLock { countAuthFailure(host, nowMs) }
                onCall(McpCallRecord(nowMs, McpClient(host, McpEra.Legacy), null, "${request.method} $path", null, McpCallOutcome.Unauthorized, clock.nowMs() - nowMs))
                return full(HttpResponse.text(401, "Unauthorized", listOf("WWW-Authenticate" to "Bearer realm=\"SuperDRO\"")))
            }

            return inWork {
                when {
                    path == PATH_MCP -> mcp(request, host, nowMs)
                    path == PATH_UPLOAD -> upload(request, host, nowMs)
                    else -> download(request, host, nowMs)
                }
            }
        }

        private suspend fun inWork(block: suspend () -> HttpReply): HttpReply {
            inFlight.update { count -> count + 1 }
            updateActivity()
            try {
                val reply = block()
                if (reply !is HttpReply.Stream) return reply
                // A stream is in work until its end.
                inFlight.update { count -> count + 1 }
                return HttpReply.Stream { sink ->
                    try {
                        reply.produce(sink)
                    } finally {
                        inFlight.update { count -> count - 1 }
                        updateActivity()
                    }
                }
            } finally {
                inFlight.update { count -> count - 1 }
                updateActivity()
            }
        }

        private fun full(response: HttpResponse): HttpReply = HttpReply.Full(response)

        /** Removes the old entries of the tables. Call it with the lock. */
        private fun prune(nowMs: Long) {
            lastSeen.entries.removeAll { (_, seen) -> nowMs - seen > McpLimits.CLIENT_AGE_MS }
            requestTimes.values.forEach { times -> while (times.isNotEmpty() && nowMs - times.first() >= RATE_WINDOW_MS) times.removeFirst() }
            requestTimes.entries.removeAll { (_, times) -> times.isEmpty() }
            authFailures.values.forEach { times -> while (times.isNotEmpty() && nowMs - times.first() >= McpLimits.AUTH_LOCK_MS) times.removeFirst() }
            authFailures.entries.removeAll { (_, times) -> times.isEmpty() }
            lockedUntil.entries.removeAll { (_, until) -> until <= nowMs }
            publishClients(nowMs)
        }

        /** Counts one request of [host]. Gives false above the rate. Call it with the lock. */
        private fun admit(host: String, nowMs: Long): Boolean {
            val times = requestTimes.getOrPut(host) { ArrayDeque() }
            while (times.isNotEmpty() && nowMs - times.first() >= RATE_WINDOW_MS) times.removeFirst()
            if (times.size >= McpLimits.REQUESTS_PER_SECOND) return false
            times.addLast(nowMs)
            return true
        }

        private fun countAuthFailure(host: String, nowMs: Long) {
            val times = authFailures.getOrPut(host) { ArrayDeque() }
            while (times.isNotEmpty() && nowMs - times.first() >= McpLimits.AUTH_LOCK_MS) times.removeFirst()
            times.addLast(nowMs)
            if (times.size >= McpLimits.AUTH_FAILURES) {
                lockedUntil[host] = nowMs + McpLimits.AUTH_LOCK_MS
                times.clear()
            }
        }

        /** Gives true when [origin] is `null`, or a URL of this server, or a localhost URL. */
        private fun originAllowed(origin: String): Boolean {
            val value = origin.trim()
            if (value.equals("null", ignoreCase = true)) return true
            val lower = value.lowercase()
            if (!lower.startsWith("http://")) return false
            val authority = lower.removePrefix("http://").substringBefore('/')
            val originPort = portOf(authority) ?: 80
            if (originPort != port) return false
            return Http.hostOf(authority) in allowedHosts()
        }

        /** Gives true when the host of [hostHeader] is a bound address, `localhost`, or a loopback address. */
        private fun hostAllowed(hostHeader: String): Boolean = Http.hostOf(hostHeader) in allowedHosts()

        private fun allowedHosts(): Set<String> =
            listeners.value.map { listener -> Http.hostOf(listener.host) }.toSet() + LOCAL_HOSTS

        private fun portOf(authority: String): Int? {
            val after = if (authority.startsWith("[")) authority.substringAfter(']', "") else if (authority.count { it == ':' } == 1) ":" + authority.substringAfter(':') else ""
            return if (after.startsWith(":")) after.substring(1).toIntOrNull() else null
        }

        /** The constant-time check of `Authorization: Bearer <token>`. */
        private fun authorized(request: HttpRequest): Boolean {
            val expected = token()?.takeIf { bytes -> bytes.isNotEmpty() } ?: return false
            val header = request.headers["Authorization"] ?: return false
            if (!header.startsWith("Bearer ", ignoreCase = true)) return false
            val given = header.substring(7).trim().encodeToByteArray()
            // The loop reads each given byte, so the time does not show the first wrong byte.
            var difference = given.size xor expected.size
            for (index in given.indices) {
                difference = difference or (given[index].toInt() xor expected[index % expected.size].toInt())
            }
            return difference == 0
        }

        private suspend fun mcp(request: HttpRequest, host: String, startMs: Long): HttpReply {
            val type = request.contentType
            if (type != null && type != "application/json") return full(HttpResponse.text(415, "The body must be application/json"))
            val headers = McpHeaders(
                protocolVersion = request.headers["MCP-Protocol-Version"]?.trim(),
                method = request.headers["Mcp-Method"]?.trim(),
                name = request.headers["Mcp-Name"]?.trim(),
            )
            val outcome = protocol.handle(request.body.decodeToString(), headers, host)
            val client = McpClient(host, outcome.era)
            return when (val reply = outcome.reply) {
                ProtocolReply.Accepted -> full(HttpResponse.empty(202))
                is ProtocolReply.Json -> {
                    record(outcome, client, startMs, classify(reply.body))
                    full(HttpResponse.json(reply.status, reply.body))
                }
                is ProtocolReply.Stream -> HttpReply.Stream { sink ->
                    val response = try {
                        reply.run { notification -> sink.send(notification) }
                    } catch (exception: CancellationException) {
                        record(outcome, client, startMs, McpCallOutcome.Error)
                        throw exception
                    }
                    record(outcome, client, startMs, classify(response))
                    sink.send(response)
                }
            }
        }

        private fun record(outcome: ProtocolOutcome, client: McpClient, startMs: Long, result: McpCallOutcome) {
            onCall(McpCallRecord(startMs, client, outcome.tool, outcome.method ?: "?", outcome.argumentsJson, result, clock.nowMs() - startMs))
        }

        private suspend fun upload(request: HttpRequest, host: String, startMs: Long): HttpReply {
            if (request.method != "POST") return full(HttpResponse.text(405, "Use POST", listOf("Allow" to "POST")))
            val contentType = request.contentType ?: "application/octet-stream"
            val receipt = files.putUpload(contentType, request.body)
            onCall(McpCallRecord(startMs, McpClient(host, McpEra.Legacy), null, "POST $PATH_UPLOAD", null, McpCallOutcome.Ok, clock.nowMs() - startMs))
            return full(
                HttpResponse.json(
                    200,
                    buildJsonObject {
                        put("fileId", receipt.fileId)
                        put("bytes", receipt.bytes)
                        put("sha256Hex", receipt.sha256Hex)
                        put("contentType", contentType)
                    },
                ),
            )
        }

        private suspend fun download(request: HttpRequest, host: String, startMs: Long): HttpReply {
            if (request.method != "GET") return full(HttpResponse.text(405, "Use GET", listOf("Allow" to "GET")))
            val fileId = request.path.removePrefix(PATH_DOWNLOAD)
            val file = if (fileId.isEmpty() || '/' in fileId) null else files.download(fileId)
            val outcome = if (file == null) McpCallOutcome.Error else McpCallOutcome.Ok
            onCall(McpCallRecord(startMs, McpClient(host, McpEra.Legacy), null, "GET $PATH_DOWNLOAD$fileId", null, outcome, clock.nowMs() - startMs))
            if (file == null) return full(HttpResponse.text(404, "The file is not there"))
            return full(HttpResponse(200, file.bytes, file.contentType, listOf("Cache-Control" to "no-store")))
        }

        private suspend fun fail(error: McpError, report: Boolean = true) {
            if (report) onError(error)
            setState { McpServerState.Failed(error, error.atMs + retryDelayMs) }
            delay(retryDelayMs)
        }

        private fun updateActivity() {
            val listening = listeners.value.isNotEmpty()
            val busy = inFlight.value > 0
            setState { state ->
                when (state) {
                    McpServerState.Ready, McpServerState.Active, McpServerState.Starting ->
                        if (!listening) state else if (busy) McpServerState.Active else McpServerState.Ready
                    else -> state
                }
            }
        }
    }

    companion object {
        /** The time from a failure to the new server. */
        const val RETRY_DELAY_MS: Long = 5000L

        /** The owner of the port in the [PortRegistry]. */
        const val PORT_OWNER: String = "mcp"

        const val PATH_MCP: String = "/mcp"
        const val PATH_UPLOAD: String = "/upload"
        const val PATH_DOWNLOAD: String = "/download/"

        private const val RATE_WINDOW_MS: Long = 1000L

        private val LOCAL_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1")

        /**
         * Gives the outcome of a JSON-RPC [response]: [McpCallOutcome.Error] for an error or
         * `isError: true`, [McpCallOutcome.Refused] and [McpCallOutcome.Timeout] for the gate
         * texts, else [McpCallOutcome.Ok].
         */
        fun classify(response: JsonObject): McpCallOutcome {
            if (response.containsKey("error")) return McpCallOutcome.Error
            val result = response["result"] as? JsonObject ?: return McpCallOutcome.Ok
            val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (!isError) return McpCallOutcome.Ok
            val text = result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
            return when (text) {
                GateTexts.REFUSED -> McpCallOutcome.Refused
                GateTexts.TIMEOUT -> McpCallOutcome.Timeout
                else -> McpCallOutcome.Error
            }
        }
    }
}
