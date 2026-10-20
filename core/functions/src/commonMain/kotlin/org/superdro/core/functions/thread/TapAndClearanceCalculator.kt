package org.superdro.core.functions.thread

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * The entry chamfer type of a hand or machine tap.
 *
 * @property pitches The nominal chamfer length in pitches.
 */
enum class TapChamferKind(val pitches: Double) {
    Bottoming(2.5),
    Plug(4.5),
    Taper(9.0),
}

/**
 * The closest standard drill in one [DrillSeries] and its actual thread engagement percentage.
 *
 * @property drill The closest standard drill in the series.
 * @property actualEngagementPercent The actual thread engagement percentage that [drill] gives.
 */
data class SeriesTapDrill(
    val drill: StandardDrill,
    val actualEngagementPercent: Double,
)

/**
 * The tap drill result for a target engagement percentage. All lengths are µm.
 *
 * @property engagementPercent The target thread engagement percentage (`50.0` to `85.0`).
 * @property theoreticalDiameterUm The exact tap drill diameter for [engagementPercent].
 * @property nearestDrills The nearest standard drills to [theoreticalDiameterUm].
 * @property closestActualEngagementPercent The actual thread engagement percentage produced by
 *   `nearestDrills.closest`.
 * @property closestWithinMinorLimits True when `nearestDrills.closest.diameterUm` is inside the
 *   class minor diameter range `[D1min, D1max]`, false when it is outside, or null when no class
 *   limits are provided.
 * @property seriesDrills The closest standard drill for each valid [DrillSeries]. The list omits a
 *   series when the tap drill diameter is outside the series range or gives an invalid thread
 *   engagement percentage.
 */
data class TapDrillResult(
    val engagementPercent: Double,
    val theoreticalDiameterUm: Double,
    val nearestDrills: NearestDrills,
    val closestActualEngagementPercent: Double,
    val closestWithinMinorLimits: Boolean?,
    val seriesDrills: List<SeriesTapDrill> = emptyList(),
)

/**
 * The fastener clearance hole diameters and the 90° entry countersink diameter. All lengths are µm.
 *
 * @property closeFitUm The close-fit clearance hole diameter (ISO 273 H12 or ASME B18.2.8 close).
 * @property normalFitUm The normal-fit clearance hole diameter (ISO 273 H13 or ASME B18.2.8 normal).
 * @property looseFitUm The loose-fit clearance hole diameter (ISO 273 H14 or ASME B18.2.8 loose).
 * @property countersink90DiameterUm The 90° entry countersink diameter (`D + 300 µm`).
 */
data class ClearanceHoleSizes(
    val closeFitUm: Double,
    val normalFitUm: Double,
    val looseFitUm: Double,
    val countersink90DiameterUm: Double,
)

/**
 * The minimum blind-hole drill depths (excluding drill point cone) for a required full thread
 * depth. All lengths are µm.
 *
 * @property fullThreadDepthUm The required full-thread depth.
 * @property bottomingMinDrillDepthUm Minimum drill depth for a bottoming tap (`2.5 + 1.5` pitches).
 * @property plugMinDrillDepthUm Minimum drill depth for a plug tap (`4.5 + 1.5` pitches).
 * @property taperMinDrillDepthUm Minimum drill depth for a taper tap (`9.0 + 1.5` pitches).
 */
data class BlindHoleDepths(
    val fullThreadDepthUm: Double,
    val bottomingMinDrillDepthUm: Double,
    val plugMinDrillDepthUm: Double,
    val taperMinDrillDepthUm: Double,
)

/**
 * Combined tap drill, roll-form tap drill, clearance hole, and blind-hole depth results.
 *
 * @property cutTap The cut-tap drill calculation result.
 * @property formTap The roll-form tap drill calculation result (for 60° threads), or null.
 * @property clearance The fastener clearance hole and countersink diameters.
 * @property blindHole The minimum blind-hole depths when a full thread depth is given, or null.
 */
data class TapAndHolePrepSummary(
    val cutTap: TapDrillResult,
    val formTap: TapDrillResult?,
    val clearance: ClearanceHoleSizes,
    val blindHole: BlindHoleDepths?,
)

/**
 * Calculates cut-tap drill sizes, roll-form tap drill sizes, standard drill table lookups,
 * fastener clearance hole diameters, and blind-hole tap depths. All lengths are µm.
 */
object TapAndClearanceCalculator {

    /** Double radial thread height factor `2 × (5/8) × H = 1.2990381` for 60° threads. */
    const val FULL_THREAD_FACTOR_60: Double = 1.2990381

    /** Double radial thread height factor `2 × h3 = 1.2806548` for 55° Whitworth/BSPP threads. */
    const val FULL_THREAD_FACTOR_55: Double = 1.2806548

    /** Double radial thread height factor `0.68` for 60° roll-form taps. */
    const val FORM_TAP_FACTOR_60: Double = 0.68

    /** Minimum allowed thread engagement percentage. */
    const val MIN_ENGAGEMENT_PERCENT: Double = 50.0

    /** Maximum allowed thread engagement percentage. */
    const val MAX_ENGAGEMENT_PERCENT: Double = 85.0

    /** Default cut-tap thread engagement percentage. */
    const val DEFAULT_CUT_ENGAGEMENT_PERCENT: Double = 75.0

    /** Default roll-form tap thread engagement percentage. */
    const val DEFAULT_FORM_ENGAGEMENT_PERCENT: Double = 65.0

    /** 90° entry countersink diameter allowance over the nominal major diameter (`300 µm`). */
    const val COUNTERSINK_ALLOWANCE_UM: Double = 300.0

    /** Extra clearance pitches at the bottom of a blind hole (`1.5` pitches). */
    const val BLIND_HOLE_EXTRA_PITCHES: Double = 1.5

    private const val UM_PER_MM: Double = 1000.0
    private const val UM_PER_INCH: Double = 25400.0
    private const val LIMIT_TOLERANCE_UM: Double = 1e-6

    /**
     * Calculates the cut-tap drill diameter, nearest standard drills, actual thread engagement
     * percentage, and minor-diameter limit check.
     */
    fun cutTapDrill(
        majorDiameterUm: Double,
        pitchUm: Double,
        includedAngleDeg: Double = 60.0,
        engagementPercent: Double = DEFAULT_CUT_ENGAGEMENT_PERCENT,
        metric: Boolean = true,
        minorLimitsUm: Pair<Double, Double>? = null,
    ): TapDrillResult {
        val clampedPct = engagementPercent.coerceIn(MIN_ENGAGEMENT_PERCENT, MAX_ENGAGEMENT_PERCENT)
        val fullThreadHeightUm = fullCutThreadHeightUm(pitchUm, includedAngleDeg)
        val theoreticalDiameterUm = majorDiameterUm - (clampedPct / 100.0) * fullThreadHeightUm
        val nearest = StandardDrillTables.nearest(theoreticalDiameterUm, metricPreferred = metric)
        val actualPct = if (fullThreadHeightUm > 0.0) {
            ((majorDiameterUm - nearest.closest.diameterUm) / fullThreadHeightUm) * 100.0
        } else {
            0.0
        }
        val withinLimits = minorLimitsUm?.let { (minUm, maxUm) ->
            nearest.closest.diameterUm in (minUm - LIMIT_TOLERANCE_UM)..(maxUm + LIMIT_TOLERANCE_UM)
        }
        val seriesDrills = cutTapSeriesDrills(
            majorDiameterUm = majorDiameterUm,
            pitchUm = pitchUm,
            theoreticalDiameterUm = theoreticalDiameterUm,
            includedAngleDeg = includedAngleDeg,
        )
        return TapDrillResult(
            engagementPercent = clampedPct,
            theoreticalDiameterUm = theoreticalDiameterUm,
            nearestDrills = nearest,
            closestActualEngagementPercent = actualPct,
            closestWithinMinorLimits = withinLimits,
            seriesDrills = seriesDrills,
        )
    }

    /**
     * Gives the closest standard drill for each valid [DrillSeries].
     *
     * The result includes a series only when both conditions are true:
     * 1. [theoreticalDiameterUm] is inside the series range, or the boundary drill gives an
     *    engagement in `[MIN_ENGAGEMENT_PERCENT, MAX_ENGAGEMENT_PERCENT]`.
     * 2. [actualCutTapEngagementPercent] is greater than `0.0` and at most `100.0`.
     */
    fun cutTapSeriesDrills(
        majorDiameterUm: Double,
        pitchUm: Double,
        theoreticalDiameterUm: Double,
        includedAngleDeg: Double = 60.0,
    ): List<SeriesTapDrill> = DrillSeries.entries.mapNotNull { series ->
        val nearest = StandardDrillTables.nearestInSeries(theoreticalDiameterUm, series) ?: return@mapNotNull null
        val drill = nearest.closest
        val pct = actualCutTapEngagementPercent(
            majorDiameterUm = majorDiameterUm,
            pitchUm = pitchUm,
            drillDiameterUm = drill.diameterUm,
            includedAngleDeg = includedAngleDeg,
        )
        val withinSeriesBounds = nearest.smallerOrEqual != null && nearest.largerOrEqual != null
        val boundaryValidEngagement = pct in MIN_ENGAGEMENT_PERCENT..MAX_ENGAGEMENT_PERCENT
        if ((withinSeriesBounds || boundaryValidEngagement) && pct > 0.0 && pct <= 100.0) {
            SeriesTapDrill(drill = drill, actualEngagementPercent = pct)
        } else {
            null
        }
    }

    /**
     * Calculates the actual cut-tap thread engagement percentage for a specific [drillDiameterUm].
     */
    fun actualCutTapEngagementPercent(
        majorDiameterUm: Double,
        pitchUm: Double,
        drillDiameterUm: Double,
        includedAngleDeg: Double = 60.0,
    ): Double {
        val fullThreadHeightUm = fullCutThreadHeightUm(pitchUm, includedAngleDeg)
        if (fullThreadHeightUm <= 0.0) return 0.0
        return ((majorDiameterUm - drillDiameterUm) / fullThreadHeightUm) * 100.0
    }

    /**
     * Calculates the 60° roll-form tap drill diameter (`D − (pct / 100) × 0.68 × P`), nearest
     * standard drills, and actual thread engagement percentage.
     */
    fun formTapDrill(
        majorDiameterUm: Double,
        pitchUm: Double,
        engagementPercent: Double = DEFAULT_FORM_ENGAGEMENT_PERCENT,
        metric: Boolean = true,
        minorLimitsUm: Pair<Double, Double>? = null,
    ): TapDrillResult {
        val clampedPct = engagementPercent.coerceIn(MIN_ENGAGEMENT_PERCENT, MAX_ENGAGEMENT_PERCENT)
        val fullFormHeightUm = FORM_TAP_FACTOR_60 * pitchUm
        val theoreticalDiameterUm = majorDiameterUm - (clampedPct / 100.0) * fullFormHeightUm
        val nearest = StandardDrillTables.nearest(theoreticalDiameterUm, metricPreferred = metric)
        val actualPct = if (fullFormHeightUm > 0.0) {
            ((majorDiameterUm - nearest.closest.diameterUm) / fullFormHeightUm) * 100.0
        } else {
            0.0
        }
        val withinLimits = minorLimitsUm?.let { (minUm, maxUm) ->
            nearest.closest.diameterUm in (minUm - LIMIT_TOLERANCE_UM)..(maxUm + LIMIT_TOLERANCE_UM)
        }
        return TapDrillResult(
            engagementPercent = clampedPct,
            theoreticalDiameterUm = theoreticalDiameterUm,
            nearestDrills = nearest,
            closestActualEngagementPercent = actualPct,
            closestWithinMinorLimits = withinLimits,
        )
    }

    /**
     * Gives the close, normal, and loose clearance hole diameters (ISO 273 for metric sizes,
     * ASME B18.2.8 for inch sizes, with a formula fallback for custom diameters) and the 90°
     * entry countersink diameter (`D + 300 µm`).
     */
    fun clearanceHoles(majorDiameterUm: Double, metric: Boolean = true): ClearanceHoleSizes {
        val countersinkUm = majorDiameterUm + COUNTERSINK_ALLOWANCE_UM
        val row = if (metric) {
            findClearanceRow(majorDiameterUm, iso273RowsUm, toleranceUm = 50.0)
        } else {
            findClearanceRow(majorDiameterUm, asmeB18RowsUm, toleranceUm = 50.0)
        }
        if (row != null) {
            return ClearanceHoleSizes(
                closeFitUm = row.closeUm,
                normalFitUm = row.normalUm,
                looseFitUm = row.looseUm,
                countersink90DiameterUm = countersinkUm,
            )
        }
        return if (metric) {
            ClearanceHoleSizes(
                closeFitUm = majorDiameterUm * 1.05,
                normalFitUm = majorDiameterUm * 1.10,
                looseFitUm = majorDiameterUm * 1.15,
                countersink90DiameterUm = countersinkUm,
            )
        } else {
            ClearanceHoleSizes(
                closeFitUm = majorDiameterUm + UM_PER_INCH / 64.0,
                normalFitUm = majorDiameterUm + UM_PER_INCH / 32.0,
                looseFitUm = majorDiameterUm + UM_PER_INCH / 16.0,
                countersink90DiameterUm = countersinkUm,
            )
        }
    }

    /**
     * Calculates the minimum blind-hole drill depth for bottoming, plug, and taper taps:
     * `fullThreadDepthUm + (chamfer.pitches + 1.5) × pitchUm`.
     */
    fun blindHoleDepths(fullThreadDepthUm: Double, pitchUm: Double): BlindHoleDepths =
        BlindHoleDepths(
            fullThreadDepthUm = fullThreadDepthUm,
            bottomingMinDrillDepthUm = minBlindHoleDepthUm(fullThreadDepthUm, pitchUm, TapChamferKind.Bottoming),
            plugMinDrillDepthUm = minBlindHoleDepthUm(fullThreadDepthUm, pitchUm, TapChamferKind.Plug),
            taperMinDrillDepthUm = minBlindHoleDepthUm(fullThreadDepthUm, pitchUm, TapChamferKind.Taper),
        )

    /** Calculates the minimum blind-hole drill depth for one [chamfer] kind. */
    fun minBlindHoleDepthUm(
        fullThreadDepthUm: Double,
        pitchUm: Double,
        chamfer: TapChamferKind,
    ): Double = fullThreadDepthUm + (chamfer.pitches + BLIND_HOLE_EXTRA_PITCHES) * pitchUm

    /**
     * Combines [cutTapDrill], [formTapDrill] (when [includedAngleDeg] is 60°), [clearanceHoles],
     * and [blindHoleDepths] (when [fullThreadDepthUm] is positive).
     */
    fun summary(
        majorDiameterUm: Double,
        pitchUm: Double,
        includedAngleDeg: Double = 60.0,
        cutEngagementPercent: Double = DEFAULT_CUT_ENGAGEMENT_PERCENT,
        formEngagementPercent: Double = DEFAULT_FORM_ENGAGEMENT_PERCENT,
        metric: Boolean = true,
        minorLimitsUm: Pair<Double, Double>? = null,
        fullThreadDepthUm: Double? = null,
    ): TapAndHolePrepSummary {
        val cutTap = cutTapDrill(
            majorDiameterUm = majorDiameterUm,
            pitchUm = pitchUm,
            includedAngleDeg = includedAngleDeg,
            engagementPercent = cutEngagementPercent,
            metric = metric,
            minorLimitsUm = minorLimitsUm,
        )
        val formTap = if (abs(includedAngleDeg - 60.0) < 1e-4) {
            formTapDrill(
                majorDiameterUm = majorDiameterUm,
                pitchUm = pitchUm,
                engagementPercent = formEngagementPercent,
                metric = metric,
                minorLimitsUm = minorLimitsUm,
            )
        } else {
            null
        }
        val clearance = clearanceHoles(majorDiameterUm = majorDiameterUm, metric = metric)
        val blindHole = fullThreadDepthUm
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { depthUm -> blindHoleDepths(depthUm, pitchUm) }
        return TapAndHolePrepSummary(
            cutTap = cutTap,
            formTap = formTap,
            clearance = clearance,
            blindHole = blindHole,
        )
    }

    private fun fullCutThreadHeightUm(pitchUm: Double, includedAngleDeg: Double): Double = when {
        abs(includedAngleDeg - 60.0) < 1e-4 -> FULL_THREAD_FACTOR_60 * pitchUm
        abs(includedAngleDeg - 55.0) < 1e-4 -> FULL_THREAD_FACTOR_55 * pitchUm
        else -> {
            val halfAngleRad = (includedAngleDeg / 2.0) * (PI / 180.0)
            0.75 * pitchUm / tan(halfAngleRad)
        }
    }

    private data class ClearanceRow(
        val nominalUm: Double,
        val closeUm: Double,
        val normalUm: Double,
        val looseUm: Double,
    )

    private fun findClearanceRow(
        majorDiameterUm: Double,
        rows: List<ClearanceRow>,
        toleranceUm: Double,
    ): ClearanceRow? = rows.firstOrNull { row -> abs(row.nominalUm - majorDiameterUm) <= toleranceUm }

    private fun mmRow(nominalMm: Double, closeMm: Double, normalMm: Double, looseMm: Double): ClearanceRow =
        ClearanceRow(
            nominalUm = nominalMm * UM_PER_MM,
            closeUm = closeMm * UM_PER_MM,
            normalUm = normalMm * UM_PER_MM,
            looseUm = looseMm * UM_PER_MM,
        )

    private fun inchRow(nominalIn: Double, closeIn: Double, normalIn: Double, looseIn: Double): ClearanceRow =
        ClearanceRow(
            nominalUm = nominalIn * UM_PER_INCH,
            closeUm = closeIn * UM_PER_INCH,
            normalUm = normalIn * UM_PER_INCH,
            looseUm = looseIn * UM_PER_INCH,
        )

    /** ISO 273 metric fastener clearance holes from M1.6 to M68. */
    private val iso273RowsUm: List<ClearanceRow> = listOf(
        mmRow(1.6, 1.7, 1.8, 2.0),
        mmRow(2.0, 2.2, 2.4, 2.6),
        mmRow(2.5, 2.7, 2.9, 3.1),
        mmRow(3.0, 3.2, 3.4, 3.6),
        mmRow(3.5, 3.7, 3.9, 4.2),
        mmRow(4.0, 4.3, 4.5, 4.8),
        mmRow(4.5, 4.8, 5.0, 5.3),
        mmRow(5.0, 5.3, 5.5, 5.8),
        mmRow(6.0, 6.4, 6.6, 7.0),
        mmRow(7.0, 7.4, 7.6, 8.0),
        mmRow(8.0, 8.4, 9.0, 10.0),
        mmRow(10.0, 10.5, 11.0, 12.0),
        mmRow(12.0, 13.0, 13.5, 14.5),
        mmRow(14.0, 15.0, 15.5, 16.5),
        mmRow(16.0, 17.0, 17.5, 18.5),
        mmRow(18.0, 19.0, 20.0, 21.0),
        mmRow(20.0, 21.0, 22.0, 24.0),
        mmRow(22.0, 23.0, 24.0, 26.0),
        mmRow(24.0, 25.0, 26.0, 28.0),
        mmRow(27.0, 28.0, 30.0, 32.0),
        mmRow(30.0, 31.0, 33.0, 35.0),
        mmRow(33.0, 34.0, 36.0, 38.0),
        mmRow(36.0, 37.0, 39.0, 42.0),
        mmRow(39.0, 40.0, 42.0, 45.0),
        mmRow(42.0, 43.0, 45.0, 48.0),
        mmRow(45.0, 46.0, 48.0, 52.0),
        mmRow(48.0, 50.0, 52.0, 56.0),
        mmRow(52.0, 54.0, 56.0, 62.0),
        mmRow(56.0, 58.0, 62.0, 66.0),
        mmRow(60.0, 62.0, 66.0, 70.0),
        mmRow(64.0, 66.0, 70.0, 74.0),
        mmRow(68.0, 70.0, 74.0, 78.0),
    )

    /** ASME B18.2.8 inch fastener clearance holes from #0 to 2 in. */
    private val asmeB18RowsUm: List<ClearanceRow> = listOf(
        inchRow(0.0600, 0.0635, 0.0670, 0.0730),
        inchRow(0.0730, 0.0760, 0.0810, 0.0890),
        inchRow(0.0860, 0.0890, 0.0935, 0.1040),
        inchRow(0.0990, 0.1040, 0.1065, 0.1160),
        inchRow(0.1120, 0.1160, 0.1200, 0.1285),
        inchRow(0.1250, 0.1285, 0.1360, 0.1470),
        inchRow(0.1380, 0.1440, 0.1495, 0.1590),
        inchRow(0.1640, 0.1695, 0.1770, 0.1890),
        inchRow(0.1900, 0.1960, 0.2040, 0.21875),
        inchRow(0.2160, 0.2210, 0.2280, 0.2420),
        inchRow(0.2500, 0.2570, 0.2660, 0.28125),
        inchRow(0.3125, 0.3230, 0.3320, 0.34375),
        inchRow(0.3750, 0.3860, 0.3970, 0.40625),
        inchRow(0.4375, 0.453125, 0.46875, 0.484375),
        inchRow(0.5000, 0.515625, 0.53125, 0.5625),
        inchRow(0.5625, 0.578125, 0.59375, 0.6250),
        inchRow(0.6250, 0.640625, 0.65625, 0.6875),
        inchRow(0.7500, 0.765625, 0.78125, 0.8125),
        inchRow(0.8750, 0.890625, 0.90625, 0.9375),
        inchRow(1.0000, 1.015625, 1.03125, 1.09375),
        inchRow(1.1250, 1.140625, 1.15625, 1.21875),
        inchRow(1.2500, 1.265625, 1.28125, 1.34375),
        inchRow(1.3750, 1.390625, 1.40625, 1.46875),
        inchRow(1.5000, 1.515625, 1.53125, 1.59375),
        inchRow(1.7500, 1.765625, 1.78125, 1.84375),
        inchRow(2.0000, 2.015625, 2.03125, 2.09375),
    )
}
