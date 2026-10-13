package org.superdro.core.store.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.Tool
import org.superdro.core.model.ToolKind
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter

/**
 * The tools of the profile libraries and of the shared library.
 *
 * With [MachineProfile.useSharedToolLibrary] off (the default), a profile uses its own tools.
 * With it on, the profile uses the shared library. The library always filters by machine kind:
 * the mill tools for both mill types, the lathe tools for a lathe. A change of the switch
 * takes effect at once: [library] reads the profile row, so it gives the new library after
 * the change.
 */
class ToolRepository(
    private val writer: StoreWriter,
    private val json: Json = SuperDroJson,
    private val clock: Clock = Clock.System,
) {
    private val database get() = writer.database

    /** The library that the profile [profileId] uses now, sorted by name. */
    fun library(profileId: String): Flow<List<Tool>> =
        database.toolQueries.selectLibraryForProfile(profileId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map(json::toolFrom) }

    /** Reads the library of the profile [profileId] one time. */
    suspend fun loadLibrary(profileId: String): List<Tool> =
        writer.read { db -> db.toolQueries.selectLibraryForProfile(profileId).executeAsList().map(json::toolFrom) }

    /** The own tools of the profile [profileId], also when the profile uses the shared library. */
    fun profileTools(profileId: String): Flow<List<Tool>> =
        database.toolQueries.selectForProfile(profileId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map(json::toolFrom) }

    /** The tools of the shared library with the kind [kind]. */
    fun sharedTools(kind: ToolKind): Flow<List<Tool>> =
        database.toolQueries.selectShared(kind).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map(json::toolFrom) }

    /**
     * Turns the shared library on or off for the profile [profileId]. The [library] flow
     * gives the other library at once.
     */
    suspend fun setUseSharedLibrary(profileId: String, useShared: Boolean) {
        writer.write { db -> db.machineProfileQueries.setUseSharedTools(useShared, clock.now(), profileId) }
    }

    /**
     * Adds [tool] to the library that the profile [profileId] uses now. The tool kind
     * comes from the machine type of the profile: a mill tool on both mill types.
     */
    suspend fun addTool(profileId: String, tool: Tool): StoreResult<Tool> = writer.write { db ->
        val profile = db.machineProfileQueries.selectById(profileId).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val now = clock.now()
        val stored = tool.copy(kind = toolKindFor(profile.machine_type), createdOn = now, updatedOn = now)
        db.insertTool(json, if (profile.use_shared_tools) null else profileId, stored)
        StoreResult.Ok(stored)
    }

    /** Writes the changed values of [tool]. The tool stays in its library. */
    suspend fun updateTool(tool: Tool): StoreResult<Tool> = writer.write { db ->
        val row = db.toolQueries.selectById(tool.id).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val stored = tool.copy(createdOn = row.created_on, updatedOn = clock.now())
        db.updateTool(json, stored)
        StoreResult.Ok(stored)
    }

    /**
     * Writes [tools] in one transaction (the CSV import). A tool with the ID of a
     * stored tool updates that tool. Each other tool goes into the library that the profile
     * [profileId] uses now.
     */
    suspend fun saveTools(profileId: String, tools: List<Tool>): StoreResult<ToolSaveCounts> = writer.write { db ->
        val profile = db.machineProfileQueries.selectById(profileId).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val library = if (profile.use_shared_tools) null else profileId
        val now = clock.now()
        var added = 0
        var updated = 0
        tools.forEach { tool ->
            val existing = db.toolQueries.selectById(tool.id).executeAsOneOrNull()
            if (existing != null) {
                db.updateTool(json, tool.copy(createdOn = existing.created_on, updatedOn = now))
                updated++
            } else {
                db.insertTool(json, library, tool.copy(createdOn = now, updatedOn = now))
                added++
            }
        }
        StoreResult.Ok(ToolSaveCounts(added = added, updated = updated))
    }

    /** Deletes the tools [ids] in one transaction. */
    suspend fun deleteTools(ids: Collection<String>) {
        writer.write { db -> ids.forEach { id -> db.toolQueries.deleteById(id) } }
    }
}

/** The counts of [ToolRepository.saveTools]: "Tool import finished: <a> tools added, <u> tools updated." */
data class ToolSaveCounts(val added: Int, val updated: Int)
