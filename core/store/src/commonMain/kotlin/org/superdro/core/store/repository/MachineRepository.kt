package org.superdro.core.store.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineDatum
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.ThreadSpec
import org.superdro.core.model.Workspace
import org.superdro.core.model.newId
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.Machine_profile
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.files.subdatum.SubDatumImportPlan

/**
 * The machine profiles and their data: the
 * profiles, the workspaces, the axis states, the sub-datums with all their tags, the
 * guides, the machine datums, and the threads.
 *
 * All writes go through [writer], one transaction for each call.
 * The reads are flows of the SQLDelight coroutines extensions. A flow gives the current value
 * first, and then a new value after each change of its tables.
 *
 * A profile of [profiles] and [profile] holds the settings only: the lists
 * [MachineProfile.workspaces], [MachineProfile.tools], and [MachineProfile.machineDatums] are
 * empty. [loadFullProfile] gives the full profile.
 *
 * @param storeIO Deletes the reference image files of a deleted workspace.
 * @param clock Gives the times of [MachineProfile.updatedOn] and the other dates.
 */
class MachineRepository(
    private val writer: StoreWriter,
    private val storeIO: StoreIOProvider,
    private val json: Json = SuperDroJson,
    private val clock: Clock = Clock.System,
) {
    private val database: SuperDroDatabase get() = writer.database

    private fun now(): Instant = clock.now()

    // ---------------------------------------------------------------------------------------
    // Profiles

    /** The settings of all profiles, sorted by name. */
    fun profiles(): Flow<List<MachineProfile>> =
        database.machineProfileQueries.selectAll().asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map(json::profileFrom) }

    /** The settings of the profile [id], or null when it does not exist. */
    fun profile(id: String): Flow<MachineProfile?> =
        database.machineProfileQueries.selectById(id).asFlow().mapToOneOrNull(writer.readDispatcher)
            .map { row -> row?.let(json::profileFrom) }

    /** Reads the settings of the profile [id] one time. */
    suspend fun loadProfile(id: String): MachineProfile? =
        writer.read { db -> db.machineProfileQueries.selectById(id).executeAsOneOrNull()?.let(json::profileFrom) }

    /**
     * Reads the full profile [id] in one transaction: the settings, the workspaces with their
     * axis states, sub-datums, and guides, the tools of the profile library, and the machine
     * datums.
     */
    suspend fun loadFullProfile(id: String): MachineProfile? = writer.read { db ->
        val row = db.machineProfileQueries.selectById(id).executeAsOneOrNull() ?: return@read null
        db.fullProfile(row)
    }

    /**
     * Writes a new profile with its workspaces, tools, and machine datums in one transaction.
     * The name gets a trim. A profile with no workspace gets one "Default Workspace". An adapter
     * profile ID that the store does not have becomes null.
     *
     * Refusals: [StoreRefusal.ProfileNameBlank], [StoreRefusal.ProfileNameExists], and
     * [StoreRefusal.WorkspaceNameBlank] or [StoreRefusal.WorkspaceNameExists] for the workspaces.
     *
     * @return The settings of the stored profile.
     */
    suspend fun createProfile(profile: MachineProfile): StoreResult<MachineProfile> = writer.write { db ->
        db.profileNameRefusal(profile.name, exceptId = null)?.let { return@write StoreResult.Refused(it) }
        workspaceListRefusal(profile.workspaces)?.let { return@write StoreResult.Refused(it) }
        db.insertFullProfile(profile)
        StoreResult.Ok(db.settingsOf(profile.id))
    }

    /**
     * Writes the settings of [profile]. The workspaces, the tools, and the machine datums
     * of the argument are not written. The machine type does not change: use
     * [changeMachineType]. A new name gets the rules of [renameProfile].
     */
    suspend fun updateProfile(profile: MachineProfile): StoreResult<MachineProfile> = writer.write { db ->
        val row = db.machineProfileQueries.selectById(profile.id).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.writeSettings(row, profile)
    }

    /**
     * Reads the settings of the profile [id], applies [transform], and writes the result in one
     * transaction. [transform] must have no side effect.
     */
    suspend fun updateProfile(id: String, transform: (MachineProfile) -> MachineProfile): StoreResult<MachineProfile> =
        writer.write { db ->
            val row = db.machineProfileQueries.selectById(id).executeAsOneOrNull()
                ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
            db.writeSettings(row, transform(json.profileFrom(row)).copy(id = id))
        }

    /** Renames the profile [id]. The name gets a trim and must be unique. */
    suspend fun renameProfile(id: String, name: String): StoreResult<MachineProfile> = writer.write { db ->
        db.machineProfileQueries.selectById(id).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.profileNameRefusal(name, exceptId = id)?.let { return@write StoreResult.Refused(it) }
        db.machineProfileQueries.rename(name = name.trim(), updatedOn = now(), id = id)
        StoreResult.Ok(db.settingsOf(id))
    }

    /**
     * Makes a copy of the profile [id] with the name [name]. The copy duplicates the
     * settings and the tools of the profile library, not the workspaces and not the machine
     * datums. It gets one new "Default Workspace". The copy has no adapter link and no camera,
     * because each profile is one machine. A probe role that points to a probe profile of
     * the source profile becomes null.
     */
    suspend fun copyProfile(id: String, name: String): StoreResult<MachineProfile> = writer.write { db ->
        val row = db.machineProfileQueries.selectById(id).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.profileNameRefusal(name, exceptId = null)?.let { return@write StoreResult.Refused(it) }
        val source = json.profileFrom(row)
        val ownProbes = db.probeQueries.selectForProfile(id).executeAsList()
            .filter { probe -> probe.profile_id == id }
            .mapTo(HashSet()) { probe -> probe.id }
        val roles = source.probeRoles.let { roles ->
            ProbeRoles(
                edgeFinderProfileId = roles.edgeFinderProfileId?.takeUnless { it in ownProbes },
                zReferenceProfileId = roles.zReferenceProfileId?.takeUnless { it in ownProbes },
                measurementInstrumentProfileId = roles.measurementInstrumentProfileId?.takeUnless { it in ownProbes },
            )
        }
        val now = now()
        val workspace = Workspace.forMachine(source.machineType).copy(createdOn = now, updatedOn = now)
        val tools = db.toolQueries.selectForProfile(id).executeAsList().map { tool ->
            json.toolFrom(tool).copy(id = newId(), createdOn = now, updatedOn = now)
        }
        val copy = source.copy(
            id = newId(),
            name = name.trim(),
            adapter = null,
            adapterProfileId = null,
            cameraId = null,
            probeRoles = roles,
            workspaces = listOf(workspace),
            activeWorkspaceId = workspace.id,
            tools = tools,
            machineDatums = emptyList(),
            createdOn = now,
            updatedOn = now,
        )
        db.insertFullProfile(copy)
        StoreResult.Ok(db.settingsOf(copy.id))
    }

    /**
     * Deletes the profile [id] with all its data in one transaction: the
     * workspaces with their axis states, sub-datums, guides, threads, drawings, and inspection
     * logs; the machine datums; the probe profiles of the profile; the camera selections; and the
     * archive. The reference image files go after the commit.
     *
     * The UI asks "Delete the tools of this profile too?" and gives the answer in
     * [deleteTools]. On true, the tools of the profile library are deleted. On false, they move to
     * the shared library.
     *
     * The caller also removes the profile from `TabStore` (its tab closes).
     *
     * @return False when the profile does not exist.
     */
    suspend fun deleteProfile(id: String, deleteTools: Boolean): Boolean {
        val images = writer.write<List<String>?> { db ->
            db.machineProfileQueries.selectById(id).executeAsOneOrNull() ?: return@write null
            val images = db.workspaceQueries.selectImagesForProfile(id).executeAsList()
                .mapNotNull { text -> json.referenceImageFrom(text)?.fileName }
            if (!deleteTools) db.toolQueries.moveProfileToolsToShared(id)
            db.machineProfileQueries.deleteById(id)
            images
        } ?: return false
        images.forEach(::deleteFile)
        return true
    }

    /** Gives the confirmation list of a change of the profile [id] to the type [to]. */
    suspend fun planMachineTypeChange(id: String, to: MachineType): MachineTypeChangePlan? = writer.read { db ->
        db.machineProfileQueries.selectById(id).executeAsOneOrNull()?.let { row -> db.planMachineTypeChange(json, row, to) }
    }

    /**
     * Changes the machine type of the profile [id] in one transaction. The
     * data that does not fit the new type goes to the archive or is deleted ([disposal]). The
     * store converts the rest: the mill types share the mill tools, the workspace plane and
     * the X diameter mode change, a lathe leaves the measurement mode, and the pattern angle
     * convention changes (a lathe gets `Legacy`, a mill from a lathe gets `Standard`). Archived
     * data that fits the new type comes back.
     *
     * @return The plan that the store applied.
     */
    suspend fun changeMachineType(
        id: String,
        to: MachineType,
        disposal: MismatchDisposal = MismatchDisposal.Archive,
    ): StoreResult<MachineTypeChangePlan> = writer.write { db ->
        val row = db.machineProfileQueries.selectById(id).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        StoreResult.Ok(db.writeMachineTypeChange(json, now(), row, to, disposal))
    }

    /** The archive of the profile [profileId]: the data that a machine type change hides. */
    fun archivedItems(profileId: String): Flow<List<ArchivedItem>> =
        database.archiveQueries.selectForProfile(profileId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() } }

    // ---------------------------------------------------------------------------------------
    // Workspaces

    /**
     * The workspaces of the profile [profileId] in the order of creation, each with its axis
     * states. The sub-datums and the guides are empty: use [workspace] or [subDatums].
     */
    fun workspaces(profileId: String): Flow<List<Workspace>> = writer.observe(
        database.workspaceQueries.selectForProfile(profileId),
        database.workspaceQueries.selectAxisStatesForProfile(profileId),
    ) { db ->
        val axisRows = db.workspaceQueries.selectAxisStatesForProfile(profileId).executeAsList().groupBy { it.workspace_id }
        db.workspaceQueries.selectForProfile(profileId).executeAsList().map { row ->
            json.workspaceFrom(row, axisRows[row.id].orEmpty())
        }
    }

    /** The full workspace [id]: the state, the axis states, the sub-datums, and the guides. */
    fun workspace(id: String): Flow<Workspace?> = writer.observe(
        database.workspaceQueries.selectById(id),
        database.workspaceQueries.selectAxisStates(id),
        database.subDatumQueries.selectForWorkspace(id),
        database.subDatumQueries.selectGuides(id),
    ) { db -> db.fullWorkspace(id) }

    /**
     * The full active workspace of the profile [profileId]: the workspace of
     * [MachineProfile.activeWorkspaceId], else the first workspace.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun activeWorkspace(profileId: String): Flow<Workspace?> = writer.observe(
        database.machineProfileQueries.selectById(profileId),
        database.workspaceQueries.selectForProfile(profileId),
    ) { db -> db.activeWorkspaceId(profileId) }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else workspace(id) }

    /** Reads the full workspace [id] one time. */
    suspend fun loadWorkspace(id: String): Workspace? = writer.read { db -> db.fullWorkspace(id) }

    /** The state of each axis of the workspace [workspaceId]. */
    fun axisStates(workspaceId: String): Flow<Map<AxisId, AxisState>> =
        database.workspaceQueries.selectAxisStates(workspaceId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.associate { row -> row.axis to row.toModel() } }

    /**
     * Makes a workspace with the name [name] in the profile [profileId]. The new
     * workspace gets the defaults of the machine type. It does not become the active workspace.
     *
     * Refusals: "Workspace name can't be blank." and "A workspace with this name already exists."
     */
    suspend fun createWorkspace(profileId: String, name: String): StoreResult<Workspace> = writer.write { db ->
        val profile = db.machineProfileQueries.selectById(profileId).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.workspaceNameRefusal(profileId, name, exceptId = null)?.let { return@write StoreResult.Refused(it) }
        val now = now()
        val workspace = Workspace.forMachine(profile.machine_type, name.trim()).copy(createdOn = now, updatedOn = now)
        db.insertWorkspace(profileId, workspace)
        StoreResult.Ok(workspace)
    }

    /** Renames the workspace [id] with the rules of [createWorkspace]. */
    suspend fun renameWorkspace(id: String, name: String): StoreResult<Workspace> = writer.write { db ->
        val row = db.workspaceQueries.selectById(id).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.workspaceNameRefusal(row.profile_id, name, exceptId = id)?.let { return@write StoreResult.Refused(it) }
        db.workspaceQueries.rename(name = name.trim(), updatedOn = now(), id = id)
        StoreResult.Ok(db.fullWorkspace(id)!!)
    }

    /**
     * Selects the workspace [workspaceId] of the profile [profileId]. In one
     * transaction, the store keeps [currentAxisStates] in the old active workspace, sets the new
     * active workspace, and reads the full state of the new workspace: the offsets, the modes,
     * the active tool, the active sub-datum, the proximity target, and the graphical switches.
     *
     * @param currentAxisStates The axis states of the old workspace that the caller holds, or null
     *   when the store has them already.
     */
    suspend fun selectWorkspace(
        profileId: String,
        workspaceId: String,
        currentAxisStates: Map<AxisId, AxisState>? = null,
    ): StoreResult<Workspace> = writer.write { db ->
        db.machineProfileQueries.selectById(profileId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val target = db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull()
        if (target == null || target.profile_id != profileId) return@write StoreResult.Refused(StoreRefusal.NotFound)
        val oldId = db.activeWorkspaceId(profileId)
        if (currentAxisStates != null && oldId != null) {
            currentAxisStates.forEach { (axis, state) -> db.writeAxisState(oldId, axis, state) }
        }
        db.machineProfileQueries.setActiveWorkspace(workspaceId = workspaceId, updatedOn = now(), id = profileId)
        StoreResult.Ok(db.fullWorkspace(workspaceId)!!)
    }

    /**
     * Deletes the workspace [id] with its data: the axis states, the sub-datums, the
     * guides, the threads, the drawing, the inspection log, and the reference image file.
     * The last workspace of a profile stays: "The last workspace can't be deleted."
     */
    suspend fun deleteWorkspace(id: String): StoreResult<Unit> {
        val profileId = writer.read { db -> db.workspaceQueries.selectById(id).executeAsOneOrNull()?.profile_id }
            ?: return StoreResult.Refused(StoreRefusal.NotFound)
        val result = deleteWorkspaces(profileId, listOf(id))
        return result.refusal?.let { StoreResult.Refused(it) } ?: StoreResult.Ok(Unit)
    }

    /**
     * Deletes the workspaces [ids] of the profile [profileId] in one transaction. A
     * selection of all workspaces keeps one: the active workspace, else the first. The result
     * then has the refusal "The last workspace can't be deleted." and no exception occurs. When
     * the active workspace goes, the first workspace that stays becomes active. The reference
     * image files go after the commit.
     */
    suspend fun deleteWorkspaces(profileId: String, ids: Collection<String>): WorkspaceDeleteResult {
        val (result, images) = writer.write { db ->
            val all = db.workspaceQueries.selectForProfile(profileId).executeAsList()
            val activeId = db.activeWorkspaceId(profileId)
            val targets = all.filter { row -> row.id in ids }
            var keptId: String? = null
            var refusal: StoreRefusal? = null
            val deleted = if (targets.isNotEmpty() && targets.size == all.size) {
                keptId = activeId?.takeIf { id -> targets.any { it.id == id } } ?: all.first().id
                refusal = StoreRefusal.LastWorkspace
                targets.filter { row -> row.id != keptId }
            } else {
                targets
            }
            val images = deleted.mapNotNull { row -> json.referenceImageFrom(row.reference_image_json)?.fileName }
            deleted.forEach { row -> db.workspaceQueries.deleteById(row.id) }
            val newActive = if (activeId != null && deleted.any { it.id == activeId }) {
                all.first { row -> deleted.none { it.id == row.id } }.id.also { id ->
                    db.machineProfileQueries.setActiveWorkspace(workspaceId = id, updatedOn = now(), id = profileId)
                }
            } else {
                activeId
            }
            WorkspaceDeleteResult(deleted.map { it.id }, keptId, refusal, newActive) to images
        }
        images.forEach(::deleteFile)
        return result
    }

    /**
     * Reads the workspace [id], applies [transform], and writes the changed state in one
     * transaction: the typed state, the axis states, the last ordinal, and the
     * reference image. A new reference image file removes the old file. The name, the
     * sub-datums, and the guides do not change here: use [renameWorkspace] and the sub-datum
     * functions.
     */
    suspend fun updateWorkspace(id: String, transform: (Workspace) -> Workspace): StoreResult<Workspace> {
        var oldFile: String? = null
        val result = writer.write { db ->
            val row = db.workspaceQueries.selectById(id).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
            val current = json.workspaceFrom(row, db.workspaceQueries.selectAxisStates(id).executeAsList())
            val next = transform(current)
            val now = now()
            val stateJson = json.workspaceStateJson(next)
            if (stateJson != row.state_json) db.workspaceQueries.updateState(stateJson, now, id)
            AxisId.entries.forEach { axis ->
                val state = next.axisStates[axis] ?: AxisState()
                if (state != current.axisStates[axis]) db.writeAxisState(id, axis, state)
            }
            if (next.lastOrdinal != current.lastOrdinal) db.workspaceQueries.updateLastOrdinal(next.lastOrdinal, now, id)
            if (next.referenceImage != current.referenceImage) {
                db.workspaceQueries.updateReferenceImage(json.referenceImageJson(next.referenceImage), now, id)
                val old = current.referenceImage?.fileName
                if (old != null && old.isNotEmpty() && old != next.referenceImage?.fileName) oldFile = old
            }
            StoreResult.Ok(db.fullWorkspace(id)!!)
        }
        oldFile?.let(::deleteFile)
        return result
    }

    /** Writes the state of one axis: one row. The offsets stay after a restart. */
    suspend fun setAxisState(workspaceId: String, axis: AxisId, state: AxisState) {
        writer.write { db -> db.writeAxisState(workspaceId, axis, state) }
    }

    /** Writes the states of more axes in one transaction. */
    suspend fun setAxisStates(workspaceId: String, states: Map<AxisId, AxisState>) {
        writer.write { db -> states.forEach { (axis, state) -> db.writeAxisState(workspaceId, axis, state) } }
    }

    /** Sets or clears the reference image of the workspace. A new image file removes the old file. */
    suspend fun setReferenceImage(workspaceId: String, image: ReferenceImage?): StoreResult<Workspace> =
        updateWorkspace(workspaceId) { workspace -> workspace.copy(referenceImage = image) }

    // ---------------------------------------------------------------------------------------
    // Sub-datums and guides

    /** The sub-datums of the workspace in the list order, each with all its tags. */
    fun subDatums(workspaceId: String): Flow<List<SubDatum>> =
        database.subDatumQueries.selectForWorkspace(workspaceId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.toSubDatums() }

    /** The guides of the workspace. */
    fun guides(workspaceId: String): Flow<List<Guide>> =
        database.subDatumQueries.selectGuides(workspaceId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() } }

    /** Adds one sub-datum with the naming rule of [addSubDatums]. */
    suspend fun addSubDatum(workspaceId: String, subDatum: SubDatum): StoreResult<SubDatum> =
        when (val result = addSubDatums(workspaceId, listOf(subDatum))) {
            is StoreResult.Ok -> StoreResult.Ok(result.value.single())
            is StoreResult.Refused -> result
        }

    /**
     * Adds [subDatums] to the workspace in one transaction.
     *
     * The ordinal counter is the last ordinal of the workspace, or the number of its sub-datums
     * when the last ordinal is 0. Each new point gets the next number n. A point with a blank
     * name gets the name `Point <n>`. With [namePrefix] (a group, for example a hole pattern),
     * each point gets the name `<namePrefix> - <n>`. The workspace then keeps the last number
     * that it used, so a name does not repeat.
     */
    suspend fun addSubDatums(
        workspaceId: String,
        subDatums: List<SubDatum>,
        namePrefix: String? = null,
    ): StoreResult<List<SubDatum>> = writer.write { db ->
        val row = db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        var counter = row.last_ordinal.takeIf { it > 0 } ?: db.subDatumQueries.countForWorkspace(workspaceId).executeAsOne().toInt()
        val now = now()
        val named = subDatums.map { point ->
            counter += 1
            val name = when {
                namePrefix != null -> "$namePrefix - $counter"
                point.name.isBlank() -> "Point $counter"
                else -> point.name
            }
            point.copy(name = name, createdOn = now, updatedOn = now)
        }
        named.forEach { point -> db.writeSubDatum(workspaceId, point) }
        db.workspaceQueries.updateLastOrdinal(counter, now, workspaceId)
        StoreResult.Ok(named)
    }

    /**
     * Writes [subDatums] to the workspace as they are, in one transaction: no automatic names and
     * no change of the ordinal counter. For the CSV import and the undo of a delete.
     */
    suspend fun insertSubDatums(workspaceId: String, subDatums: List<SubDatum>): StoreResult<Unit> = writer.write { db ->
        db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        subDatums.forEach { point -> db.writeSubDatum(workspaceId, point) }
        StoreResult.Ok(Unit)
    }

    /** Writes the changed sub-datum [subDatum] with all its tags. */
    suspend fun updateSubDatum(subDatum: SubDatum): StoreResult<SubDatum> = writer.write { db ->
        val row = db.subDatumQueries.selectById(subDatum.id).executeAsOneOrNull()
            ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val stored = subDatum.copy(createdOn = row.created_on, updatedOn = now())
        db.writeSubDatum(row.workspace_id, stored)
        StoreResult.Ok(stored)
    }

    /** Sets the tag list [tags] on each sub-datum of [ids] in one transaction ("Set Sub-Datum Tag"). */
    suspend fun setTags(ids: Collection<String>, tags: List<String>) {
        writer.write { db ->
            val now = now()
            ids.forEach { id ->
                val row = db.subDatumQueries.selectById(id).executeAsOneOrNull() ?: return@forEach
                db.subDatumQueries.deleteTags(id)
                tags.distinct().forEachIndexed { position, tag -> db.subDatumQueries.insertTag(id, position, tag) }
                db.subDatumQueries.upsert(
                    row.id, row.workspace_id, row.name, row.note, row.x_um, row.y_um, row.z_um, row.a_arcsec,
                    row.group_id, row.group_kind, row.group_ref, row.created_on, now,
                )
            }
        }
    }

    /**
     * Deletes the sub-datums [ids] of the workspace in one transaction. A delete of the
     * active sub-datum clears it. When no sub-datum stays, the ordinal counter goes to 0.
     *
     * @return The deleted sub-datums, so that the caller can give an undo for 10 s.
     */
    suspend fun deleteSubDatums(workspaceId: String, ids: Collection<String>): List<SubDatum> = writer.write { db ->
        val current = db.subDatumQueries.selectForWorkspace(workspaceId).executeAsList().toSubDatums()
        val deleted = current.filter { point -> point.id in ids }
        deleted.forEach { point -> db.subDatumQueries.deleteById(point.id) }
        db.afterSubDatumDelete(workspaceId, deleted.mapTo(HashSet()) { it.id })
        deleted
    }

    /** Deletes all sub-datums of the workspace. The ordinal counter goes to 0. */
    suspend fun deleteAllSubDatums(workspaceId: String): List<SubDatum> = writer.write { db ->
        val current = db.subDatumQueries.selectForWorkspace(workspaceId).executeAsList().toSubDatums()
        db.subDatumQueries.deleteForWorkspace(workspaceId)
        db.afterSubDatumDelete(workspaceId, current.mapTo(HashSet()) { it.id })
        current
    }

    /**
     * Writes the plan of a sub-datum CSV import in one transaction: for
     * [SubDatumImportPlan.deleteAll] ("Replace all") the delete of all sub-datums and
     * guides of the workspace first, then the new and the changed sub-datums as they are, then the
     * new guides. The ordinal counter does not change, except that a delete of all points sets it
     * to 0 when no point stays.
     */
    suspend fun applySubDatumImport(workspaceId: String, plan: SubDatumImportPlan): StoreResult<Unit> = writer.write { db ->
        db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        if (plan.deleteAll) {
            val current = db.subDatumQueries.selectForWorkspace(workspaceId).executeAsList().toSubDatums()
            db.subDatumQueries.deleteForWorkspace(workspaceId)
            db.subDatumQueries.deleteGuidesForWorkspace(workspaceId)
            db.afterSubDatumDelete(workspaceId, current.mapTo(HashSet()) { it.id })
        }
        (plan.addSubDatums + plan.updateSubDatums).forEach { point -> db.writeSubDatum(workspaceId, point) }
        plan.addGuides.forEach { guide -> db.writeGuide(workspaceId, guide) }
        StoreResult.Ok(Unit)
    }

    /**
     * The undo of a delete of sub-datums and guides, in one transaction: [subDatums] and
     * [guides] come back as they are. The active sub-datum comes back when [activeSubDatumId] is one
     * of [subDatums]. The ordinal counter becomes
     * `max(current, lastOrdinal)`, so that a name does not repeat.
     */
    suspend fun restoreSubDatums(
        workspaceId: String,
        subDatums: List<SubDatum>,
        guides: List<Guide>,
        activeSubDatumId: String?,
        lastOrdinal: Int,
    ): StoreResult<Unit> = writer.write { db ->
        val row = db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        subDatums.forEach { point -> db.writeSubDatum(workspaceId, point) }
        guides.forEach { guide -> db.writeGuide(workspaceId, guide) }
        val now = now()
        val state = json.workspaceFrom(row, emptyList())
        val restoreActive = activeSubDatumId != null && subDatums.any { point -> point.id == activeSubDatumId }
        if (restoreActive && state.activeSubDatumId != activeSubDatumId) {
            db.workspaceQueries.updateState(json.workspaceStateJson(state.copy(activeSubDatumId = activeSubDatumId)), now, workspaceId)
        }
        if (lastOrdinal > row.last_ordinal) db.workspaceQueries.updateLastOrdinal(lastOrdinal, now, workspaceId)
        StoreResult.Ok(Unit)
    }

    /** Writes a new or changed guide of the workspace. */
    suspend fun saveGuide(workspaceId: String, guide: Guide): StoreResult<Guide> = writer.write { db ->
        db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        db.writeGuide(workspaceId, guide)
        StoreResult.Ok(guide)
    }

    /** Deletes the guides [ids] in one transaction. */
    suspend fun deleteGuides(ids: Collection<String>) {
        writer.write { db -> ids.forEach { id -> db.subDatumQueries.deleteGuide(id) } }
    }

    // ---------------------------------------------------------------------------------------
    // Machine datums

    /** The machine datums of the profile in the natural order of the names. */
    fun machineDatums(profileId: String): Flow<List<MachineDatum>> =
        database.machineDatumQueries.selectForProfile(profileId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() }.sortedWith(compareBy(NaturalOrder) { it.name }) }

    /** Writes a new or changed machine datum of the profile. */
    suspend fun saveMachineDatum(profileId: String, datum: MachineDatum): StoreResult<MachineDatum> = writer.write { db ->
        db.machineProfileQueries.selectById(profileId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val existing = db.machineDatumQueries.selectById(datum.id).executeAsOneOrNull()
        val now = now()
        val stored = datum.copy(createdOn = existing?.created_on ?: now, updatedOn = now)
        db.writeMachineDatum(profileId, stored)
        StoreResult.Ok(stored)
    }

    /** Deletes the machine datum [id]. */
    suspend fun deleteMachineDatum(id: String) {
        writer.write { db -> db.machineDatumQueries.deleteById(id) }
    }

    // ---------------------------------------------------------------------------------------
    // Threads

    /** The lathe threads of the workspace. */
    fun threads(workspaceId: String): Flow<List<ThreadSpec>> =
        database.threadSpecQueries.selectForWorkspace(workspaceId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map(json::threadFrom) }

    /** Writes a new or changed thread of the workspace. */
    suspend fun saveThread(workspaceId: String, thread: ThreadSpec): StoreResult<ThreadSpec> = writer.write { db ->
        db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
        val existing = db.threadSpecQueries.selectById(thread.id).executeAsOneOrNull()
        val now = now()
        val stored = thread.copy(workspaceId = workspaceId, createdOn = existing?.created_on ?: now, updatedOn = now)
        db.writeThread(json, workspaceId, stored)
        StoreResult.Ok(stored)
    }

    /** Deletes the thread [id]. A workspace that has it as the armed thread then has no armed thread. */
    suspend fun deleteThread(id: String) {
        writer.write<Unit> { db ->
            val row = db.threadSpecQueries.selectById(id).executeAsOneOrNull() ?: return@write
            db.threadSpecQueries.deleteById(id)
            val workspace = db.workspaceQueries.selectById(row.workspace_id).executeAsOneOrNull() ?: return@write
            val state = json.workspaceFrom(workspace, emptyList())
            if (state.armedThreadId == id) {
                db.workspaceQueries.updateState(json.workspaceStateJson(state.copy(armedThreadId = null)), now(), workspace.id)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Workspace transforms

    /**
     * Writes a workspace transform in one transaction and keeps a copy of the old rows
     * for the undo. The store first deactivates the active sub-datum. The caller
     * reloads the workspace after the commit (the flows do this).
     *
     * @return The undo copy, or null when the result changes no stored value (no write).
     */
    suspend fun applyTransform(workspaceId: String, result: WorkspaceTransformResult): StoreResult<TransformUndo?> =
        writer.write { db ->
            db.workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
            StoreResult.Ok(db.writeTransform(json, now(), workspaceId, result))
        }

    /**
     * "Undo last transform": writes the copy of the last transform back in one
     * transaction and removes the copy.
     *
     * @return False when the workspace has no undo copy.
     */
    suspend fun undoLastTransform(workspaceId: String): Boolean = writer.write { db -> db.writeUndo(json, now(), workspaceId) }

    /** The undo copy of the last transform of the workspace, or null. */
    fun transformUndo(workspaceId: String): Flow<TransformUndo?> =
        database.transformUndoQueries.selectUndo(workspaceId).asFlow().mapToOneOrNull(writer.readDispatcher)
            .map { row -> row?.toModel() }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun deleteFile(name: String) {
        if (name.isNotEmpty()) storeIO.delete(name)
    }

    private fun SuperDroDatabase.settingsOf(id: String): MachineProfile =
        json.profileFrom(machineProfileQueries.selectById(id).executeAsOne())

    private fun SuperDroDatabase.profileNameRefusal(name: String, exceptId: String?): StoreRefusal? {
        if (name.isBlank()) return StoreRefusal.ProfileNameBlank
        val taken = machineProfileQueries.selectNames().executeAsList().any { row -> row.id != exceptId && sameName(row.name, name) }
        return if (taken) StoreRefusal.ProfileNameExists else null
    }

    private fun SuperDroDatabase.workspaceNameRefusal(profileId: String, name: String, exceptId: String?): StoreRefusal? {
        if (name.isBlank()) return StoreRefusal.WorkspaceNameBlank
        val taken = workspaceQueries.selectNamesForProfile(profileId).executeAsList()
            .any { row -> row.id != exceptId && sameName(row.name, name) }
        return if (taken) StoreRefusal.WorkspaceNameExists else null
    }

    private fun workspaceListRefusal(workspaces: List<Workspace>): StoreRefusal? {
        if (workspaces.any { workspace -> workspace.name.isBlank() }) return StoreRefusal.WorkspaceNameBlank
        val names = workspaces.map { workspace -> workspace.name.trim().lowercase() }
        return if (names.size != names.toSet().size) StoreRefusal.WorkspaceNameExists else null
    }

    private fun SuperDroDatabase.writeSettings(row: Machine_profile, profile: MachineProfile): StoreResult<MachineProfile> {
        profileNameRefusal(profile.name, exceptId = row.id)?.let { return StoreResult.Refused(it) }
        val workspaceIds = workspaceQueries.selectForProfile(row.id).executeAsList().map { it.id }
        val activeId = profile.activeWorkspaceId.takeIf { it in workspaceIds } ?: row.active_workspace_id
        val settings = profile.copy(machineType = row.machine_type)
        machineProfileQueries.update(
            name = profile.name.trim(),
            machineType = row.machine_type,
            metricDefault = profile.metricDefault,
            useSharedTools = profile.useSharedToolLibrary,
            adapterProfileId = existingAdapterId(profile.adapterProfileId),
            activeWorkspaceId = activeId,
            configJson = json.profileConfigJson(settings),
            updatedOn = now(),
            id = row.id,
        )
        return StoreResult.Ok(settingsOf(row.id))
    }

    private fun SuperDroDatabase.existingAdapterId(id: String?): String? =
        id?.takeIf { adapterQueries.existsById(it).executeAsOne() > 0 }

    private fun SuperDroDatabase.insertFullProfile(profile: MachineProfile) {
        val workspaces = profile.workspaces.ifEmpty {
            listOf(Workspace.forMachine(profile.machineType).copy(createdOn = profile.createdOn, updatedOn = profile.createdOn))
        }
        val activeId = profile.activeWorkspaceId.takeIf { id -> workspaces.any { it.id == id } } ?: workspaces.first().id
        machineProfileQueries.insert(
            id = profile.id,
            name = profile.name.trim(),
            machine_type = profile.machineType,
            metric_default = profile.metricDefault,
            use_shared_tools = profile.useSharedToolLibrary,
            adapter_profile_id = existingAdapterId(profile.adapterProfileId),
            active_workspace_id = activeId,
            config_json = json.profileConfigJson(profile),
            created_on = profile.createdOn,
            updated_on = profile.updatedOn,
        )
        workspaces.forEach { workspace -> insertWorkspace(profile.id, workspace) }
        profile.machineDatums.forEach { datum -> writeMachineDatum(profile.id, datum) }
        profile.tools.forEach { tool -> insertTool(json, profile.id, tool) }
    }

    private fun SuperDroDatabase.insertWorkspace(profileId: String, workspace: Workspace) {
        workspaceQueries.insert(
            id = workspace.id,
            profile_id = profileId,
            name = workspace.name.trim(),
            last_ordinal = workspace.lastOrdinal,
            state_json = json.workspaceStateJson(workspace),
            reference_image_json = json.referenceImageJson(workspace.referenceImage),
            created_on = workspace.createdOn,
            updated_on = workspace.updatedOn,
        )
        AxisId.entries.forEach { axis -> writeAxisState(workspace.id, axis, workspace.axisStates[axis] ?: AxisState()) }
        workspace.subDatums.forEach { point -> writeSubDatum(workspace.id, point) }
        workspace.guides.forEach { guide -> writeGuide(workspace.id, guide) }
    }

    private fun SuperDroDatabase.fullWorkspace(id: String): Workspace? {
        val row = workspaceQueries.selectById(id).executeAsOneOrNull() ?: return null
        return json.workspaceFrom(
            row = row,
            axisRows = workspaceQueries.selectAxisStates(id).executeAsList(),
            subDatums = subDatumQueries.selectForWorkspace(id).executeAsList().toSubDatums(),
            guides = subDatumQueries.selectGuides(id).executeAsList().map { guide -> guide.toModel() },
        )
    }

    private fun SuperDroDatabase.fullProfile(row: Machine_profile): MachineProfile {
        val workspaces = workspaceQueries.selectForProfile(row.id).executeAsList().mapNotNull { workspace -> fullWorkspace(workspace.id) }
        return json.profileFrom(row).copy(
            workspaces = workspaces,
            tools = toolQueries.selectForProfile(row.id).executeAsList().map(json::toolFrom),
            machineDatums = machineDatumQueries.selectForProfile(row.id).executeAsList().map { datum -> datum.toModel() }
                .sortedWith(compareBy(NaturalOrder) { it.name }),
        )
    }

    /** The active workspace of the profile: the stored ID when it exists, else the first workspace. */
    private fun SuperDroDatabase.activeWorkspaceId(profileId: String): String? {
        val profile = machineProfileQueries.selectById(profileId).executeAsOneOrNull() ?: return null
        val workspaces = workspaceQueries.selectForProfile(profileId).executeAsList()
        return workspaces.firstOrNull { it.id == profile.active_workspace_id }?.id ?: workspaces.firstOrNull()?.id
    }

    private fun SuperDroDatabase.afterSubDatumDelete(workspaceId: String, deletedIds: Set<String>) {
        val now = now()
        if (subDatumQueries.countForWorkspace(workspaceId).executeAsOne() == 0L) {
            workspaceQueries.updateLastOrdinal(0, now, workspaceId)
        }
        val row = workspaceQueries.selectById(workspaceId).executeAsOneOrNull() ?: return
        val state = json.workspaceFrom(row, emptyList())
        if (state.activeSubDatumId != null && state.activeSubDatumId in deletedIds) {
            workspaceQueries.updateState(json.workspaceStateJson(state.copy(activeSubDatumId = null)), now, workspaceId)
        }
    }
}

/**
 * The result of a workspace delete.
 *
 * @property deletedIds The workspaces that the store deleted.
 * @property keptId The workspace that the store kept because the selection had all workspaces.
 * @property refusal [StoreRefusal.LastWorkspace] when the store kept a workspace, else null.
 * @property activeWorkspaceId The active workspace of the profile after the delete.
 */
data class WorkspaceDeleteResult(
    val deletedIds: List<String>,
    val keptId: String?,
    val refusal: StoreRefusal?,
    val activeWorkspaceId: String?,
)
