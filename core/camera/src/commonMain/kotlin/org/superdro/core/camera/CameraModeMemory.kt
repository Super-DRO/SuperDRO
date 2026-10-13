package org.superdro.core.camera

import kotlin.time.Instant
import org.superdro.core.model.CameraCalibration
import org.superdro.core.model.CameraModeResult
import org.superdro.core.model.CameraModeStatus
import org.superdro.core.model.CameraSelection

/**
 * The mode memory as plain values. `core:store`
 * keeps the two lists in its camera tables (`CameraRepository`). These functions give the
 * new lists. They do no I/O.
 *
 * - `CameraModeResult` has one row for each VID:PID and mode key. All machine profiles share the
 *   rows (the test result belongs to the camera model).
 * - `CameraSelection` has one row for each machine profile and VID:PID.
 */
object CameraModeMemory {

    /** Gives the rows of [results] for the camera key [cameraKey]. */
    fun resultsFor(results: List<CameraModeResult>, cameraKey: String): List<CameraModeResult> =
        results.filter { row -> row.vidPid == cameraKey }

    /** Gives the row of [mode] of the camera [cameraKey], or null. */
    fun resultFor(results: List<CameraModeResult>, cameraKey: String, mode: UvcMode): CameraModeResult? =
        results.firstOrNull { row -> row.vidPid == cameraKey && row.modeKey == mode.key }

    /**
     * Writes the test result of [mode] (a replace on VID:PID and mode key). A GOOD write with no
     * rate keeps the old rate. A BAD write has no rate.
     */
    fun record(
        results: List<CameraModeResult>,
        cameraKey: String,
        mode: UvcMode,
        status: CameraModeStatus,
        measuredFps: Double?,
        now: Instant,
    ): List<CameraModeResult> {
        val old = resultFor(results, cameraKey, mode)
        val rate = when (status) {
            CameraModeStatus.Good -> measuredFps ?: old?.measuredFps
            CameraModeStatus.Bad -> null
        }
        val row = CameraModeResult(
            vidPid = cameraKey,
            modeKey = mode.key,
            status = status,
            width = mode.width,
            height = mode.height,
            codec = mode.codec.storeName,
            measuredFps = rate,
            updatedOn = now,
        )
        return results.filterNot { item -> item.vidPid == cameraKey && item.modeKey == mode.key } + row
    }

    /** Gives the selection of the profile [profileId] for the camera [cameraKey], or null. */
    fun selectionFor(selections: List<CameraSelection>, profileId: String, cameraKey: String): CameraSelection? =
        selections.firstOrNull { row -> row.profileId == profileId && row.cameraKey == cameraKey }

    /**
     * Writes the selected mode of the profile and the camera (a replace on profile and VID:PID).
     * The calibration of the old row stays.
     */
    fun select(
        selections: List<CameraSelection>,
        profileId: String,
        cameraKey: String,
        mode: UvcMode,
        now: Instant,
    ): List<CameraSelection> {
        val old = selectionFor(selections, profileId, cameraKey)
        val row = CameraSelection(profileId, cameraKey, mode.key, now, old?.calibration)
        return replace(selections, row)
    }

    /** Writes the image scale of the profile and the camera. */
    fun calibrate(
        selections: List<CameraSelection>,
        profileId: String,
        cameraKey: String,
        calibration: CameraCalibration?,
        now: Instant,
    ): List<CameraSelection> {
        val old = selectionFor(selections, profileId, cameraKey)
        val row = (old ?: CameraSelection(profileId = profileId, cameraKey = cameraKey))
            .copy(calibration = calibration, updatedOn = now)
        return replace(selections, row)
    }

    /**
     * Removes the selections of a deleted profile. The mode results stay, because all
     * profiles share them.
     */
    fun removeProfile(selections: List<CameraSelection>, profileId: String): List<CameraSelection> =
        selections.filterNot { row -> row.profileId == profileId }

    /**
     * Selects the camera of a video start: the attached
     * camera with the most recent selection of the profile, else the first attached camera.
     * When the row of the selected camera is not the most recent row of the profile, the result
     * has the row again with the time [now].
     */
    fun cameraForPlay(
        attached: List<CameraIdentity>,
        selections: List<CameraSelection>,
        profileId: String,
        now: Instant,
    ): PlayCamera? {
        if (attached.isEmpty()) return null
        val rows = selections.filter { row -> row.profileId == profileId }.sortedByDescending { row -> row.updatedOn }
        val newest = rows.firstOrNull()
        val chosenRow = rows.firstOrNull { row -> attached.any { camera -> camera.key == row.cameraKey } }
        val camera = chosenRow?.let { row -> attached.first { camera -> camera.key == row.cameraKey } } ?: attached.first()
        val touched = if (chosenRow != null && newest != null && chosenRow != newest) {
            replace(selections, chosenRow.copy(updatedOn = now))
        } else {
            null
        }
        return PlayCamera(camera, chosenRow?.copy(updatedOn = if (touched != null) now else chosenRow.updatedOn), touched)
    }

    /**
     * Gives the result of a video start.
     *
     * @param camera The camera of [cameraForPlay], or null when no camera is attached.
     * @param modes All modes of the camera.
     */
    fun playChoice(
        camera: CameraIdentity?,
        modes: List<UvcMode>,
        selections: List<CameraSelection>,
        results: List<CameraModeResult>,
        profileId: String,
    ): PlayChoice {
        if (camera == null) return PlayChoice.NoCamera
        val saved = selectionFor(selections, profileId, camera.key)
        val mode = UvcModeRules.playMode(modes, saved?.modeKey, resultsFor(results, camera.key))
        if (mode != null) return PlayChoice.Start(camera, mode)
        val otherCamera = selections.any { row -> row.profileId == profileId && row.cameraKey != camera.key }
        return if (otherCamera) PlayChoice.WrongCamera else PlayChoice.NeedsSetup
    }

    /**
     * The writes after the first frame of a video start:
     * the mode is GOOD, and it is the selection of the profile.
     */
    fun onFirstFrame(
        memory: CameraMemory,
        profileId: String,
        camera: CameraIdentity,
        mode: UvcMode,
        now: Instant,
    ): CameraMemory = CameraMemory(
        results = record(memory.results, camera.key, mode, CameraModeStatus.Good, null, now),
        selections = select(memory.selections, profileId, camera.key, mode, now),
    )

    /** True when the camera is "configured" for the profile ([UvcModeRules.isConfigured]). */
    fun isConfigured(
        camera: CameraIdentity?,
        selections: List<CameraSelection>,
        results: List<CameraModeResult>,
        profileId: String,
    ): Boolean = camera != null && UvcModeRules.isConfigured(
        attached = true,
        hasSavedSelection = selectionFor(selections, profileId, camera.key) != null,
        results = resultsFor(results, camera.key),
    )

    private fun replace(selections: List<CameraSelection>, row: CameraSelection): List<CameraSelection> =
        selections.filterNot { item -> item.profileId == row.profileId && item.cameraKey == row.cameraKey } + row
}

/** The two lists of the mode memory. */
data class CameraMemory(
    val results: List<CameraModeResult> = emptyList(),
    val selections: List<CameraSelection> = emptyList(),
)

/**
 * The camera of a video start.
 *
 * @property selection The selection row of the camera, or null.
 * @property touchedSelections The new selection list when the row got a new time, else null.
 */
data class PlayCamera(
    val camera: CameraIdentity,
    val selection: CameraSelection?,
    val touchedSelections: List<CameraSelection>?,
)

/** The result of a video start. */
sealed interface PlayChoice {
    /** Start [camera] in [mode]. */
    data class Start(val camera: CameraIdentity, val mode: UvcMode) : PlayChoice

    /** No camera is attached: `camera_error_no_device`. */
    data object NoCamera : PlayChoice

    /** The profile has a selection for a different camera: `camera_play_wrong_camera`. */
    data object WrongCamera : PlayChoice

    /** No saved selection and no GOOD mode: `camera_play_needs_setup` with "Camera setup". */
    data object NeedsSetup : PlayChoice
}
