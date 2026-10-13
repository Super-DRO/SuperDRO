package org.superdro.core.functions.errormap

import org.superdro.core.model.MapPoint

/**
 * The frame of a wizard map: the values that the
 * builder removed before the node fit.
 *
 * @property pin The zero point of the map, in target counts.
 * @property level The fit value at the pin that the builder removed, in counts.
 * @property slope The linear term (counts of error for each count of travel).
 * @property directionSplit `atMidTravel` of the direction split, or null with no split.
 */
data class MapFrame(
    val pin: Double,
    val level: Double,
    val slope: Double,
    val directionSplit: Double?,
)

/**
 * The periodic error (`mk.m`).
 *
 * @property periodCounts The median of the best periods of the passes, in counts.
 * @property amplitudeCounts The mean amplitude, in counts.
 * @property powerFraction The mean fraction of the variance that the sine fit explains (0 to 1).
 * @property passCount The number of passes of the analysis.
 */
data class PeriodicError(
    val periodCounts: Double,
    val amplitudeCounts: Double,
    val powerFraction: Double,
    val passCount: Int,
)

/**
 * The error at the two ends of the travel against the middle. The diagnostics show
 * "ratio× — ends ±outer/2 vs middle ±middle/2".
 *
 * @property outerSpread P95 − P5 of the samples within 5 % of the span from each end.
 * @property middleSpread P95 − P5 of the other samples.
 * @property ratio `outerSpread / middleSpread`, or null when [middleSpread] is 0.
 */
data class TravelExtremes(val outerSpread: Double, val middleSpread: Double, val ratio: Double?)

/** The result of the verification pass. It does not change the map and does not block "Save Map". */
sealed interface VerificationResult {

    /** "Not recorded": there is no verification pass. */
    data object NotRecorded : VerificationResult

    /** "Nothing to measure": the verification pass gives no sample, or the map has no node. */
    data object NothingToMeasure : VerificationResult

    /**
     * The report shows `min to max`. The diagnostics show the offset.
     *
     * @property minResidual The smallest residual, in counts.
     * @property maxResidual The largest residual, in counts.
     * @property frameOffsetCounts The median residual, in counts.
     * @property residuals The residual of each unique position, for the chart series "Verification".
     */
    data class Measured(
        val minResidual: Double,
        val maxResidual: Double,
        val frameOffsetCounts: Double,
        val residuals: List<MapSample>,
    ) : VerificationResult
}

/**
 * One advice item of the report.
 *
 * @property blocksSave True when the item stops "Save Map".
 */
enum class ErrorMapAdvice(val blocksSave: Boolean) {
    /** `error_map_wizard_advice_worse`: the map has nodes and the improvement is less than 1.0. */
    MapMakesItWorse(true),

    /** `error_map_wizard_advice_reference_moved`: `referenceDrift > 5 × repeatability` and repeatability > 0. */
    ReferenceMoved(false),

    /** `error_map_wizard_advice_target_stalls`: a dead band in the target, 2 events or more, `spread <= 0.5 × counts`. */
    TargetStalls(false),

    /** `error_map_wizard_advice_truncated`: a pass reached 200000 pairs. */
    CaptureTruncated(false),
}

/**
 * The statistics of the report. The counts are encoder counts.
 *
 * @property passCount The number of passes with samples.
 * @property sampleCount The number of pairs of all passes.
 * @property nodeCount The number of nodes.
 * @property medianNodeSpacingCounts The median of the position differences of neighbor nodes.
 * @property spreadBeforeCounts P95 − P5 of `delta''` of all samples.
 * @property spreadAfterCounts P95 − P5 of the held-out residuals.
 * @property repeatabilityCounts P95 − P5 of `delta'' − median(bin)` in the bins of 20 counts
 *   with samples of 2 passes or more, or null ("Not measured").
 * @property linearTermPpm `slope × 1 000 000`.
 * @property heldOutIsWholePass True for 2 passes or more ("Whole pass"), else "Interleaved".
 * @property closureCountsByPass The closure of each pass, or null.
 * @property samplesPerNode `K`.
 * @property violations The problems of [MapValidation.validateErrorMap] for the nodes.
 * @property directionSplitCounts `atMidTravel`, or null with no split.
 * @property periodicError The periodic error, or null.
 * @property deadBand The dead band after a reversal, or null.
 * @property travelExtremes The travel extremes, or null.
 * @property pinCounts The pin.
 * @property referenceDriftCounts The reference drift for 2 passes or more, or null.
 * @property rawLow The smallest bin median of `delta''`.
 * @property rawHigh The largest bin median of `delta''`.
 * @property correctedLow The smallest bin median of the held-out residuals.
 * @property correctedHigh The largest bin median of the held-out residuals.
 */
data class ErrorMapStatistics(
    val passCount: Int,
    val sampleCount: Int,
    val nodeCount: Int,
    val medianNodeSpacingCounts: Double?,
    val spreadBeforeCounts: Double?,
    val spreadAfterCounts: Double?,
    val repeatabilityCounts: Double?,
    val linearTermPpm: Double,
    val heldOutIsWholePass: Boolean,
    val closureCountsByPass: List<Double?>,
    val samplesPerNode: Int,
    val violations: List<MapProblem>,
    val directionSplitCounts: Double?,
    val periodicError: PeriodicError?,
    val deadBand: DeadBandResult?,
    val travelExtremes: TravelExtremes?,
    val pinCounts: Double,
    val referenceDriftCounts: Double?,
    val rawLow: Double?,
    val rawHigh: Double?,
    val correctedLow: Double?,
    val correctedHigh: Double?,
) {
    /**
     * The improvement `(rawHigh − rawLow) / (correctedHigh − correctedLow)`. It is 1.0 when a value
     * is not available or the denominator is 0 or less.
     */
    val improvement: Double
        get() {
            val rl = rawLow ?: return 1.0
            val rh = rawHigh ?: return 1.0
            val cl = correctedLow ?: return 1.0
            val ch = correctedHigh ?: return 1.0
            val denominator = ch - cl
            if (denominator <= 0.0) return 1.0
            return (rh - rl) / denominator
        }
}

/** The result of "Save Map" of the report. */
sealed interface WizardSaveResult {

    /**
     * The map is valid. The store writes [points] to the encoder profile and deletes the
     * session file.
     *
     * @property text The saved map text with the provenance comments.
     */
    data class Saved(val points: List<MapPoint>, val provenance: MapProvenance, val text: String) : WizardSaveResult

    /** "Save Map" is not possible: fewer than 2 passes, no nodes, or an advice that blocks it. */
    data object NotAllowed : WizardSaveResult

    /** "The map could not be saved." The validator found problems. */
    data class Refused(val problems: List<MapProblem>) : WizardSaveResult
}

/**
 * The report of the map builder.
 *
 * @property nodes The final nodes of the map ("Fitted Map").
 * @property frame The frame of the map.
 * @property statistics The statistics.
 * @property advice The advice items, in the order of [ErrorMapAdvice].
 * @property verification The verification result.
 * @property rawSeries `delta''` of each pass with 2 samples or more ("Recorded Raw Data").
 */
data class ErrorMapReport(
    val nodes: List<MapPoint>,
    val frame: MapFrame,
    val statistics: ErrorMapStatistics,
    val advice: List<ErrorMapAdvice>,
    val verification: VerificationResult,
    val rawSeries: List<List<MapSample>>,
) {
    /** The label of the expected error: true gives "Expected error (in-sample)" (interleaved folds). */
    val expectedErrorInSample: Boolean get() = !statistics.heldOutIsWholePass

    /** True when the report shows "Zero at" (the pin is not 0). */
    val showsZeroAt: Boolean get() = frame.pin != 0.0

    /**
     * True when "Save Map" is active: 2 passes or more, at least 1 node, and no advice that blocks
     * it. The rule "an improvement of 1 count or more" is the improvement ratio of 1.0 or more,
     * the rule of [ErrorMapAdvice.MapMakesItWorse].
     */
    val canSave: Boolean
        get() = statistics.passCount >= 2 && nodes.isNotEmpty() && advice.none { it.blocksSave }

    /**
     * "Save Map". The text has the comments `generated-by: SuperDRO Error Map
     * Wizard`, `generated-on`, `passes`, `spread-before-counts`, and `spread-after-counts` (the
     * doubles as Kotlin `Double.toString`, for example `23.0`), then one `position,correction`
     * line for each node.
     *
     * @param generatedOn The date and time text `yyyy-MM-dd HH:mm:ss`.
     */
    fun saveMap(generatedOn: String): WizardSaveResult {
        if (!canSave) return WizardSaveResult.NotAllowed
        val problems = MapValidation.validateErrorMap(nodes)
        if (problems.isNotEmpty()) return WizardSaveResult.Refused(problems)
        val provenance = MapProvenance(
            generatedBy = MapProvenance.SUPERDRO_WIZARD,
            generatedOn = generatedOn,
            passes = statistics.passCount,
            spreadBeforeCounts = statistics.spreadBeforeCounts,
            spreadAfterCounts = statistics.spreadAfterCounts,
        )
        return WizardSaveResult.Saved(nodes, provenance, MapEditor.mapText(nodes, provenance))
    }
}

/** The result of the map builder. */
sealed interface ErrorMapBuild {

    /** "Not enough data to build a map." The builder failed or gave no nodes. */
    data object NotEnoughData : ErrorMapBuild

    /** The builder gave a map. */
    data class Built(val report: ErrorMapReport) : ErrorMapBuild
}
