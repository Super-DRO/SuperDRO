package org.superdro.core.ui.mcp.populate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.superdro.core.dataoutput.WallClock
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.McpClient
import org.superdro.core.mcp.McpEra
import org.superdro.core.mcp.McpFileStore
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.OpenWriteGate
import org.superdro.core.mcp.StoredFile
import org.superdro.core.mcp.ToolCall
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.UploadReceipt
import org.superdro.core.mcp.WriteGate
import org.superdro.core.mcp.WriteRequest
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.McpConfig
import org.superdro.core.model.McpConfirmation
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.Workspace
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.core.ui.testing.FixedWallClock
import org.superdro.core.ui.testing.MemoryStoreIO

/** A gate that records each request and gives [decision]. */
class RecordingGate(var decision: GateDecision = GateDecision.Allowed) : WriteGate {
    val requests = mutableListOf<WriteRequest>()

    override suspend fun confirm(request: WriteRequest): GateDecision {
        requests += request
        return decision
    }
}

/** The uploads and the downloads in memory. */
class MemoryFiles : McpFileStore {
    val uploads = linkedMapOf<String, StoredFile>()
    val downloads = linkedMapOf<String, StoredFile>()
    val downloadNames = linkedMapOf<String, String>()
    private var next = 0

    override suspend fun putUpload(contentType: String, bytes: ByteArray): UploadReceipt {
        val id = "upload-${++next}"
        uploads[id] = StoredFile(id, contentType, bytes)
        return UploadReceipt(id, bytes.size.toLong(), "")
    }

    override suspend fun upload(fileId: String): StoredFile? = uploads[fileId]

    override suspend fun putDownload(fileName: String, contentType: String, bytes: ByteArray): String {
        val id = "download-${++next}"
        downloads[id] = StoredFile(id, contentType, bytes)
        downloadNames[id] = fileName
        return id
    }

    override suspend fun download(fileId: String): StoredFile? = downloads[fileId]
}

/**
 * The populate tools on a database in memory with a metric mill "Mill" and a lathe "Lathe". All
 * work runs on one test dispatcher of [scope].
 */
class PopulateFixture(scope: TestScope, val gate: RecordingGate = RecordingGate(), confirmation: McpConfirmation = McpConfirmation.Risky) {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val driver: JdbcSqliteDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
        .also { driver -> SuperDroDatabase.Schema.create(driver) }
    val database: SuperDroDatabase = createSuperDroDatabase(driver)
    val writer = StoreWriter(database, dispatcher = dispatcher, readDispatcher = dispatcher)
    val storeIO = MemoryStoreIO()
    val wallClock = FixedWallClock()
    val machines = MachineRepository(writer, storeIO, SuperDroJson, wallClock)
    val tools = ToolRepository(writer, SuperDroJson, wallClock)
    val drawings = DrawingRepository(writer)
    val files = MemoryFiles()
    var config: McpConfig = McpConfig(confirmation = confirmation)
    val online = mutableSetOf<String>()

    val deps = PopulateDeps(
        machines = machines,
        tools = tools,
        drawings = drawings,
        files = files,
        gate = gate,
        config = { config },
        onlineMachines = { online.toSet() },
        appVersion = "1.2.3",
        clock = WallClock { wallClock.current.toEpochMilliseconds() },
        storeIO = storeIO,
    )
    val toolSet = populateToolSet(deps)
    val byName: Map<String, McpTool> = toolSet.tools.associateBy { tool -> tool.spec.name }

    lateinit var mill: MachineProfile
    lateinit var lathe: MachineProfile

    /** Writes the two profiles. Call it first in a test. */
    suspend fun start(): PopulateFixture {
        mill = (machines.createProfile(MachineProfile.create(MachineType.VerticalMill, "Mill").copy(metricDefault = true)) as StoreResult.Ok).value
        lathe = (machines.createProfile(MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true)) as StoreResult.Ok).value
        return this
    }

    /** The default workspace of the mill. */
    suspend fun millWorkspace(): Workspace = machines.workspaces(mill.id).first().single().let { machines.loadWorkspace(it.id)!! }

    /** Calls the tool [name] with [arguments]. */
    suspend fun call(name: String, arguments: JsonObject): ToolResult {
        val tool = byName[name] ?: fail("No tool $name")
        return tool.handler.call(ToolCall(name, arguments, McpClient("192.168.1.9", McpEra.Modern)) { _, _, _ -> })
    }

    /** Calls the tool and expects a success. */
    suspend fun ok(name: String, arguments: JsonObject): ToolResult.Ok {
        val result = call(name, arguments)
        return assertIs<ToolResult.Ok>(result, "The call of $name failed: ${(result as? ToolResult.Error)?.message}")
    }

    /** Calls the tool and expects an error. */
    suspend fun error(name: String, arguments: JsonObject): ToolResult.Error = assertIs(call(name, arguments))

    /** Keeps an upload and gives its ID. */
    suspend fun upload(contentType: String, bytes: ByteArray): String = files.putUpload(contentType, bytes).fileId

    fun close() = driver.close()
}

/** Builds the arguments of a call. */
fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

val ToolResult.Ok.obj: JsonObject get() = structured!!.jsonObject

fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content

fun JsonElement.num(key: String): Double = jsonObject.getValue(key).jsonPrimitive.content.toDouble()

fun JsonElement.int(key: String): Int = jsonObject.getValue(key).jsonPrimitive.content.toInt()

fun JsonElement.bool(key: String): Boolean = jsonObject.getValue(key).jsonPrimitive.content.toBoolean()

fun JsonElement.list(key: String): JsonArray = jsonObject.getValue(key).jsonArray

fun jsonList(vararg values: Any): JsonArray = JsonArray(
    values.map { value ->
        when (value) {
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> error("Unsupported $value")
        }
    },
)

/** A wireframe document with one line and one circle, in nm. */
val LINE_AND_CIRCLE: JsonObject = buildJsonObject {
    put("schema", JsonPrimitive("touchdro.wireframe"))
    put("v", JsonPrimitive("3.0"))
    put("layers", jsonList(buildJsonObject { put("n", JsonPrimitive("Outline")) }))
    put(
        "entities",
        jsonList(
            buildJsonObject {
                put("t", JsonPrimitive("L"))
                put("l", JsonPrimitive(0))
                put("p", jsonList(jsonList(0, 0), jsonList(10_000_000, 0)))
            },
            buildJsonObject {
                put("t", JsonPrimitive("C"))
                put("l", JsonPrimitive(0))
                put("c", jsonList(5_000_000, 5_000_000))
                put("r", JsonPrimitive(2_000_000))
            },
        ),
    )
}

/** A closed triangle insert of 10 mm with the tip at the origin: a valid lathe tool outline. */
val TRIANGLE: JsonObject = buildJsonObject {
    put("schema", JsonPrimitive("touchdro.wireframe"))
    put("v", JsonPrimitive("3.0"))
    put("layers", jsonList(buildJsonObject { put("n", JsonPrimitive("0")) }))
    put(
        "entities",
        jsonList(
            buildJsonObject {
                put("t", JsonPrimitive("P"))
                put("l", JsonPrimitive(0))
                put("cl", JsonPrimitive(1))
                put("pts", jsonList(jsonList(0, 0), jsonList(10_000_000, 2_000_000), jsonList(10_000_000, -2_000_000)))
            },
        ),
    )
}

/** A PNG header of a 32 × 16 image: the signature and the IHDR chunk. */
val PNG_32_16: ByteArray = byteArrayOf(
    0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
    0, 0, 0, 32, 0, 0, 0, 16, 8, 2, 0, 0, 0,
)

/** A JPEG head of a 640 × 480 image: SOI and a SOF0 segment. */
val JPEG_640_480: ByteArray = byteArrayOf(
    0xFF.toByte(), 0xD8.toByte(),
    0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00,
    0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x01, 0xE0.toByte(), 0x02, 0x80.toByte(), 0x03,
)

/** The gate of the open tests. */
val openGate: WriteGate = OpenWriteGate
