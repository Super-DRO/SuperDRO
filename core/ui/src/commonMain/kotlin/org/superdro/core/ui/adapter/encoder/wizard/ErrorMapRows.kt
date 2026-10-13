package org.superdro.core.ui.adapter.encoder.wizard

import androidx.compose.runtime.Immutable
import org.superdro.core.functions.errormap.ErrorMapAdvice
import org.superdro.core.functions.errormap.ErrorMapDiagnostics
import org.superdro.core.functions.errormap.ErrorMapReport
import org.superdro.core.functions.errormap.HeldOutFolds
import org.superdro.core.functions.errormap.VerificationResult
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_advice_reference_moved
import org.superdro.core.resources.encoder_advice_target_stalls
import org.superdro.core.resources.encoder_advice_truncated
import org.superdro.core.resources.encoder_advice_worse
import org.superdro.core.resources.encoder_counts_value
import org.superdro.core.resources.encoder_diag_adapter
import org.superdro.core.resources.encoder_diag_app
import org.superdro.core.resources.encoder_diag_dead_band
import org.superdro.core.resources.encoder_diag_dead_band_value
import org.superdro.core.resources.encoder_diag_direction_split
import org.superdro.core.resources.encoder_diag_firmware
import org.superdro.core.resources.encoder_diag_folds
import org.superdro.core.resources.encoder_diag_folds_interleaved
import org.superdro.core.resources.encoder_diag_folds_whole
import org.superdro.core.resources.encoder_diag_glitches
import org.superdro.core.resources.encoder_diag_in_reference
import org.superdro.core.resources.encoder_diag_in_target
import org.superdro.core.resources.encoder_diag_no
import org.superdro.core.resources.encoder_diag_none
import org.superdro.core.resources.encoder_diag_pass_row
import org.superdro.core.resources.encoder_diag_periodic
import org.superdro.core.resources.encoder_diag_periodic_value
import org.superdro.core.resources.encoder_diag_reference
import org.superdro.core.resources.encoder_diag_reference_drift
import org.superdro.core.resources.encoder_diag_spread
import org.superdro.core.resources.encoder_diag_travel
import org.superdro.core.resources.encoder_diag_travel_value
import org.superdro.core.resources.encoder_diag_truncated
import org.superdro.core.resources.encoder_diag_verification_offset
import org.superdro.core.resources.encoder_diag_yes
import org.superdro.core.resources.encoder_map_spread_value
import org.superdro.core.resources.encoder_report_expected
import org.superdro.core.resources.encoder_report_expected_in_sample
import org.superdro.core.resources.encoder_report_improvement
import org.superdro.core.resources.encoder_report_improvement_value
import org.superdro.core.resources.encoder_report_linear_term
import org.superdro.core.resources.encoder_report_linear_term_value
import org.superdro.core.resources.encoder_report_node_spacing
import org.superdro.core.resources.encoder_report_nodes
import org.superdro.core.resources.encoder_report_not_measured
import org.superdro.core.resources.encoder_report_not_recorded
import org.superdro.core.resources.encoder_report_nothing_to_measure
import org.superdro.core.resources.encoder_report_passes
import org.superdro.core.resources.encoder_report_range
import org.superdro.core.resources.encoder_report_raw
import org.superdro.core.resources.encoder_report_readings
import org.superdro.core.resources.encoder_report_readings_per_node
import org.superdro.core.resources.encoder_report_repeatability
import org.superdro.core.resources.encoder_report_repeatability_value
import org.superdro.core.resources.encoder_report_verification
import org.superdro.core.resources.encoder_report_zero_at
import org.superdro.core.resources.encoder_wizard_pass
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.encoder.EncoderFormat
import org.superdro.core.ui.text.UiText

/** One row of the report or the diagnostics: a label and a value. */
@Immutable
data class InfoRow(val label: UiText, val value: UiText)

/**
 * The rows of the report and of the diagnostics.
 * All spreads show as ± half of the P95 − P5 value.
 */
object ErrorMapRows {

    private val DASH = UiText.Plain("—")

    /** The rows of the report screen, without the advice. */
    fun report(report: ErrorMapReport): List<InfoRow> {
        val stats = report.statistics
        val expectedLabel = if (report.expectedErrorInSample) Res.string.encoder_report_expected_in_sample else Res.string.encoder_report_expected
        return buildList {
            add(InfoRow(UiText.of(Res.string.encoder_report_raw), range(stats.rawLow, stats.rawHigh)))
            add(InfoRow(UiText.of(expectedLabel), range(stats.correctedLow, stats.correctedHigh)))
            add(InfoRow(UiText.of(Res.string.encoder_report_improvement), improvement(stats.improvement)))
            add(InfoRow(UiText.of(Res.string.encoder_report_repeatability), plusMinusOrNotMeasured(stats.repeatabilityCounts?.let { it / 2.0 })))
            add(InfoRow(UiText.of(Res.string.encoder_report_verification), verification(report.verification)))
            if (report.showsZeroAt) add(InfoRow(UiText.of(Res.string.encoder_report_zero_at), counts(report.frame.pin)))
            add(InfoRow(UiText.of(Res.string.encoder_report_passes), UiText.Plain(stats.passCount.toString())))
            add(InfoRow(UiText.of(Res.string.encoder_report_readings), UiText.Plain(stats.sampleCount.toString())))
            add(InfoRow(UiText.of(Res.string.encoder_report_nodes), UiText.Plain(stats.nodeCount.toString())))
            add(InfoRow(UiText.of(Res.string.encoder_report_node_spacing), stats.medianNodeSpacingCounts?.let(::counts) ?: DASH))
            add(InfoRow(UiText.of(Res.string.encoder_report_readings_per_node), UiText.Plain(stats.samplesPerNode.toString())))
            add(InfoRow(UiText.of(Res.string.encoder_report_linear_term), UiText.of(Res.string.encoder_report_linear_term_value, kotlin.math.round(stats.linearTermPpm).toLong())))
        }
    }

    /** The text of one advice item. */
    fun advice(advice: ErrorMapAdvice): UiText = UiText.of(
        when (advice) {
            ErrorMapAdvice.MapMakesItWorse -> Res.string.encoder_advice_worse
            ErrorMapAdvice.ReferenceMoved -> Res.string.encoder_advice_reference_moved
            ErrorMapAdvice.TargetStalls -> Res.string.encoder_advice_target_stalls
            ErrorMapAdvice.CaptureTruncated -> Res.string.encoder_advice_truncated
        },
    )

    /** The rows of the diagnostics screen, in the order of the screen. */
    fun diagnostics(d: ErrorMapDiagnostics): List<InfoRow> = buildList {
        add(InfoRow(UiText.of(Res.string.encoder_diag_app), UiText.Plain(d.context.appVersion)))
        add(InfoRow(UiText.of(Res.string.encoder_diag_adapter), UiText.Plain(d.context.adapterName)))
        add(InfoRow(UiText.of(Res.string.encoder_diag_firmware), d.context.firmware?.let { UiText.Plain(it) } ?: DASH))
        add(InfoRow(UiText.of(Res.string.encoder_diag_reference), d.context.referenceInput?.let(AdapterTexts::encoderInputName) ?: DASH))
        add(InfoRow(UiText.of(Res.string.encoder_report_raw), range(d.rawLow, d.rawHigh)))
        add(InfoRow(UiText.of(Res.string.encoder_report_expected), range(d.correctedLow, d.correctedHigh)))
        val before = d.spreadBeforeHalf
        val after = d.spreadAfterHalf
        add(
            InfoRow(
                UiText.of(Res.string.encoder_diag_spread),
                if (before != null && after != null) UiText.of(Res.string.encoder_map_spread_value, number(before), number(after)) else DASH,
            ),
        )
        add(InfoRow(UiText.of(Res.string.encoder_report_repeatability), plusMinusOrNotMeasured(d.repeatabilityHalf)))
        add(InfoRow(UiText.of(Res.string.encoder_report_improvement), improvement(d.improvement)))
        add(InfoRow(UiText.of(Res.string.encoder_report_linear_term), UiText.of(Res.string.encoder_report_linear_term_value, d.linearTermPpmRounded)))
        add(InfoRow(UiText.of(Res.string.encoder_diag_direction_split), d.directionSplitCounts?.let(::signedCounts) ?: UiText.of(Res.string.encoder_diag_none)))
        val periodic = d.periodicError
        add(
            InfoRow(
                UiText.of(Res.string.encoder_diag_periodic),
                if (periodic == null) {
                    UiText.of(Res.string.encoder_diag_none)
                } else {
                    UiText.of(
                        Res.string.encoder_diag_periodic_value,
                        kotlin.math.round(periodic.periodCounts).toLong(),
                        number(periodic.amplitudeCounts),
                        "${d.periodicPercent ?: 0}%",
                    )
                },
            ),
        )
        val travel = d.travelExtremes
        add(
            InfoRow(
                UiText.of(Res.string.encoder_diag_travel),
                if (travel == null) {
                    DASH
                } else {
                    UiText.of(
                        Res.string.encoder_diag_travel_value,
                        travel.ratio?.let { EncoderFormat.fixed(it, 1) } ?: "—",
                        number(d.travelOuterHalf ?: 0.0),
                        number(d.travelMiddleHalf ?: 0.0),
                    )
                },
            ),
        )
        val band = d.deadBand
        add(
            InfoRow(
                UiText.of(Res.string.encoder_diag_dead_band),
                if (band == null) {
                    UiText.of(Res.string.encoder_diag_none)
                } else {
                    UiText.of(
                        Res.string.encoder_diag_dead_band_value,
                        number(band.counts),
                        UiText.of(if (band.inReference) Res.string.encoder_diag_in_reference else Res.string.encoder_diag_in_target),
                        band.eventCount,
                        number(band.spread),
                    )
                },
            ),
        )
        add(InfoRow(UiText.of(Res.string.encoder_diag_reference_drift), d.referenceDriftCounts?.let(::counts) ?: DASH))
        add(InfoRow(UiText.of(Res.string.encoder_diag_verification_offset), d.verificationOffsetCounts?.let(::signedCounts) ?: DASH))
        add(InfoRow(UiText.of(Res.string.encoder_report_zero_at), counts(d.zeroAt)))
        add(InfoRow(UiText.of(Res.string.encoder_report_nodes), UiText.Plain(d.nodes.toString())))
        add(InfoRow(UiText.of(Res.string.encoder_report_node_spacing), d.nodeSpacing?.let(::counts) ?: DASH))
        add(InfoRow(UiText.of(Res.string.encoder_report_readings_per_node), UiText.Plain(d.readingsPerNode.toString())))
        add(InfoRow(UiText.of(Res.string.encoder_report_readings), UiText.Plain(d.readings.toString())))
        add(InfoRow(UiText.of(Res.string.encoder_report_passes), UiText.Plain(d.passes.toString())))
        add(
            InfoRow(
                UiText.of(Res.string.encoder_diag_folds),
                UiText.of(if (d.heldOutFolds == HeldOutFolds.WholePass) Res.string.encoder_diag_folds_whole else Res.string.encoder_diag_folds_interleaved),
            ),
        )
        add(InfoRow(UiText.of(Res.string.encoder_diag_truncated), UiText.of(if (d.captureTruncated) Res.string.encoder_diag_yes else Res.string.encoder_diag_no)))
        add(InfoRow(UiText.of(Res.string.encoder_diag_glitches), UiText.Plain(d.glitchesDropped.toString())))
        d.passRows.forEachIndexed { index, pass ->
            add(
                InfoRow(
                    UiText.of(Res.string.encoder_wizard_pass, index + 1),
                    UiText.of(Res.string.encoder_diag_pass_row, pass.readings, pass.spanCounts, pass.closureCounts?.let(::signed) ?: "—"),
                ),
            )
        }
    }

    /** "%1$s to %2$s counts", or a dash when a value is not known. */
    fun range(low: Double?, high: Double?): UiText =
        if (low == null || high == null) DASH else UiText.of(Res.string.encoder_report_range, number(low), number(high))

    /** The improvement ratio with the format `0.0` and "×". */
    fun improvement(ratio: Double): UiText = UiText.of(Res.string.encoder_report_improvement_value, EncoderFormat.fixed(ratio, 1))

    private fun plusMinusOrNotMeasured(half: Double?): UiText =
        half?.let { UiText.of(Res.string.encoder_report_repeatability_value, number(it)) } ?: UiText.of(Res.string.encoder_report_not_measured)

    private fun verification(result: VerificationResult): UiText = when (result) {
        VerificationResult.NotRecorded -> UiText.of(Res.string.encoder_report_not_recorded)
        VerificationResult.NothingToMeasure -> UiText.of(Res.string.encoder_report_nothing_to_measure)
        is VerificationResult.Measured -> range(result.minResidual, result.maxResidual)
    }

    private fun counts(value: Double): UiText = UiText.of(Res.string.encoder_counts_value, number(value))

    private fun signedCounts(value: Double): UiText = UiText.of(Res.string.encoder_counts_value, signed(value))

    /** The text of [value] with 1 decimal at most. */
    fun number(value: Double): String = EncoderFormat.number(value, 1)

    /** The text of [value] with a sign: "+1.5", "-2", "0". */
    fun signed(value: Double): String {
        val text = number(value)
        return if (value > 0.0 && text != "0") "+$text" else text
    }
}
