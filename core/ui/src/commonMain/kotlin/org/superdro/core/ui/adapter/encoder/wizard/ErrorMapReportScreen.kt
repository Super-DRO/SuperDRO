package org.superdro.core.ui.adapter.encoder.wizard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.errormap.ErrorMapReport
import org.superdro.core.functions.errormap.VerificationResult
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_diag_copied
import org.superdro.core.resources.encoder_diag_copy
import org.superdro.core.resources.encoder_diag_title
import org.superdro.core.resources.encoder_loading
import org.superdro.core.resources.encoder_report_advice
import org.superdro.core.resources.encoder_report_back
import org.superdro.core.resources.encoder_report_chart_description
import org.superdro.core.resources.encoder_report_diagnostics
import org.superdro.core.resources.encoder_report_fitted
import org.superdro.core.resources.encoder_report_no_session
import org.superdro.core.resources.encoder_report_not_enough
import org.superdro.core.resources.encoder_report_raw_data
import org.superdro.core.resources.encoder_report_save
import org.superdro.core.resources.encoder_report_save_blocked
import org.superdro.core.resources.encoder_report_title
import org.superdro.core.resources.encoder_report_verification_series
import org.superdro.core.resources.ic_encoder_copy
import org.superdro.core.ui.adapter.AdapterTexts
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.adapter.common.StatusKind
import org.superdro.core.ui.adapter.common.StatusLine
import org.superdro.core.ui.adapter.common.ValueRow
import org.superdro.core.ui.adapter.encoder.map.ChartSeries
import org.superdro.core.ui.adapter.encoder.map.MapChart
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tags of the report and the diagnostics. */
object ReportTags {
    const val SAVE: String = "report-save"
    const val DIAGNOSTICS: String = "report-diagnostics"
    const val BACK: String = "report-back"
    const val CHART: String = "report-chart"
    const val COPY: String = "diagnostics-copy"
}

/**
 * The report of the error map wizard: the chart, the rows of the report, the advice,
 * "Save Map", "Diagnostics", and "Back".
 */
@Composable
fun ErrorMapReportScreen(
    state: ErrorMapReportUiState,
    onSave: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val inputName = AdapterTexts.encoderInputName(state.input).resolve()
    ScreenScaffold(title = stringResource(Res.string.encoder_report_title, inputName), onBack = onBack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                val report = state.report
                when {
                    state.load == ReviewLoad.Loading -> StatusLine(StatusKind.Progress, stringResource(Res.string.encoder_loading))
                    state.load == ReviewLoad.NoSession -> StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_report_no_session))
                    state.load == ReviewLoad.NotEnoughData || report == null ->
                        StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_report_not_enough))
                    else -> ReportBody(state, report)
                }
                Spacer(Modifier.size(16.dp))
                ReportButtons(state, onSave, onDiagnostics, onBack)
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ReportBody(state: ErrorMapReportUiState, report: ErrorMapReport) {
    val rawColor = MaterialTheme.colorScheme.outline
    val fittedColor = accentColor()
    val verificationColor = MaterialTheme.colorScheme.tertiary
    val series = buildList {
        report.rawSeries.forEach { pass -> add(ChartSeries(pass.map { it.position to it.delta }, rawColor, line = false, alpha = 0.5f)) }
        add(ChartSeries(report.nodes.map { it.position.toDouble() to it.value.toDouble() }, fittedColor, line = true))
        (report.verification as? VerificationResult.Measured)?.let { measured ->
            add(ChartSeries(measured.residuals.map { it.position to it.delta }, verificationColor, line = false))
        }
    }
    MapChart(
        series = series,
        axisColor = MaterialTheme.colorScheme.outline,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        description = stringResource(Res.string.encoder_report_chart_description, report.nodes.size),
        modifier = Modifier.fillMaxWidth().height(260.dp).testTag(ReportTags.CHART),
    )
    Legend(rawColor, fittedColor, verificationColor, report.verification is VerificationResult.Measured)
    Spacer(Modifier.size(8.dp))
    ErrorMapRows.report(report).forEach { row -> ValueRow(row.label.resolve(), row.value.resolve()) }
    if (report.advice.isNotEmpty()) {
        SectionTitle(stringResource(Res.string.encoder_report_advice))
        report.advice.forEach { advice ->
            StatusLine(if (advice.blocksSave) StatusKind.Error else StatusKind.Warning, ErrorMapRows.advice(advice).resolve())
        }
    }
    if (!report.canSave) StatusLine(StatusKind.Info, stringResource(Res.string.encoder_report_save_blocked))
    state.saveProblems.forEach { StatusLine(StatusKind.Error, it.resolve()) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(raw: Color, fitted: Color, verification: Color, showVerification: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        LegendItem(raw.copy(alpha = 0.5f), stringResource(Res.string.encoder_report_raw_data))
        LegendItem(fitted, stringResource(Res.string.encoder_report_fitted))
        if (showVerification) LegendItem(verification, stringResource(Res.string.encoder_report_verification_series))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(12.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportButtons(state: ErrorMapReportUiState, onSave: () -> Unit, onDiagnostics: () -> Unit, onBack: () -> Unit) {
    val height = Modifier.heightIn(min = LocalTouchTargets.current.button)
    val report = state.report
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = report?.canSave == true && !state.saving, modifier = height.testTag(ReportTags.SAVE), colors = AccentButtons.filled()) {
            Text(stringResource(Res.string.encoder_report_save))
        }
        OutlinedButton(onClick = onDiagnostics, enabled = report != null, colors = AccentButtons.outlined(), modifier = height.testTag(ReportTags.DIAGNOSTICS)) {
            Text(stringResource(Res.string.encoder_report_diagnostics))
        }
        OutlinedButton(onClick = onBack, colors = AccentButtons.outlined(), modifier = height.testTag(ReportTags.BACK)) {
            Text(stringResource(Res.string.encoder_report_back))
        }
    }
}

/**
 * The diagnostics of the error map wizard:
 * one row for each value, and "Copy" that copies the table as text.
 */
@Composable
fun ErrorMapDiagnosticsScreen(state: ErrorMapDiagnosticsUiState, onCopied: () -> Unit, onBack: () -> Unit) {
    val inputName = AdapterTexts.encoderInputName(state.input).resolve()
    val rows = state.diagnostics?.let(ErrorMapRows::diagnostics).orEmpty().map { row -> row.label.resolve() to row.value.resolve() }
    // The common ClipEntry of Compose has no text constructor, so "Copy" uses the text API of
    // ClipboardManager.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(Res.string.encoder_diag_copy)
    val title = stringResource(Res.string.encoder_diag_title, inputName)
    ScreenScaffold(
        title = title,
        onBack = onBack,
        actions = {
            if (rows.isNotEmpty()) {
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(diagnosticsText(title, rows)))
                        onCopied()
                    },
                    modifier = Modifier.testTag(ReportTags.COPY),
                ) { Icon(painterResource(Res.drawable.ic_encoder_copy), contentDescription = copyLabel) }
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                when {
                    state.load == ReviewLoad.Loading -> StatusLine(StatusKind.Progress, stringResource(Res.string.encoder_loading))
                    state.load == ReviewLoad.NoSession -> StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_report_no_session))
                    rows.isEmpty() -> StatusLine(StatusKind.Warning, stringResource(Res.string.encoder_report_not_enough))
                    else -> rows.forEach { (label, value) -> ValueRow(label, value) }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

/** The text of the diagnostics for "Copy": the title, then one `label: value` line for each row. */
fun diagnosticsText(title: String, rows: List<Pair<String, String>>): String =
    buildString {
        append(title).append('\n')
        rows.forEach { (label, value) -> append(label).append(": ").append(value).append('\n') }
    }

/** The message after "Copy". */
val DIAGNOSTICS_COPIED = Res.string.encoder_diag_copied
