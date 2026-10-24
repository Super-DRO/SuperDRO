package org.superdro.core.store.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.Mcp_call

/**
 * One row of the audit log of the MCP server.
 *
 * @property id The row ID. The database gives it at the insert. The value of a new row is 0.
 * @property atMs The wall clock time of the call, in milliseconds since 1970-01-01 00:00 UTC.
 * @property client The host of the client.
 * @property era The protocol era of the client, `Modern` or `Legacy`.
 * @property tool The tool name, or null for a request that is not a tool call.
 * @property method The JSON-RPC method, for example `tools/call`.
 * @property argumentsJson The arguments of the call as JSON, or null.
 * @property result A short result text, for example `ok`, `error`, `refused`, or `timeout`.
 * @property durationMs The time of the call, in milliseconds.
 * @property previousJson The record before a write, as JSON, or null when the call did not write.
 */
data class McpCallRow(
    val id: Long,
    val atMs: Long,
    val client: String,
    val era: String,
    val tool: String?,
    val method: String,
    val argumentsJson: String?,
    val result: String,
    val durationMs: Long,
    val previousJson: String?,
)

/**
 * The audit log of the MCP server. Each write of an MCP tool goes through the [StoreWriter], so
 * a log row and the write that it describes do not run at the same time.
 */
class McpCallRepository(private val writer: StoreWriter) {

    private val database get() = writer.database

    /**
     * Adds [row] to the log. The database gives the ID; the `id` of [row] is not used.
     *
     * @return The ID of the new row.
     */
    suspend fun insert(row: McpCallRow): Long = writer.write { db ->
        db.mcpCallQueries.insert(
            at_ms = row.atMs,
            client = row.client,
            era = row.era,
            tool = row.tool,
            method = row.method,
            arguments_json = row.argumentsJson,
            result = row.result,
            duration_ms = row.durationMs,
            previous_json = row.previousJson,
        )
        db.mcpCallQueries.lastInsertRowId().executeAsOne()
    }

    /** The last [count] rows, the newest first. The flow gives a new list after each change. */
    fun last(count: Int): Flow<List<McpCallRow>> =
        database.mcpCallQueries.last(count.toLong()).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map(Mcp_call::toRow) }

    /** The number of rows of the log. */
    suspend fun count(): Long = writer.read { db -> db.mcpCallQueries.count().executeAsOne() }

    /** Deletes each row with a time before [olderThanMs] (a wall clock time in milliseconds). */
    suspend fun prune(olderThanMs: Long) {
        writer.write { db -> db.mcpCallQueries.pruneBefore(olderThanMs) }
    }
}

private fun Mcp_call.toRow(): McpCallRow = McpCallRow(
    id = id,
    atMs = at_ms,
    client = client,
    era = era,
    tool = tool,
    method = method,
    argumentsJson = arguments_json,
    result = result,
    durationMs = duration_ms,
    previousJson = previous_json,
)
