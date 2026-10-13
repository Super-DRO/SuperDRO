package org.superdro.core.store.repository

import com.toxicbakery.logging.Arbor
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Calibration
import org.superdro.core.model.Channels
import org.superdro.core.model.DeclaredInput
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MapPoint
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreRefusal
import org.superdro.core.store.StoreResult
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.Adapter_profile
import org.superdro.core.store.db.Encoder_profile
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.readEntry
import org.superdro.core.store.storeIoDispatcher
import org.superdro.core.store.writeAtomically

/** The two maps of an encoder input. */
enum class MapKind {
    /** The error correction map. */
    Error,

    /** The backlash map of the DEAD_ZONE model. */
    Backlash,
}

/**
 * The adapter profiles, their encoder profiles, and the error maps and backlash maps, and the error
 * map wizard session files. The comment lines of an error map ([EncoderProfile.errorMapComments],
 * the provenance) stay with the encoder profile, so that a map of the wizard keeps its provenance
 * after a restart.
 *
 * A map write is one transaction: a map of 20 000 points goes in
 * or stays out in full.
 *
 * The wizard session of one input is the file `error_map_wizard/adapter_<id>_<INPUT>.emw` in the
 * app files, with the upper case input letter, for example
 * `error_map_wizard/adapter_6f1c_X.emw`. It is temporary work data in JSON. The file has the field
 * `schemaVersion`. A session file of the older name `wizard/<id>-<input>.json` still loads one time:
 * the load moves it to the new name.
 */
class AdapterRepository(
    private val writer: StoreWriter,
    private val storeIO: StoreIOProvider,
    private val json: Json = SuperDroJson,
    private val clock: Clock = Clock.System,
    private val fileDispatcher: CoroutineDispatcher = storeIoDispatcher,
) {
    private val database get() = writer.database
    private val fileLock = Mutex()

    /**
     * All adapter profiles with their encoder profiles, sorted by alias. The error maps and the
     * backlash maps are empty here: [adapterProfile] gives them.
     */
    fun adapterProfiles(): Flow<List<AdapterProfile>> = writer.observe(
        database.adapterQueries.selectAll(),
        database.adapterQueries.selectAllEncoders(),
    ) { db ->
        val encoders = db.adapterQueries.selectAllEncoders().executeAsList().groupBy { it.adapter_profile_id }
        db.adapterQueries.selectAll().executeAsList().map { row ->
            profileFrom(row, encoders[row.id].orEmpty().associate { encoder -> encoder.input.first() to encoderFrom(encoder, emptyMap()) })
        }
    }

    /** The full adapter profile [id] with all maps, or null. */
    fun adapterProfile(id: String): Flow<AdapterProfile?> = writer.observe(
        database.adapterQueries.selectById(id),
        database.adapterQueries.selectEncoders(id),
        database.adapterQueries.selectAllMapPoints(id),
    ) { db -> db.fullAdapterProfile(id) }

    /** Reads the full adapter profile [id] one time, in one transaction. */
    suspend fun loadAdapterProfile(id: String): AdapterProfile? = writer.read { db -> db.fullAdapterProfile(id) }

    /** The adapter profiles with the identity [identity] of the source [source], oldest first. */
    suspend fun findByIdentity(identity: String, source: IdentitySource): List<AdapterProfile> = writer.read { db ->
        db.adapterQueries.selectByIdentity(identity, source).executeAsList().mapNotNull { row -> db.fullAdapterProfile(row.id) }
    }

    /**
     * Writes the adapter profile [profile] with all its encoder profiles and maps in one
     * transaction. An encoder input that is not in [AdapterProfile.encoders] is
     * removed with its maps.
     *
     * Refusals: [StoreRefusal.AdapterAliasBlank], [StoreRefusal.AdapterAliasTooLong] (more than
     * 40 characters), [StoreRefusal.AdapterAliasExists].
     */
    suspend fun saveAdapterProfile(profile: AdapterProfile): StoreResult<AdapterProfile> = writer.write { db ->
        db.aliasRefusal(profile.alias, profile.id)?.let { return@write StoreResult.Refused(it) }
        val existing = db.adapterQueries.selectById(profile.id).executeAsOneOrNull()
        db.adapterQueries.upsert(
            id = profile.id,
            alias = profile.alias.trim(),
            model = profile.model,
            identity = profile.identity,
            identity_source = profile.identitySource,
            legacy = profile.legacy,
            last_link_json = profile.lastLink?.let { link -> json.encodeToString(AdapterAddress.serializer(), link) },
            declared_inputs_json = json.encodeToString(declaredInputsSerializer, profile.declaredInputs),
            probe_contact_type = profile.probeContactType,
            probe_debounce_ms = profile.probeDebounceMs,
            last_seen_on = profile.lastSeenOn,
            created_on = existing?.created_on ?: profile.createdOn,
        )
        val inputs = profile.encoders.keys.map { input -> Channels.normalize(input).toString() }
        if (inputs.isEmpty()) db.adapterQueries.deleteAllEncoders(profile.id) else db.adapterQueries.deleteEncodersNotIn(profile.id, inputs)
        profile.encoders.values.forEach { encoder -> db.writeEncoder(profile.id, encoder, withMaps = true) }
        StoreResult.Ok(db.fullAdapterProfile(profile.id)!!)
    }

    /** Writes one encoder profile and its two maps in one transaction. */
    suspend fun saveEncoderProfile(adapterProfileId: String, encoder: EncoderProfile): StoreResult<EncoderProfile> =
        writer.write { db ->
            db.adapterQueries.selectById(adapterProfileId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
            db.writeEncoder(adapterProfileId, encoder, withMaps = true)
            StoreResult.Ok(encoder.copy(input = Channels.normalize(encoder.input)))
        }

    /**
     * Replaces one map of one input in one transaction. An
     * input with no encoder profile gets the default profile first.
     */
    suspend fun replaceMap(adapterProfileId: String, input: Char, kind: MapKind, points: List<MapPoint>): StoreResult<Unit> =
        writer.write { db ->
            db.adapterQueries.selectById(adapterProfileId).executeAsOneOrNull() ?: return@write StoreResult.Refused(StoreRefusal.NotFound)
            val letter = Channels.normalize(input)
            if (db.adapterQueries.selectEncoders(adapterProfileId).executeAsList().none { it.input == letter.toString() }) {
                db.writeEncoder(adapterProfileId, EncoderProfile.defaultFor(letter), withMaps = false)
            }
            db.writeMap(adapterProfileId, letter.toString(), kind, points)
            StoreResult.Ok(Unit)
        }

    /** Sets the time when the app last saw the adapter. */
    suspend fun setLastSeen(id: String, lastSeenOn: Instant = clock.now()) {
        writer.write { db -> db.adapterQueries.setLastSeen(lastSeenOn, id) }
    }

    /**
     * Deletes the adapter profile [id] with its encoder profiles and maps ("Forget"), and
     * the wizard session files of its inputs. A machine profile that refers to it keeps no
     * reference.
     */
    suspend fun deleteAdapterProfile(id: String) {
        writer.write { db -> db.adapterQueries.deleteById(id) }
        Channels.encoderInputs.forEach { input -> deleteWizardSession(id, input) }
    }

    // ---------------------------------------------------------------------------------------
    // Error map wizard sessions

    /**
     * Writes the wizard session of one input to `error_map_wizard/adapter_<id>_<INPUT>.emw`,
     * atomically (temporary file, then move). `core:functions` or `core:ui` owns the type [T].
     */
    suspend fun <T> saveWizardSession(adapterProfileId: String, input: Char, serializer: KSerializer<T>, session: T) {
        val file = WizardSessionFile(
            adapterProfileId = adapterProfileId,
            input = Channels.normalize(input).toString(),
            session = json.encodeToJsonElement(serializer, session),
        )
        val text = json.encodeToString(WizardSessionFile.serializer(), file)
        withContext(fileDispatcher) {
            fileLock.withLock {
                storeIO.writeAtomically(wizardSessionName(adapterProfileId, input)) { sink -> sink.writeString(text) }
            }
        }
    }

    /**
     * Reads the wizard session of one input, or null when no session file is there. A file that
     * does not decode gives null and a log line. The next save replaces it. A file of the older
     * name moves to the new name first.
     */
    suspend fun <T> loadWizardSession(adapterProfileId: String, input: Char, serializer: KSerializer<T>): T? =
        withContext(fileDispatcher) {
            fileLock.withLock {
                val name = wizardSessionName(adapterProfileId, input)
                val legacy = legacyWizardSessionName(adapterProfileId, input)
                if (!storeIO.exists(name) && storeIO.exists(legacy)) storeIO.move(legacy, name)
                if (!storeIO.exists(name)) return@withLock null
                try {
                    val text = storeIO.readEntry(name) { source -> source.readString() }
                    val file = json.decodeFromString(WizardSessionFile.serializer(), text)
                    file.session?.let { element -> json.decodeFromJsonElement(serializer, element) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error) { "Cannot read the wizard session $name." }
                    null
                }
            }
        }

    /** Deletes the wizard session of one input. An empty session deletes the file. */
    suspend fun deleteWizardSession(adapterProfileId: String, input: Char) {
        withContext(fileDispatcher) {
            fileLock.withLock {
                storeIO.delete(wizardSessionName(adapterProfileId, input))
                storeIO.delete(legacyWizardSessionName(adapterProfileId, input))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Private parts

    private fun SuperDroDatabase.aliasRefusal(alias: String, exceptId: String): StoreRefusal? {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return StoreRefusal.AdapterAliasBlank
        if (trimmed.length > AdapterProfile.MAX_ALIAS_LENGTH) return StoreRefusal.AdapterAliasTooLong
        val taken = adapterQueries.selectAliases().executeAsList().any { row -> row.id != exceptId && sameName(row.alias, trimmed) }
        return if (taken) StoreRefusal.AdapterAliasExists else null
    }

    private fun SuperDroDatabase.writeEncoder(adapterProfileId: String, encoder: EncoderProfile, withMaps: Boolean) {
        val input = Channels.normalize(encoder.input).toString()
        adapterQueries.upsertEncoder(
            adapter_profile_id = adapterProfileId,
            input = input,
            label = encoder.label,
            type = encoder.type,
            kind = encoder.kind,
            resolution = encoder.resolution,
            counts_per_screw_rev = encoder.countsPerScrewRev,
            reversed = encoder.reversed,
            spike_filter = encoder.filter,
            backlash_mode = encoder.backlashMode,
            backlash_counts = encoder.backlashCounts,
            error_map_cyclic = encoder.errorMapCyclic,
            calibration_json = json.encodeToString(Calibration.serializer(), encoder.calibration),
            error_map_comments_json = json.encodeToString(commentsSerializer, encoder.errorMapComments),
        )
        if (withMaps) {
            writeMap(adapterProfileId, input, MapKind.Error, encoder.errorMap)
            writeMap(adapterProfileId, input, MapKind.Backlash, encoder.backlashMap)
        }
    }

    private fun SuperDroDatabase.writeMap(adapterProfileId: String, input: String, kind: MapKind, points: List<MapPoint>) {
        adapterQueries.deleteMap(adapterProfileId, input, kind.name)
        points.forEachIndexed { seq, point ->
            adapterQueries.insertMapPoint(adapterProfileId, input, kind.name, seq, point.position, point.value)
        }
    }

    private fun SuperDroDatabase.fullAdapterProfile(id: String): AdapterProfile? {
        val row = adapterQueries.selectById(id).executeAsOneOrNull() ?: return null
        val points = adapterQueries.selectAllMapPoints(id).executeAsList().groupBy { point -> point.input to point.map_kind }
        val encoders = adapterQueries.selectEncoders(id).executeAsList().associate { encoder ->
            val maps = MapKind.entries.associateWith { kind ->
                points[encoder.input to kind.name].orEmpty().map { point -> MapPoint(point.position_counts, point.value_counts) }
            }
            encoder.input.first() to encoderFrom(encoder, maps)
        }
        return profileFrom(row, encoders)
    }

    private fun profileFrom(row: Adapter_profile, encoders: Map<Char, EncoderProfile>): AdapterProfile = AdapterProfile(
        id = row.id,
        alias = row.alias,
        model = row.model,
        identity = row.identity,
        identitySource = row.identity_source,
        legacy = row.legacy,
        lastLink = row.last_link_json?.let { text -> json.decodeColumn(AdapterAddress.serializer().nullable, text) { null } },
        declaredInputs = json.decodeColumn(declaredInputsSerializer, row.declared_inputs_json) { emptySet() },
        probeContactType = row.probe_contact_type,
        probeDebounceMs = row.probe_debounce_ms,
        encoders = encoders,
        lastSeenOn = row.last_seen_on,
        createdOn = row.created_on,
    )

    private fun encoderFrom(row: Encoder_profile, maps: Map<MapKind, List<MapPoint>>): EncoderProfile = EncoderProfile(
        input = row.input.first(),
        label = row.label,
        type = row.type,
        kind = row.kind,
        resolution = row.resolution,
        countsPerScrewRev = row.counts_per_screw_rev,
        reversed = row.reversed,
        filter = row.spike_filter,
        backlashMode = row.backlash_mode,
        backlashCounts = row.backlash_counts,
        errorMap = maps[MapKind.Error].orEmpty(),
        errorMapCyclic = row.error_map_cyclic,
        backlashMap = maps[MapKind.Backlash].orEmpty(),
        calibration = json.decodeColumn(Calibration.serializer(), row.calibration_json) { Calibration() },
        errorMapComments = json.decodeColumn(commentsSerializer, row.error_map_comments_json) { emptyList() },
    )

    companion object {
        /** The current version of the wizard session file. */
        const val WIZARD_SESSION_SCHEMA_VERSION: Int = 1

        /** The folder of the wizard session files. */
        const val WIZARD_SESSION_FOLDER: String = "error_map_wizard"

        /**
         * The store name of the wizard session file of one input:
         * `error_map_wizard/adapter_<adapterProfileId>_<INPUT>.emw`, with the upper case input letter.
         */
        fun wizardSessionName(adapterProfileId: String, input: Char): String =
            "$WIZARD_SESSION_FOLDER/adapter_${adapterProfileId}_${Channels.normalize(input).uppercaseChar()}.emw"

        /** The older store name of a wizard session file: `wizard/<adapterProfileId>-<input>.json`. */
        internal fun legacyWizardSessionName(adapterProfileId: String, input: Char): String =
            "wizard/$adapterProfileId-${Channels.normalize(input)}.json"

        private val declaredInputsSerializer = SetSerializer(DeclaredInput.serializer())

        private val commentsSerializer = ListSerializer(String.serializer())

        private val log = Arbor.tag("AdapterRepository")
    }
}

/** The file form of a wizard session: the version, the owner, and the session of the wizard. */
@Serializable
private data class WizardSessionFile(
    val schemaVersion: Int = AdapterRepository.WIZARD_SESSION_SCHEMA_VERSION,
    val adapterProfileId: String = "",
    val input: String = "",
    val session: JsonElement? = null,
)
