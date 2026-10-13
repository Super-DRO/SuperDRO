package org.superdro.core.functions.tipcal

import org.superdro.core.model.ProbeProfile

/**
 * One line of "Touches taken" in the calibration record.
 * The text is "%1$s o'clock: %2$d touches, spread %3$s %4$s" with the hour, [touchCount],
 * [spreadUm] in the profile units, and the unit.
 *
 * @property spreadUm The spread of the position in µm, or null when the session does not have it.
 */
data class RecordPositionLine(val clock: ClockPosition, val touchCount: Int, val spreadUm: Double?)

/**
 * The content of the "Calibration Record" section of the probe profile screen (It shows in the screen, not in a
 * dialog). The UI
 * shows the values in the units of the profile.
 */
sealed interface CalibrationRecord {
    /** Text "No calibration has been recorded for this probe." */
    data object NoCalibration : CalibrationRecord

    /** The stored session cannot be read. The UI shows the text of [NoCalibration]. */
    data object Unreadable : CalibrationRecord

    /**
     * An incomplete session, with a warning icon. Text "This run was never finished, so it was
     * not applied…" The "Touches taken" lines follow.
     */
    data class Incomplete(val positions: List<RecordPositionLine>) : CalibrationRecord

    /**
     * A complete session: the summary (tip diameter, repeatability, ring gauge, and the date),
     * "Across X: … Across Y: …" when both per-axis diameters exist, and the "Touches taken"
     * lines.
     *
     * @property spreadLimitFromRunUm The spread limit when it comes from this run: the profile
     *   has no spread limit, and the calibration residual gives the limit. Then the UI
     *   adds "The spread ceiling in force, %1$s %2$s, comes from this run." Else null.
     */
    data class Complete(
        val tipDiameterUm: Double,
        val repeatabilityUm: Double?,
        val gaugeUm: Double?,
        val completed: String?,
        val diameterXUm: Double?,
        val diameterYUm: Double?,
        val positions: List<RecordPositionLine>,
        val spreadLimitFromRunUm: Double?,
    ) : CalibrationRecord
}

/** Makes the calibration record of a probe profile. */
object CalibrationRecords {

    /** Gives the record of [profile] from its stored session. */
    fun of(profile: ProbeProfile): CalibrationRecord {
        val text = profile.calibrationSession
        if (text.isNullOrBlank()) return CalibrationRecord.NoCalibration
        val session = TipCalibrationSessions.decode(text) ?: return CalibrationRecord.Unreadable
        val lines = session.positions.map { position ->
            RecordPositionLine(position.clock, position.touches.size, position.spread)
        }
        if (!session.complete) return CalibrationRecord.Incomplete(lines)
        val result = session.result ?: return CalibrationRecord.Unreadable
        val tip = result.effective ?: return CalibrationRecord.Unreadable
        val limit = SpreadLimits.of(profile)
        val perAxis = result.effectiveX != null && result.effectiveY != null
        return CalibrationRecord.Complete(
            tipDiameterUm = tip,
            repeatabilityUm = result.repeatability,
            gaugeUm = session.gauge,
            completed = session.completed,
            diameterXUm = if (perAxis) result.effectiveX else null,
            diameterYUm = if (perAxis) result.effectiveY else null,
            positions = lines,
            spreadLimitFromRunUm = if (limit.source == SpreadLimitSource.Calibration) limit.valueUm else null,
        )
    }
}
