package org.superdro.validation.mcp

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.di.APP_SCOPE
import org.superdro.core.di.coreDiModule
import org.superdro.core.di.noOpPlatformModule
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.McpProtocol
import org.superdro.core.mcp.McpServerState
import org.superdro.core.mcp.WriteRequest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.BindInterface
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpScope
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.McpCallRepository
import org.superdro.core.store.repository.McpCallRow
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.mcp.McpGate
import org.superdro.core.ui.mcp.McpRunner
import org.superdro.core.ui.mcp.McpSettingsPresenter

/** One HTTP answer of the server: the status, the headers with lowercase names, and the body. */
class HttpAnswer(val status: Int, val headers: Map<String, String>, val bytes: ByteArray) {
    val text: String get() = bytes.decodeToString()

    /** The body as a JSON object. */
    val json: JsonObject get() = Json.parseToJsonElement(text).jsonObject

    /** The `result` object of a JSON-RPC response. */
    val result: JsonObject get() = json.getValue("result").jsonObject

    /** The `error` object of a JSON-RPC response. */
    val error: JsonObject get() = json.getValue("error").jsonObject

    /** The `structuredContent` of a tool result. */
    val structured: JsonObject get() = result.getValue("structuredContent").jsonObject

    /** The text of the first content block of a tool result. */
    val content: String get() = result.array("content").first().jsonObject.text("text")

    /** The `isError` flag of a tool result. */
    val isError: Boolean get() = (result["isError"] as? JsonPrimitive)?.content == "true"
}

/**
 * The MCP server through the full DI graph of `core:di` on the JVM: the real `McpRunner`, the
 * real `McpServer` on a loopback socket, the database in memory, the app files in a temporary
 * folder, and the fake adapter for an online machine. The client side is raw HTTP/1.1 on a
 * `java.net.Socket`, so each header of the request is under the control of the test.
 *
 * @param interfaces The interfaces that the graph sees. The default is one loopback interface
 *   for [BindInterface.Localhost] and one for [BindInterface.WiFi], both on `127.0.0.1`.
 */
class McpAcceptanceGraph(
    interfaces: List<NetworkInterfaceInfo> = listOf(
        NetworkInterfaceInfo("lo", BindInterface.Localhost, listOf("127.0.0.1")),
        NetworkInterfaceInfo("wlan0", BindInterface.WiFi, listOf("127.0.0.1")),
    ),
) : AutoCloseable {
    val folder: File = Files.createTempDirectory("superdro-mcp-acceptance").toFile()

    /** A free port of this computer. The graph binds the server to it. */
    val port: Int = ServerSocket(0).use { socket -> socket.localPort }

    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
        .also { driver -> SuperDroDatabase.Schema.create(driver) }

    private val network = object : NetworkInterfaces {
        override val interfaces = MutableStateFlow(interfaces)
    }

    val di: DI = DI {
        import(coreDiModule, allowOverride = true)
        import(noOpPlatformModule, allowOverride = true)
        bindSingleton<SqlDriver> { driver }
        bindProvider<StoreIOProvider>(overrides = true) { StoreIOProviderJvm(File(folder, "files")) }
        bindSingleton<NetworkInterfaces>(overrides = true) { network }
        bindSingleton<DebugAdapter>(overrides = true) { FakeDebugAdapter() }
    }

    val appSettings: AppSettingsStore = di.direct.instance()
    val runner: McpRunner = di.direct.instance()
    val gate: McpGate = di.direct.instance()
    val calls: McpCallRepository = di.direct.instance()
    val machines: MachineRepository = di.direct.instance()
    val tools: ToolRepository = di.direct.instance()
    val drawings: DrawingRepository = di.direct.instance()
    val adapters: AdapterRepository = di.direct.instance()
    val manager: ConnectionManager = di.direct.instance()

    /** The scope of the gate answers and the other helpers of the test. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** The requests that the gate showed, in order. */
    val gateRequests: MutableList<WriteRequest> = Collections.synchronizedList(ArrayList())

    private val presenters = mutableListOf<McpSettingsPresenter>()

    init {
        runBlocking { appSettings.awaitLoaded() }
    }

    /** Gives a settings presenter of the graph. [close] closes it. */
    fun presenter(): McpSettingsPresenter = di.direct.instance<McpSettingsPresenter>().also { presenter -> presenters += presenter }

    /** The stored MCP configuration. */
    val config: McpConfig get() = appSettings.settings.value.mcp

    /** Writes the MCP configuration. The runner follows it. */
    fun configure(transform: (McpConfig) -> McpConfig) {
        runBlocking { appSettings.update { settings -> settings.copy(mcp = transform(settings.mcp)) } }
    }

    /**
     * Turns the server on: always on, on the loopback interface, on [port], with the Read scope
     * and [scopes]. Waits until the server listens.
     */
    fun turnOn(vararg scopes: McpScope, confirmation: org.superdro.core.model.McpConfirmation = org.superdro.core.model.McpConfirmation.Risky) {
        configure { config ->
            config.copy(
                enabled = true,
                activation = McpActivation.AlwaysOn,
                bindInterface = BindInterface.Localhost,
                port = port,
                scopes = setOf(McpScope.Read) + scopes,
                populateConfirmed = McpScope.Populate in scopes,
                confirmation = confirmation,
            )
        }
        awaitReady()
    }

    /** Waits until the server listens. */
    fun awaitReady() {
        waitUntil("The server listens") { runner.state.value == McpServerState.Ready }
    }

    /** Waits until the server has no socket. */
    fun awaitStopped() {
        waitUntil("The server stopped") { runner.state.value == McpServerState.Stopped && !portOpen() }
    }

    /** True when a client can connect to [port]. */
    fun portOpen(): Boolean = try {
        Socket().use { probe -> probe.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: ConnectException) {
        false
    }

    /** The bearer token of the server. The first call makes it. */
    fun token(): String = runBlocking { runner.token(create = true) ?: error("The token is not there.") }

    /** Waits up to [timeoutMs] for [condition]. */
    fun waitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            check(System.currentTimeMillis() - start < timeoutMs) { "$what. state=${runner.state.value} error=${runner.lastError.value} listening=${runner.listening.value} config=$config" }
            Thread.sleep(20)
        }
    }

    /**
     * Answers each request of the gate with [decision] until the returned job is cancelled. The
     * requests go to [gateRequests].
     */
    fun answerGate(decision: GateDecision): Job = scope.launch {
        gate.pending.filterNotNull().collect { request ->
            gateRequests += request
            // A short wait: the dialog of the app also needs time, and the call must wait for it.
            delay(20)
            if (decision == GateDecision.Allowed) gate.allow() else gate.refuse()
        }
    }

    // -----------------------------------------------------------------------------------------
    // The stored data

    /** Stores a metric machine profile [name] of [type] and gives it. */
    fun profile(name: String, type: MachineType = MachineType.VerticalMill): MachineProfile = runBlocking {
        machines.createProfile(MachineProfile.create(type, name).copy(metricDefault = true)).valueOrNull() ?: error("The profile $name was not stored.")
    }

    /**
     * Stores a machine with the fake adapter and connects it, as the UI validation does. The
     * session goes online in real time. The function waits for it.
     */
    fun connectFake(id: String, name: String, type: MachineType = MachineType.Lathe, scenario: String = "still"): MachineProfile = runBlocking {
        val encoders = AdapterProfile().encoders.mapValues { (_, encoder) -> encoder.copy(calibration = Calibration(status = CalibrationStatus.UserSet)) }
        val adapter = adapters.saveAdapterProfile(AdapterProfile(alias = "$name adapter", model = "tda-fake", encoders = encoders)).valueOrNull()
        val address = AdapterAddress.Fake(scenario)
        val profile = MachineProfile.create(type, name).copy(id = id, adapter = address, adapterProfileId = adapter?.id, metricDefault = true)
        val stored = machines.createProfile(profile).valueOrNull() ?: error("The profile $id was not stored.")
        manager.connect(DeviceRef(DeviceId(id), name, address))
        waitUntil("The machine $name is online") { manager.sessions.value[DeviceId(id)]?.state?.value?.isOnline == true }
        stored
    }

    /** The audit rows, the newest first. */
    fun auditRows(count: Int = 200): List<McpCallRow> = runBlocking { calls.last(count).first() }

    // -----------------------------------------------------------------------------------------
    // The HTTP client

    /**
     * Sends one HTTP/1.1 request to the server and gives the answer. The request has
     * `Connection: close`, so the server closes the socket after the answer.
     *
     * @param token The bearer token, or null for a request with no `Authorization` header.
     * @param host The `Host` header.
     */
    fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        token: String? = token(),
        contentType: String? = "application/json",
        headers: List<Pair<String, String>> = emptyList(),
        host: String = "127.0.0.1:$port",
    ): HttpAnswer {
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Connection: close\r\n")
            if (token != null) append("Authorization: Bearer ").append(token).append("\r\n")
            if (body != null) {
                if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
            }
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.soTimeout = 20_000
            socket.getOutputStream().apply {
                write(head.encodeToByteArray())
                if (body != null) write(body)
                flush()
            }
            val all = ByteArrayOutputStream()
            socket.getInputStream().copyTo(all)
            return parse(all.toByteArray())
        }
    }

    private fun parse(bytes: ByteArray): HttpAnswer {
        val separator = indexOfBlankLine(bytes)
        check(separator >= 0) { "The answer has no head: ${bytes.decodeToString()}" }
        val headLines = bytes.copyOfRange(0, separator).decodeToString().split("\r\n")
        val status = headLines.first().split(' ')[1].toInt()
        val headers = headLines.drop(1).filter { line -> ':' in line }
            .associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
        val body = bytes.copyOfRange(separator + 4, bytes.size)
        return HttpAnswer(status, headers, body)
    }

    private fun indexOfBlankLine(bytes: ByteArray): Int {
        for (index in 0..bytes.size - 4) {
            if (bytes[index] == '\r'.code.toByte() && bytes[index + 1] == '\n'.code.toByte() && bytes[index + 2] == '\r'.code.toByte() && bytes[index + 3] == '\n'.code.toByte()) return index
        }
        return -1
    }

    /** One JSON-RPC request of the legacy era (no MCP headers, no `_meta` version) on `/mcp`. */
    fun legacy(method: String, params: JsonObject? = null, id: Int? = 1, token: String? = token()): HttpAnswer =
        request("POST", "/mcp", rpc(method, params, id).encodeToByteArray(), token = token)

    /**
     * One JSON-RPC request of the modern era on `/mcp`: the `_meta` protocol version in the
     * body, and the headers `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` (for a tool call).
     *
     * @param headerMethod The `Mcp-Method` header. The default is [method].
     * @param headerVersion The `MCP-Protocol-Version` header. The default is the modern version.
     */
    fun modern(
        method: String,
        params: JsonObject? = null,
        name: String? = null,
        id: Int = 1,
        token: String? = token(),
        headerMethod: String? = method,
        headerVersion: String? = McpProtocol.MODERN_VERSION,
        metaVersion: String = McpProtocol.MODERN_VERSION,
        headers: List<Pair<String, String>> = emptyList(),
    ): HttpAnswer {
        val withMeta = buildJsonObject {
            params?.forEach { (key, value) -> put(key, value) }
            put("_meta", buildJsonObject { put(McpProtocol.META_PROTOCOL_VERSION, metaVersion) })
        }
        val all = buildList {
            if (headerVersion != null) add("MCP-Protocol-Version" to headerVersion)
            if (headerMethod != null) add("Mcp-Method" to headerMethod)
            if (name != null) add("Mcp-Name" to name)
            addAll(headers)
        }
        return request("POST", "/mcp", rpc(method, withMeta, id).encodeToByteArray(), token = token, headers = all)
    }

    /** A `tools/call` of [tool] with [arguments] in the legacy era. */
    fun call(tool: String, arguments: JsonObject = JsonObject(emptyMap()), id: Int = 1): HttpAnswer =
        legacy("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) }, id)

    /** A `tools/call` of [tool] with [arguments] in the modern era. */
    fun callModern(tool: String, arguments: JsonObject = JsonObject(emptyMap()), id: Int = 1): HttpAnswer =
        modern("tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) }, name = tool, id = id)

    /** `POST /upload` of [bytes] with [contentType]. */
    fun upload(bytes: ByteArray, contentType: String): HttpAnswer = request("POST", "/upload", bytes, contentType = contentType)

    private fun rpc(method: String, params: JsonObject?, id: Int?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    override fun close() {
        presenters.forEach { presenter -> runCatching { presenter.close() } }
        scope.cancel()
        runner.stop()
        manager.close()
        di.direct.instance<CoroutineScope>(tag = APP_SCOPE).cancel()
        runBlocking { di.direct.instance<StoreWriter>().awaitIdle() }
        runCatching { driver.close() }
        folder.deleteRecursively()
    }
}

/** Builds a JSON object. */
fun json(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

/** The text of a JSON primitive [key] of this object. */
fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).content

/** The JSON array [key] of this object. */
fun JsonObject.array(key: String): List<JsonElement> = getValue(key) as kotlinx.serialization.json.JsonArray

/** The names of the tools in a `tools/list` result. */
fun HttpAnswer.toolNames(): List<String> = result.array("tools").map { tool -> tool.jsonObject.text("name") }

/** A small ASCII DXF in mm with one line and one circle on the layer `0`. */
object SmallDxf {
    private fun pairs(vararg pairs: String): String = pairs.joinToString("") { pair ->
        val separator = pair.indexOf('|')
        "${pair.substring(0, separator).padStart(3)}\n${pair.substring(separator + 1)}\n"
    }

    /** The bytes of the file. */
    val BYTES: ByteArray = pairs(
        "0|SECTION", "2|HEADER", "9|\$ACADVER", "1|AC1015", "9|\$INSUNITS", "70|4", "0|ENDSEC",
        "0|SECTION", "2|TABLES",
        "0|TABLE", "2|LTYPE", "0|LTYPE", "2|Continuous", "70|0", "0|ENDTAB",
        "0|TABLE", "2|LAYER", "0|LAYER", "2|0", "70|0", "62|7", "6|Continuous", "0|ENDTAB",
        "0|ENDSEC",
        "0|SECTION", "2|BLOCKS", "0|ENDSEC",
        "0|SECTION", "2|ENTITIES",
        "0|LINE", "8|0", "10|0.0", "20|0.0", "30|0.0", "11|10.0", "21|0.0", "31|0.0",
        "0|CIRCLE", "8|0", "10|5.0", "20|5.0", "30|0.0", "40|2.0",
        "0|ENDSEC", "0|EOF",
    ).encodeToByteArray()
}
