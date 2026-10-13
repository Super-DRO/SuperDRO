package org.superdro.core.store.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.Camera_mode_result
import org.superdro.core.store.db.Camera_selection

/**
 * The mode memory of the USB cameras.
 *
 * - The test result of each mode of each camera model (VID:PID). All machine profiles share it,
 *   and a profile delete keeps it.
 * - The mode that each machine profile uses with each camera, and the image scale of the camera on
 *   that machine. A profile delete removes it.
 *
 * @param json The JSON of the image scale column.
 */
class CameraRepository(
    private val writer: StoreWriter,
    private val json: Json = SuperDroJson,
) {
    private val database get() = writer.database

    /** The mode results of the camera model [vidPid] (the key `%04x:%04x`). */
    fun modeResults(vidPid: String): Flow<List<CameraModeResult>> =
        database.cameraQueries.selectModeResults(vidPid).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel() } }

    /** Reads the mode results of the camera model [vidPid] one time. */
    suspend fun loadModeResults(vidPid: String): List<CameraModeResult> =
        writer.read { db -> db.cameraQueries.selectModeResults(vidPid).executeAsList().map { row -> row.toModel() } }

    /**
     * Writes the test result of one mode. A result replaces the old result of the same mode. A
     * [CameraModeStatus.Good] result with no measured rate keeps the old rate.
     */
    suspend fun saveModeResult(result: CameraModeResult) {
        writer.write { db ->
            val old = db.cameraQueries.selectModeResult(result.vidPid, result.modeKey).executeAsOneOrNull()
            val fps = if (result.status == CameraModeStatus.Good && result.measuredFps == null) old?.measured_fps else result.measuredFps
            db.cameraQueries.upsertModeResult(
                vid_pid = result.vidPid,
                mode_key = result.modeKey,
                status = result.status,
                width = result.width,
                height = result.height,
                codec = result.codec,
                measured_fps = fps,
                updated_on = result.updatedOn,
            )
        }
    }

    /** The camera selections of the profile, the newest first. */
    fun selections(profileId: String): Flow<List<CameraSelection>> =
        database.cameraQueries.selectSelections(profileId).asFlow().mapToList(writer.readDispatcher)
            .map { rows -> rows.map { row -> row.toModel(json) } }

    /** Reads the camera selections of the profile one time, the newest first. */
    suspend fun loadSelections(profileId: String): List<CameraSelection> =
        writer.read { db -> db.cameraQueries.selectSelections(profileId).executeAsList().map { row -> row.toModel(json) } }

    /** The mode that the profile uses with the camera [cameraKey], or null. */
    fun selection(profileId: String, cameraKey: String): Flow<CameraSelection?> =
        database.cameraQueries.selectSelection(profileId, cameraKey).asFlow().mapToOneOrNull(writer.readDispatcher)
            .map { row -> row?.toModel(json) }

    /**
     * Writes the mode that the profile uses with a camera, with the
     * image scale of [CameraSelection.calibration].
     */
    suspend fun saveSelection(selection: CameraSelection) {
        writer.write { db ->
            db.cameraQueries.upsertSelection(
                selection.profileId,
                selection.cameraKey,
                selection.modeKey,
                selection.updatedOn,
                selection.calibration?.let { calibration -> json.encodeToString(CameraCalibration.serializer(), calibration) },
            )
        }
    }

    /**
     * Writes the image scale of the camera [cameraKey] on the profile in one write
     * transaction. The mode of the row stays. A profile with no row for the camera gets a row with
     * an empty mode key. A null [calibration] removes the scale.
     */
    suspend fun saveCalibration(profileId: String, cameraKey: String, calibration: CameraCalibration?, now: Instant) {
        writer.write { db ->
            val old = db.cameraQueries.selectSelection(profileId, cameraKey).executeAsOneOrNull()
            db.cameraQueries.upsertSelection(
                profileId,
                cameraKey,
                old?.mode_key.orEmpty(),
                now,
                calibration?.let { value -> json.encodeToString(CameraCalibration.serializer(), value) },
            )
        }
    }

    /** Deletes the selection of one camera of the profile. */
    suspend fun deleteSelection(profileId: String, cameraKey: String) {
        writer.write { db -> db.cameraQueries.deleteSelection(profileId, cameraKey) }
    }
}

internal fun Camera_mode_result.toModel(): CameraModeResult = CameraModeResult(
    vidPid = vid_pid,
    modeKey = mode_key,
    status = status,
    width = width,
    height = height,
    codec = codec,
    measuredFps = measured_fps,
    updatedOn = updated_on,
)

internal fun Camera_selection.toModel(json: Json): CameraSelection = CameraSelection(
    profileId = profile_id,
    cameraKey = camera_key,
    modeKey = mode_key,
    updatedOn = updated_on,
    calibration = calibration_json?.let { text -> json.decodeColumn(CameraCalibration.serializer(), text) { CameraCalibration() } }
        ?.takeIf { calibration -> calibration.pixelsPerMm > 0.0 },
)
