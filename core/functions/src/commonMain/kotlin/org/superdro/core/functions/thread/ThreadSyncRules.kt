package org.superdro.core.functions.thread

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * The half-nut engagement rule on a lathe threading dial.
 */
enum class ThreadDialRule {
    KeepHalfNutClosed,
    AnyPoint,
    AnyLine,
    AnyNumberedLine,
    OppositeNumberedLines,
    SameLineOnly,
}

/**
 * The result of a live Z-feed per spindle turn check against the target thread lead.
 */
sealed interface PitchCheckResult {
    val measuredLeadUm: Double
    val targetLeadUm: Double
    val relativeError: Double

    /** The measured feed per revolution matches the target lead within the tolerance. */
    data class Match(
        override val measuredLeadUm: Double,
        override val targetLeadUm: Double,
        override val relativeError: Double,
    ) : PitchCheckResult

    /** The measured feed per revolution does not match the target lead. */
    data class Mismatch(
        override val measuredLeadUm: Double,
        override val targetLeadUm: Double,
        override val relativeError: Double,
    ) : PitchCheckResult
}

/**
 * A standard internal threading bar holder size.
 *
 * @property designation The standard holder name.
 * @property minBoreUm The smallest bore diameter that accepts the holder, in µm.
 * @property shankDiameterUm The shank diameter of the holder, in µm.
 */
enum class InternalBarHolder(
    val designation: String,
    val minBoreUm: Double,
    val shankDiameterUm: Double,
) {
    CarbideMicro("Micro carbide", 2_500.0, 4_000.0),
    Ir06("06IR", 6_000.0, 6_000.0),
    Ir08("08IR", 8_000.0, 8_000.0),
    Ir11("11IR", 11_000.0, 10_000.0),
    Ir16("16IR", 16_000.0, 12_000.0),
    Ir22("22IR", 20_000.0, 16_000.0),
}

/**
 * The recommended internal threading bar holder and overhang check for an internal thread.
 *
 * @property holder The largest standard holder that fits in the minor bore, or null when the bore
 *   is smaller than [InternalBarHolder.CarbideMicro].
 * @property maxSafeDepthUm The maximum thread depth before tool deflection risk (`3.5 × shank`),
 *   in µm.
 * @property deflectionRisk True when the thread length is greater than [maxSafeDepthUm].
 */
data class InternalBarRecommendation(
    val holder: InternalBarHolder?,
    val maxSafeDepthUm: Double,
    val deflectionRisk: Boolean,
)

/**
 * Spindle speed and stopping reaction advice for single-point thread cutting.
 *
 * @property maxRpm The maximum recommended spindle speed in RPM for the stopping zone.
 * @property stoppingZoneUm The axial stopping zone length in µm (`max(exitRelief, 1.5 × pitch)`).
 * @property liveCarriageSpeedUmPerSec The live Z carriage speed in µm/s when the tachometer is
 *   active, or null.
 * @property liveReactionWindowSec The available reaction time in seconds inside the stopping zone
 *   at the live spindle speed, or null.
 * @property reactionTooFast True when [liveReactionWindowSec] is less than `0.75` seconds.
 */
data class SafeSpeedAdvice(
    val maxRpm: Int,
    val stoppingZoneUm: Double,
    val liveCarriageSpeedUmPerSec: Double?,
    val liveReactionWindowSec: Double?,
    val reactionTooFast: Boolean,
)

/**
 * Synchronization, spindle speed, internal bar, and helix lead-angle rules for single-point
 * threading.
 */
object ThreadSyncRules {

    private const val INCH_UM: Double = 25_400.0
    private const val INTEGER_TOLERANCE: Double = 1e-4
    private const val EXTERNAL_TARGET_REACTION_SEC: Double = 1.0
    private const val INTERNAL_TARGET_REACTION_SEC: Double = 1.5
    private const val MIN_REACTION_WINDOW_SEC: Double = 0.75
    private const val MAX_SHANK_OVERHANG_RATIO: Double = 3.5
    private const val ARC_SEC_PER_RAD: Double = 180.0 * 3600.0 / PI

    /** `2.5°` in arc seconds (`9000"`). */
    const val ANVIL_TILT_THRESHOLD_ARC_SEC: Double = 9_000.0

    /**
     * Gives the threading dial engagement rule for the thread lead and the lathe leadscrew pitch.
     *
     * @param leadUm The axial thread lead (`starts × pitch`) in µm.
     * @param threadMetric True for a metric thread, false for an inch thread.
     * @param leadscrewPitchUm The lathe leadscrew pitch in µm, or null when it is not set.
     * @param leadscrewMetric True when the lathe leadscrew is metric, false when it is inch.
     */
    fun dialRule(
        leadUm: Double,
        threadMetric: Boolean,
        leadscrewPitchUm: Double?,
        leadscrewMetric: Boolean = false,
    ): ThreadDialRule {
        if (
            leadscrewPitchUm == null ||
            !leadscrewPitchUm.isFinite() ||
            leadscrewPitchUm <= 0.0 ||
            !leadUm.isFinite() ||
            leadUm <= 0.0
        ) {
            return ThreadDialRule.KeepHalfNutClosed
        }
        if (threadMetric != leadscrewMetric) {
            return ThreadDialRule.KeepHalfNutClosed
        }
        if (threadMetric) {
            val pitchRatio = leadscrewPitchUm / leadUm
            return if (isPositiveInteger(pitchRatio)) {
                ThreadDialRule.AnyPoint
            } else {
                ThreadDialRule.KeepHalfNutClosed
            }
        }

        val tpi = INCH_UM / leadUm
        val leadscrewTpi = INCH_UM / leadscrewPitchUm
        if (isPositiveInteger(tpi / leadscrewTpi)) {
            return ThreadDialRule.AnyPoint
        }
        if (isPositiveInteger(tpi)) {
            val roundedTpi = round(tpi).toLong()
            return if (roundedTpi % 2L == 0L) {
                ThreadDialRule.AnyLine
            } else {
                ThreadDialRule.AnyNumberedLine
            }
        }
        val doubledTpi = 2.0 * tpi
        if (isPositiveInteger(doubledTpi) && round(doubledTpi).toLong() % 2L != 0L) {
            return ThreadDialRule.OppositeNumberedLines
        }
        val quadrupledTpi = 4.0 * tpi
        if (isPositiveInteger(quadrupledTpi) && round(quadrupledTpi).toLong() % 2L != 0L) {
            return ThreadDialRule.SameLineOnly
        }
        return ThreadDialRule.KeepHalfNutClosed
    }

    /**
     * Calculates the maximum safe spindle speed and the live reaction window in the relief zone.
     *
     * @param leadUm The axial thread lead (`starts × pitch`) in µm.
     * @param pitchUm The thread pitch in µm.
     * @param exitReliefUm The stopping relief width in µm, or null when no relief is set.
     * @param internal True for an internal thread, false for an external thread.
     * @param currentRpm The live spindle speed from the tachometer, or null when the tachometer is
     *   not active.
     */
    fun safeSpeed(
        leadUm: Double,
        pitchUm: Double,
        exitReliefUm: Double?,
        internal: Boolean,
        currentRpm: Double? = null,
    ): SafeSpeedAdvice {
        val validPitch = if (pitchUm.isFinite() && pitchUm > 0.0) pitchUm else 0.0
        val validRelief = exitReliefUm?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val stoppingZoneUm = max(validRelief, 1.5 * validPitch)
        val targetReactionSec = if (internal) INTERNAL_TARGET_REACTION_SEC else EXTERNAL_TARGET_REACTION_SEC
        val maxRpm = if (leadUm.isFinite() && leadUm > 0.0 && stoppingZoneUm > 0.0) {
            floor(60.0 * stoppingZoneUm / (leadUm * targetReactionSec)).toInt().coerceAtLeast(1)
        } else {
            1
        }
        if (currentRpm != null && currentRpm.isFinite() && currentRpm > 0.0 && leadUm.isFinite() && leadUm > 0.0) {
            val speedUmPerSec = (currentRpm / 60.0) * leadUm
            val windowSec = stoppingZoneUm / speedUmPerSec
            return SafeSpeedAdvice(
                maxRpm = maxRpm,
                stoppingZoneUm = stoppingZoneUm,
                liveCarriageSpeedUmPerSec = speedUmPerSec,
                liveReactionWindowSec = windowSec,
                reactionTooFast = windowSec < MIN_REACTION_WINDOW_SEC,
            )
        }
        return SafeSpeedAdvice(
            maxRpm = maxRpm,
            stoppingZoneUm = stoppingZoneUm,
            liveCarriageSpeedUmPerSec = null,
            liveReactionWindowSec = null,
            reactionTooFast = false,
        )
    }

    /**
     * Compares the measured Z feed per spindle revolution against the target thread lead.
     *
     * @param measuredFeedPerRevUm The measured axial movement per turn in µm, or null.
     * @param targetLeadUm The target thread lead in µm.
     * @param toleranceRatio The maximum permitted relative error (default `0.03` for 3%).
     * @return [PitchCheckResult.Match] or [PitchCheckResult.Mismatch], or null when the input is
     *   not valid.
     */
    fun pitchCheck(
        measuredFeedPerRevUm: Double?,
        targetLeadUm: Double,
        toleranceRatio: Double = 0.03,
    ): PitchCheckResult? {
        if (
            measuredFeedPerRevUm == null ||
            !measuredFeedPerRevUm.isFinite() ||
            measuredFeedPerRevUm <= 0.0 ||
            !targetLeadUm.isFinite() ||
            targetLeadUm <= 0.0
        ) {
            return null
        }
        val error = abs(measuredFeedPerRevUm - targetLeadUm) / targetLeadUm
        return if (error <= toleranceRatio) {
            PitchCheckResult.Match(measuredFeedPerRevUm, targetLeadUm, error)
        } else {
            PitchCheckResult.Mismatch(measuredFeedPerRevUm, targetLeadUm, error)
        }
    }

    /**
     * Selects the largest standard internal threading bar holder that fits in [minorDiameterUm]
     * and checks whether [lengthUm] exceeds `3.5 × shankDiameterUm`.
     */
    fun internalBarRecommendation(
        minorDiameterUm: Double,
        lengthUm: Double?,
    ): InternalBarRecommendation {
        val holder = if (minorDiameterUm.isFinite()) {
            InternalBarHolder.entries.lastOrNull { it.minBoreUm <= minorDiameterUm }
        } else {
            null
        }
        val maxSafeDepthUm = if (holder != null) MAX_SHANK_OVERHANG_RATIO * holder.shankDiameterUm else 0.0
        val deflectionRisk =
            lengthUm != null &&
                lengthUm.isFinite() &&
                holder != null &&
                lengthUm > maxSafeDepthUm
        return InternalBarRecommendation(
            holder = holder,
            maxSafeDepthUm = maxSafeDepthUm,
            deflectionRisk = deflectionRisk,
        )
    }

    /**
     * Calculates the helix lead angle `λ = arctan(lead / (π × d2))` in arc seconds.
     */
    fun leadAngleArcSec(leadUm: Double, pitchDiameterUm: Double): Double {
        if (!leadUm.isFinite() || leadUm <= 0.0 || !pitchDiameterUm.isFinite() || pitchDiameterUm <= 0.0) {
            return 0.0
        }
        val leadAngleRad = atan(leadUm / (PI * pitchDiameterUm))
        return leadAngleRad * ARC_SEC_PER_RAD
    }

    /**
     * True when the helix lead angle is greater than `2.5°` (`9000` arc seconds), where a standard
     * threading insert needs a tilted anvil or extra flank clearance.
     */
    fun needsAnvilTilt(leadAngleArcSec: Double): Boolean =
        leadAngleArcSec.isFinite() && abs(leadAngleArcSec) > ANVIL_TILT_THRESHOLD_ARC_SEC

    private fun isPositiveInteger(value: Double): Boolean {
        if (!value.isFinite() || value <= 0.0) return false
        val nearest = round(value)
        return nearest >= 1.0 && abs(value - nearest) <= INTEGER_TOLERANCE
    }
}
