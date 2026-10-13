package org.superdro.core.functions.tipcal

import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.superdro.core.model.ProbeProfile

/**
 * One touch in the session JSON. Lengths are µm, the
 * velocities are µm/s, and [t] is the trigger time stamp in ms. A null value is not in the JSON.
 */
@Serializable
data class SessionTouch(
    val x: Double? = null,
    val y: Double? = null,
    val vx: Double? = null,
    val vy: Double? = null,
    val t: Long? = null,
)

/** One clock position in the session JSON. Lengths are µm. */
@Serializable
data class SessionPosition(
    val clock: ClockPosition = ClockPosition.Nine,
    val x: Double? = null,
    val y: Double? = null,
    val sigmaX: Double? = null,
    val sigmaY: Double? = null,
    val spread: Double? = null,
    val touches: List<SessionTouch> = emptyList(),
)

/**
 * The result in the session JSON. Lengths are µm.
 *
 * @property effective The effective tip diameter `G − 2R`.
 * @property effectiveX The tip diameter across X, or null.
 * @property effectiveY The tip diameter across Y, or null.
 */
@Serializable
data class SessionResult(
    val centerX: Double? = null,
    val centerY: Double? = null,
    val radius: Double? = null,
    val effective: Double? = null,
    val residual: Double? = null,
    val repeatability: Double? = null,
    val effectiveX: Double? = null,
    val effectiveY: Double? = null,
)

/**
 * The session of one tip calibration run. The keys and their
 * order are fixed. All lengths are µm.
 *
 * @property format Always 1.
 * @property profile The id of the probe profile. SuperDRO ids are texts.
 * @property gauge The ring gauge diameter, or null before the gauge entry.
 * @property started The start time, `yyyy-MM-dd HH:mm:ss`.
 * @property completed The time of "Apply", `yyyy-MM-dd HH:mm:ss`, or null.
 */
@Serializable
data class TipCalibrationSession(
    val format: Int = FORMAT,
    val profile: String = "",
    val complete: Boolean = false,
    val gauge: Double? = null,
    val started: String? = null,
    val completed: String? = null,
    val positions: List<SessionPosition> = emptyList(),
    val result: SessionResult? = null,
) {
    companion object {
        /** The format number of the session JSON. */
        const val FORMAT: Int = 1
    }
}

/** The result of [TipCalibrationSessions.resume]. */
sealed interface ResumeResult {
    /**
     * The stored session cannot be read. Title "Calibration Interrupted". Text "This run cannot
     * be resumed. Start again from the probe profile."
     */
    data object Interrupted : ResumeResult

    /** The stored session is complete. There is no run to resume. */
    data object AlreadyComplete : ResumeResult

    /** The run continues with [run]. */
    data class Resumed(val run: TipCalibrationRun) : ResumeResult
}

/**
 * The session JSON of the tip calibration, its SHA-256 digest, and the profile values that a
 * calibration writes.
 */
object TipCalibrationSessions {

    /**
     * The JSON configuration of the session. A null value is not in the text
     * (`explicitNulls = false`), so an absent value is left out. Each value with a default is in
     * the text (`encodeDefaults = true`). An unknown key is ignored.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** Gives the JSON text of [session]. */
    fun encode(session: TipCalibrationSession): String =
        json.encodeToString(TipCalibrationSession.serializer(), session)

    /** Reads a session text. Gives null for a text that is not a session of format 1. */
    fun decode(text: String?): TipCalibrationSession? {
        if (text.isNullOrBlank()) return null
        val session = try {
            json.decodeFromString(TipCalibrationSession.serializer(), text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return session.takeIf { value -> value.format == TipCalibrationSession.FORMAT }
    }

    /** Gives the SHA-256 digest of the UTF-8 bytes of [sessionText]: 64 lowercase hex digits. */
    fun digest(sessionText: String): String = Sha256.hexOfUtf8(sessionText)

    /**
     * Makes the session of [run]. A value that is not finite becomes null, so the JSON does not
     * have it.
     */
    fun sessionOf(
        run: TipCalibrationRun,
        complete: Boolean = false,
        completed: String? = null,
        result: TipCalibrationResult? = null,
    ): TipCalibrationSession = TipCalibrationSession(
        profile = run.profileId,
        complete = complete,
        gauge = run.gaugeUm.finiteOrNull(),
        started = run.started,
        completed = completed,
        positions = ClockPosition.procedureOrder.mapNotNull { clock -> run.positionAt(clock) }.map { position ->
            SessionPosition(
                clock = position.clock,
                x = position.xUm.finiteOrNull(),
                y = position.yUm.finiteOrNull(),
                sigmaX = position.sigmaXUm.finiteOrNull(),
                sigmaY = position.sigmaYUm.finiteOrNull(),
                spread = position.spreadUm.finiteOrNull(),
                touches = position.touches.map { touch ->
                    SessionTouch(
                        x = touch.xUm.finiteOrNull(),
                        y = touch.yUm.finiteOrNull(),
                        vx = touch.vxUmPerS.finiteOrNull(),
                        vy = touch.vyUmPerS.finiteOrNull(),
                        t = touch.timeMs,
                    )
                },
            )
        },
        result = result?.let { value ->
            SessionResult(
                centerX = value.centerXUm.finiteOrNull(),
                centerY = value.centerYUm.finiteOrNull(),
                radius = value.radiusUm.finiteOrNull(),
                effective = value.effectiveDiameterUm.finiteOrNull(),
                residual = value.residualUm.finiteOrNull(),
                repeatability = value.repeatabilityUm.finiteOrNull(),
                effectiveX = value.diameterXUm.finiteOrNull(),
                effectiveY = value.diameterYUm.finiteOrNull(),
            )
        },
    )

    /**
     * Makes a run from a stored session text, so that an incomplete run continues.
     *
     * - A text that cannot be read, or a position or a touch with no X or Y, gives
     *   [ResumeResult.Interrupted].
     * - A complete session gives [ResumeResult.AlreadyComplete].
     * - A position with no sigma or no spread gets the values from its touches.
     */
    fun resume(text: String?): ResumeResult {
        val session = decode(text) ?: return ResumeResult.Interrupted
        if (session.complete) return ResumeResult.AlreadyComplete
        val positions = ArrayList<CapturedPosition>()
        for (stored in session.positions) {
            val touches = stored.touches.map { touch ->
                val x = touch.x ?: return ResumeResult.Interrupted
                val y = touch.y ?: return ResumeResult.Interrupted
                Touch(x, y, touch.vx ?: Double.NaN, touch.vy ?: Double.NaN, touch.t ?: 0L)
            }
            val x = stored.x ?: return ResumeResult.Interrupted
            val y = stored.y ?: return ResumeResult.Interrupted
            positions += CapturedPosition(
                clock = stored.clock,
                touches = touches,
                xUm = x,
                yUm = y,
                sigmaXUm = stored.sigmaX ?: TouchStatistics.populationSigma(touches.map { touch -> touch.xUm }),
                sigmaYUm = stored.sigmaY ?: TouchStatistics.populationSigma(touches.map { touch -> touch.yUm }),
                spreadUm = stored.spread ?: TouchStatistics.spreadUm(touches),
            )
        }
        if (positions.map { position -> position.clock }.toSet().size != positions.size) return ResumeResult.Interrupted
        val run = TipCalibrationRun(
            profileId = session.profile,
            started = session.started ?: "",
            gaugeUm = session.gauge,
            positions = positions,
        )
        return ResumeResult.Resumed(run)
    }

    /**
     * Gives true when a save during the run can replace the stored session:
     * the new session has a position, or the stored
     * session is not complete. No stored session, or a stored text that cannot be read, counts
     * as not complete. Thus an applied calibration stays in the record until the first position
     * of a new run.
     */
    fun canReplaceStoredSession(newSession: TipCalibrationSession, storedText: String?): Boolean {
        if (newSession.positions.isNotEmpty()) return true
        val stored = decode(storedText) ?: return true
        return !stored.complete
    }

    /**
     * Gives the profile after a save during the run. The session has `complete = false`. The
     * profile gets the session text and its digest only when [canReplaceStoredSession] is true.
     * Else the profile does not change.
     */
    fun withRunSession(profile: ProbeProfile, run: TipCalibrationRun): ProbeProfile {
        val session = sessionOf(run, complete = false)
        if (!canReplaceStoredSession(session, profile.calibrationSession)) return profile
        val text = encode(session)
        return profile.copy(calibrationSession = text, sessionDigest = digest(text))
    }

    /**
     * Gives the profile after "Apply":
     *
     * - the stylus diameter and the effective diameter are `d` (the nominal value is replaced),
     * - the calibration residual is the repeatability (not the fit residual),
     * - the calibration date is [calibratedOn],
     * - the session is the JSON with `complete = true`, [completed], and the result,
     * - the session digest is the SHA-256 of that JSON (UTF-8), 64 lowercase hex digits.
     */
    fun apply(
        profile: ProbeProfile,
        run: TipCalibrationRun,
        result: TipCalibrationResult,
        completed: String,
        calibratedOn: Instant,
    ): ProbeProfile {
        val text = encode(sessionOf(run, complete = true, completed = completed, result = result))
        return profile.copy(
            stylusDiameterUm = result.effectiveDiameterUm,
            effectiveDiameterUm = result.effectiveDiameterUm,
            calibrationResidualUm = result.repeatabilityUm,
            calibratedOn = calibratedOn,
            calibrationSession = text,
            sessionDigest = digest(text),
        )
    }

    private fun Double?.finiteOrNull(): Double? = if (this != null && isFinite()) this else null
}
