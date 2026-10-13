package org.superdro.core.store.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ProbeRoles
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.Probe_profile
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.files.probe.ProbeImportPlan

/**
 * The probe profiles: the own profiles of each machine profile, the
 * profiles for all machine profiles, and the six standard tips.
 *
 * The schema adds the six standard tips (2D edge finders 0.100", 0.200", 0.500", 4 mm, 6 mm, and
 * 10 mm) for all machine profiles. The user cannot change a standard tip
 * ([StoreRefusal.StandardTipReadOnly]). A delete of a standard tip removes it for all machine
 * profiles.
 */
class ProbeRepository(
    private val writer: StoreWriter,
    private val json: Json = SuperDroJson,
    private val clock: Clock = Clock.System,
) {
    private val database get() = writer.database

    /**
     * The probe library of the machine profile [profileId]: its own profiles first, then
     * the profiles for all machine profiles with the standard tips, each part by name.
     */
    fun library(profileId: String): Flow<List<ProbeProfile>> =
        database.probeQueries.selectForProfile(profileId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() } }

    /** The profiles for all machine profiles, with the standard tips. */
    fun sharedProfiles(): Flow<List<ProbeProfile>> =
        database.probeQueries.selectShared().asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() } }

    /** The standard tips that the user did not delete: the inch tips, then the mm tips, each by diameter. */
    fun standardTips(): Flow<List<ProbeProfile>> =
        database.probeQueries.selectStandardTips().asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() } }

    /** Reads the probe profile [id] one time. */
    suspend fun loadProbeProfile(id: String): ProbeProfile? =
        writer.read { db -> db.probeQueries.selectById(id).executeAsOneOrNull()?.toModel() }

    /**
     * Writes a new or changed probe profile. A change of a standard tip is refused
     * ([StoreRefusal.StandardTipReadOnly]). A new profile is never a standard tip.
     */
    suspend fun saveProbeProfile(profile: ProbeProfile): StoreResult<ProbeProfile> = writer.write { db ->
        val existing = db.probeQueries.selectById(profile.id).executeAsOneOrNull()
        if (existing?.standard_tip == true) return@write StoreResult.Refused(StoreRefusal.StandardTipReadOnly)
        val stored = profile.copy(standardTip = false)
        db.writeProbe(stored)
        StoreResult.Ok(stored)
    }

    /**
     * Writes [profiles] in one transaction (the `.tdprb` import). A standard tip in the
     * list is skipped.
     */
    suspend fun saveProbeProfiles(profiles: List<ProbeProfile>): Int = replaceProbeProfiles(emptyList(), profiles)

    /**
     * Writes the plan of a `.tdprb` import in one transaction: the delete of
     * [ProbeImportPlan.deleteIds] first ("Replace all", with the rules of
     * [deleteProbeProfile]), then the new and the changed profiles (with the rule of
     * [saveProbeProfiles]). A failure writes nothing.
     *
     * @return The number of profiles that the import wrote.
     */
    suspend fun applyImport(plan: ProbeImportPlan): Int = replaceProbeProfiles(plan.deleteIds, plan.add + plan.update)

    /**
     * Deletes the probe profiles [deleteIds] and writes [profiles], in one transaction. A standard
     * tip in [profiles] is skipped.
     *
     * @return The number of profiles of [profiles] that the call wrote.
     */
    suspend fun replaceProbeProfiles(deleteIds: Collection<String>, profiles: List<ProbeProfile>): Int = writer.write { db ->
        deleteIds.forEach { id ->
            db.probeQueries.deleteById(id)
            db.clearProbeRoles(id)
        }
        var count = 0
        profiles.forEach { profile ->
            val existing = db.probeQueries.selectById(profile.id).executeAsOneOrNull()
            if (existing?.standard_tip != true) {
                db.writeProbe(profile.copy(standardTip = false))
                count++
            }
        }
        count
    }

    /**
     * Deletes the probe profile [id]. Each machine profile that selects it for a function loses
     * that selection. A standard tip goes for all machine
     * profiles and does not come back.
     */
    suspend fun deleteProbeProfile(id: String) {
        writer.write { db ->
            db.probeQueries.deleteById(id)
            db.clearProbeRoles(id)
        }
    }

    /** Records the selection of the probe profile [id] for a function (the selector sort). */
    suspend fun markUsed(id: String, usedOn: Instant = clock.now()) {
        writer.write { db -> db.probeQueries.setLastUsed(usedOn, id) }
    }

    private fun SuperDroDatabase.writeProbe(profile: ProbeProfile) {
        probeQueries.upsert(
            id = profile.id,
            profile_id = profile.profileId,
            type = profile.type,
            name = profile.name,
            port = profile.port?.toString(),
            stylus_diameter_um = profile.stylusDiameterUm,
            effective_diameter_um = profile.effectiveDiameterUm,
            x_offset_um = profile.xOffsetUm,
            y_offset_um = profile.yOffsetUm,
            z_offset_um = profile.zOffsetUm,
            metric = profile.metric,
            sample_count = profile.sampleCount,
            spread_limit_um = profile.spreadLimitUm,
            calibration_residual_um = profile.calibrationResidualUm,
            calibrated_on = profile.calibratedOn,
            calibration_session = profile.calibrationSession,
            session_digest = profile.sessionDigest,
            standard_tip = profile.standardTip,
            last_used_on = profile.lastUsedOn,
        )
    }

    private fun SuperDroDatabase.clearProbeRoles(probeId: String) {
        val now = clock.now()
        machineProfileQueries.selectAll().executeAsList().forEach { row ->
            val settings = json.profileFrom(row)
            val roles = settings.probeRoles
            val cleared = ProbeRoles(
                edgeFinderProfileId = roles.edgeFinderProfileId.takeUnless { it == probeId },
                zReferenceProfileId = roles.zReferenceProfileId.takeUnless { it == probeId },
                measurementInstrumentProfileId = roles.measurementInstrumentProfileId.takeUnless { it == probeId },
            )
            if (cleared != roles) {
                machineProfileQueries.update(
                    name = row.name,
                    machineType = row.machine_type,
                    metricDefault = row.metric_default,
                    useSharedTools = row.use_shared_tools,
                    adapterProfileId = row.adapter_profile_id,
                    activeWorkspaceId = row.active_workspace_id,
                    configJson = json.profileConfigJson(settings.copy(probeRoles = cleared)),
                    updatedOn = now,
                    id = row.id,
                )
            }
        }
    }

    companion object {
        /** The IDs of the six standard tips that the schema adds. */
        val STANDARD_TIP_IDS: List<String> = (1..6).map { index -> "6f3d2a10-5c1e-4b7a-9d01-00000000000$index" }
    }
}

internal fun Probe_profile.toModel(): ProbeProfile = ProbeProfile(
    id = id,
    profileId = profile_id,
    type = type,
    name = name,
    port = port?.firstOrNull(),
    stylusDiameterUm = stylus_diameter_um,
    effectiveDiameterUm = effective_diameter_um,
    xOffsetUm = x_offset_um,
    yOffsetUm = y_offset_um,
    zOffsetUm = z_offset_um,
    metric = metric,
    sampleCount = sample_count,
    spreadLimitUm = spread_limit_um,
    calibrationResidualUm = calibration_residual_um,
    calibratedOn = calibrated_on,
    calibrationSession = calibration_session,
    sessionDigest = session_digest,
    lastUsedOn = last_used_on,
    standardTip = standard_tip,
)
