package org.superdro.core.store.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.toxicbakery.logging.Arbor
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.superdro.core.model.MeasurementLogEntry
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.Measurement_log

/**
 * The inspection log of each workspace. The sequence starts at 1
 * in each workspace. The append reads the last sequence and inserts the row in one transaction,
 * so two appends cannot get the same number. A workspace delete removes its log.
 */
class MeasurementLogRepository(
    private val writer: StoreWriter,
    private val json: Json = SuperDroJson,
    private val clock: Clock = Clock.System,
) {
    private val database get() = writer.database

    /**
     * The log entries of the workspace in the order of the sequence. A row that does not decode is
     * skipped.
     *
     * The flow keeps the decoded entry of each row. A change of the log decodes only the new and
     * the changed rows, not all rows. The decode runs on the read dispatcher.
     */
    fun entries(workspaceId: String): Flow<List<MeasurementLogEntry>> = flow {
        var decoded = emptyMap<String, DecodedRow>()
        emitAll(
            database.measurementLogQueries.selectForWorkspace(workspaceId).asFlow().mapToList(writer.readDispatcher)
                .map { rows ->
                    val next = HashMap<String, DecodedRow>(rows.size)
                    val entries = rows.mapNotNull { row ->
                        val known = decoded[row.id]?.takeIf { known -> known.row == row } ?: DecodedRow(row, entryFrom(row))
                        next[row.id] = known
                        known.entry
                    }
                    decoded = next
                    entries
                },
        )
    }.flowOn(writer.readDispatcher)

    /** One row and its decoded entry, or null when the row does not decode. */
    private class DecodedRow(val row: Measurement_log, val entry: MeasurementLogEntry?)

    /** The number of log entries of the workspace. */
    fun count(workspaceId: String): Flow<Long> =
        database.measurementLogQueries.countForWorkspace(workspaceId).asFlow().mapToOne(writer.readDispatcher)

    /**
     * Appends [entry] to the log of the workspace [workspaceId] in one transaction. The entry gets
     * the next sequence number (the last number + 1, or 1 for the first entry), the workspace ID,
     * and the time of the append.
     *
     * @return The stored entry with its sequence number.
     */
    suspend fun append(workspaceId: String, entry: MeasurementLogEntry): StoreResult<MeasurementLogEntry> = writer.write { db ->
        db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val sequence = (db.measurementLogQueries.maxSequence(workspaceId).executeAsOne().value_ ?: 0) + 1
        val stored = entry.copy(workspaceId = workspaceId, sequence = sequence, createdOn = clock.now())
        db.measurementLogQueries.insert(
            id = stored.id,
            workspace_id = workspaceId,
            sequence = sequence,
            verb = stored.verb,
            payload_json = json.encodeToString(MeasurementLogEntry.serializer(), stored),
            created_on = stored.createdOn,
        )
        StoreResult.Ok(stored)
    }

    /** Deletes one log entry. The other entries keep their sequence numbers. */
    suspend fun delete(id: String) {
        writer.write { db -> db.measurementLogQueries.deleteById(id) }
    }

    /** Deletes the full log of the workspace. The next entry gets the sequence 1. */
    suspend fun clear(workspaceId: String) {
        writer.write { db -> db.measurementLogQueries.deleteForWorkspace(workspaceId) }
    }

    private fun entryFrom(row: Measurement_log): MeasurementLogEntry? = try {
        json.decodeFromString(MeasurementLogEntry.serializer(), row.payload_json).copy(
            id = row.id,
            workspaceId = row.workspace_id,
            sequence = row.sequence,
            verb = row.verb,
            createdOn = row.created_on,
        )
    } catch (error: IllegalArgumentException) {
        log.e(error) { "Cannot decode the log entry ${row.id}. The list skips it." }
        null
    }

    private companion object {
        val log = Arbor.tag("MeasurementLogRepository")
    }
}
