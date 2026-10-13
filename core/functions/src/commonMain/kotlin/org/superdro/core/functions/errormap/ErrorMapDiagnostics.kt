package org.superdro.core.functions.errormap

/**
 * The context rows of the diagnostics screen.
 *
 * @property appVersion The app version text.
 * @property adapterName The adapter name.
 * @property firmware The firmware text of the adapter, or null.
 * @property referenceInput The letter of the reference input, or null.
 */
data class WizardContext(
    val appVersion: String,
    val adapterName: String,
    val firmware: String?,
    val referenceInput: Char?,
)

/**
 * One pass row of the diagnostics: "N readings · span · closure".
 *
 * @property readings N, the number of pairs.
 * @property spanCounts `max − min` of the target counts of the pass.
 * @property closureCounts The closure of the pass, or null.
 */
data class PassDiagnostics(val readings: Int, val spanCounts: Long, val closureCounts: Double?)

/** The kind of the held-out folds: "Whole pass" or "Interleaved". */
enum class HeldOutFolds { WholePass, Interleaved }

/**
 * The values of the diagnostics screen "Error Map Diagnostics — input %1$s". The
 * UI formats the values. All spreads show as ± half of the P95 − P5 value: the `…Half` fields
 * give these halves.
 */
data class ErrorMapDiagnostics(
    val context: WizardContext,
    val rawLow: Double?,
    val rawHigh: Double?,
    val correctedLow: Double?,
    val correctedHigh: Double?,
    val spreadBeforeHalf: Double?,
    val spreadAfterHalf: Double?,
    val repeatabilityHalf: Double?,
    val improvement: Double,
    val linearTermPpmRounded: Long,
    val directionSplitCounts: Double?,
    val periodicError: PeriodicError?,
    val periodicPercent: Int?,
    val travelExtremes: TravelExtremes?,
    val travelOuterHalf: Double?,
    val travelMiddleHalf: Double?,
    val deadBand: DeadBandResult?,
    val referenceDriftCounts: Double?,
    val verificationOffsetCounts: Double?,
    val zeroAt: Double,
    val nodes: Int,
    val nodeSpacing: Double?,
    val readingsPerNode: Int,
    val readings: Int,
    val passes: Int,
    val heldOutFolds: HeldOutFolds,
    val captureTruncated: Boolean,
    val glitchesDropped: Int,
    val passRows: List<PassDiagnostics>,
) {
    companion object {
        /**
         * Gives the diagnostics of [report]. [passes] are the passes of the build, in order.
         * The periodic error row "%1$d counts, ±%2$s (%3$d%%)" uses [PeriodicError.periodCounts],
         * [PeriodicError.amplitudeCounts], and [periodicPercent].
         */
        fun of(report: ErrorMapReport, passes: List<RecordedPass>, context: WizardContext): ErrorMapDiagnostics {
            val stats = report.statistics
            val usable = passes.filter { !it.isEmpty }
            val verification = report.verification as? VerificationResult.Measured
            return ErrorMapDiagnostics(
                context = context,
                rawLow = stats.rawLow,
                rawHigh = stats.rawHigh,
                correctedLow = stats.correctedLow,
                correctedHigh = stats.correctedHigh,
                spreadBeforeHalf = stats.spreadBeforeCounts?.let { it / 2.0 },
                spreadAfterHalf = stats.spreadAfterCounts?.let { it / 2.0 },
                repeatabilityHalf = stats.repeatabilityCounts?.let { it / 2.0 },
                improvement = stats.improvement,
                linearTermPpmRounded = Stats.roundHalfUp(stats.linearTermPpm),
                directionSplitCounts = stats.directionSplitCounts,
                periodicError = stats.periodicError,
                periodicPercent = stats.periodicError?.let { Stats.roundHalfUp(it.powerFraction * 100.0).toInt() },
                travelExtremes = stats.travelExtremes,
                travelOuterHalf = stats.travelExtremes?.let { it.outerSpread / 2.0 },
                travelMiddleHalf = stats.travelExtremes?.let { it.middleSpread / 2.0 },
                deadBand = stats.deadBand,
                referenceDriftCounts = stats.referenceDriftCounts,
                verificationOffsetCounts = verification?.frameOffsetCounts,
                zeroAt = report.frame.pin,
                nodes = stats.nodeCount,
                nodeSpacing = stats.medianNodeSpacingCounts,
                readingsPerNode = stats.samplesPerNode,
                readings = stats.sampleCount,
                passes = stats.passCount,
                heldOutFolds = if (stats.heldOutIsWholePass) HeldOutFolds.WholePass else HeldOutFolds.Interleaved,
                captureTruncated = usable.any { it.truncated },
                glitchesDropped = usable.sumOf { it.glitchesDropped },
                passRows = usable.mapIndexed { index, pass ->
                    PassDiagnostics(
                        readings = pass.pairs.size,
                        spanCounts = pass.pairs.maxOf { it.target } - pass.pairs.minOf { it.target },
                        closureCounts = stats.closureCountsByPass.getOrNull(index),
                    )
                },
            )
        }
    }
}
