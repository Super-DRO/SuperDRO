package org.superdro.core.ui.adapter.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_import_action_add
import org.superdro.core.resources.adapter_import_action_already_here
import org.superdro.core.resources.adapter_import_action_not_in_file
import org.superdro.core.resources.adapter_import_action_skip
import org.superdro.core.resources.adapter_import_action_unchanged
import org.superdro.core.resources.adapter_import_action_update
import org.superdro.core.resources.adapter_import_button
import org.superdro.core.resources.adapter_import_errors
import org.superdro.core.resources.adapter_import_none
import org.superdro.core.resources.adapter_import_operation
import org.superdro.core.resources.adapter_import_row_message
import org.superdro.core.resources.adapter_import_skipped
import org.superdro.core.resources.adapter_import_to_import
import org.superdro.core.resources.adapter_import_warnings
import org.superdro.core.resources.frame_cancel
import org.superdro.core.store.files.ImportMessage
import org.superdro.core.store.files.ImportProblem
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.MessageKey
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.ui.adaptive.AdaptiveSheet
import org.superdro.core.ui.components.InfoPanel
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors

/** The test tag of the import button of [ImportPreviewSheet]. */
const val IMPORT_CONFIRM_TAG: String = "import-confirm"

/**
 * One row of an import preview.
 *
 * @property label The name of the row, for example an input letter or a profile name.
 * @property action The text of the row action, for example "Will replace".
 * @property details The problems of the row.
 */
@Immutable
data class PreviewLineUi(val label: String, val action: UiText, val details: List<UiText> = emptyList())

/**
 * The import preview of the adapter area: the `.tdap` file and the `.tdprb` file.
 * The preview shows three lists: the rows to import, the rows that the import skips, and the
 * warnings. An error of the file stops the import.
 *
 * @property header The lines above the lists, for example the source and the adapter name.
 * @property operations The import operations that the user can select, or an empty list.
 */
@Immutable
data class ImportPreviewUi(
    val title: UiText,
    val header: List<UiText> = emptyList(),
    val toImport: List<PreviewLineUi> = emptyList(),
    val skipped: List<PreviewLineUi> = emptyList(),
    val warnings: List<UiText> = emptyList(),
    val errors: List<UiText> = emptyList(),
    val operations: List<UiText> = emptyList(),
    val selectedOperation: Int = 0,
    val canImport: Boolean = false,
)

/**
 * The report after an import: the title, the summary, and the sections with their items.
 */
@Immutable
data class ImportReportUi(val title: UiText, val summary: UiText, val sections: List<Pair<UiText, List<UiText>>>)

/** The text of a row action of the preview. */
fun RowAction.toUiText(): UiText = UiText.of(
    when (this) {
        RowAction.Add -> Res.string.adapter_import_action_add
        RowAction.Update -> Res.string.adapter_import_action_update
        RowAction.Unchanged -> Res.string.adapter_import_action_unchanged
        RowAction.AlreadyHere -> Res.string.adapter_import_action_already_here
        RowAction.NotInFile -> Res.string.adapter_import_action_not_in_file
        RowAction.Skip -> Res.string.adapter_import_action_skip
    },
)

/** The text of an import message of `core:store`. */
fun ImportMessage.toUiText(): UiText = key.toUiText(*args.toTypedArray())

/** The text of an import problem: "Row <n>: <message>", or the message alone. */
fun ImportProblem.toUiText(): UiText {
    val text = message.toUiText()
    val row = row ?: return text
    return UiText.of(Res.string.adapter_import_row_message, MessageKey.Row.toUiText(row), text)
}

/** The errors of a list of problems. */
fun List<ImportProblem>.errorTexts(): List<UiText> = filter { problem -> problem.severity == Severity.Error }.map { problem -> problem.toUiText() }

/** The report of an import as texts of the UI. An empty section does not show. */
fun ImportReport.toUi(): ImportReportUi {
    val sections = buildList {
        if (notImported.isNotEmpty()) add(MessageKey.ReportNotMapped.toUiText() to notImported.map { it.toUiText() })
        if (defaults.isNotEmpty()) add(MessageKey.ReportDefaults.toUiText() to defaults.map { it.toUiText() })
        if (probe.isNotEmpty()) add(MessageKey.ReportProbe.toUiText() to probe.map { it.toUiText() })
        if (warnings.isNotEmpty()) add(MessageKey.ReportWarnings.toUiText() to warnings.map { it.toUiText() })
    }
    return ImportReportUi(
        title = MessageKey.ReportTitle.toUiText(),
        summary = MessageKey.ReportSummary.toUiText(imported, notImported.size, defaults.size + warnings.size),
        sections = sections,
    )
}

/**
 * The import preview as a sheet. "Import" writes the rows. "Cancel" and the back gesture write
 * nothing.
 */
@Composable
fun ImportPreviewSheet(
    state: ImportPreviewUi,
    onSelectOperation: (Int) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onCancel, title = state.title.resolve()) {
        state.header.forEach { line -> Text(line.resolve(), style = MaterialTheme.typography.bodyMedium) }
        if (state.errors.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.adapter_import_errors))
            state.errors.forEach { error -> StatusLine(StatusKind.Error, error.resolve()) }
        }
        if (state.operations.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.adapter_import_operation))
            state.operations.forEachIndexed { index, operation ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = index == state.selectedOperation, role = Role.RadioButton, onClick = { onSelectOperation(index) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = index == state.selectedOperation, onClick = null, colors = accentRadioColors())
                    Spacer(Modifier.size(8.dp))
                    Text(operation.resolve(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        PreviewList(stringResource(Res.string.adapter_import_to_import), state.toImport)
        PreviewList(stringResource(Res.string.adapter_import_skipped), state.skipped)
        if (state.warnings.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.adapter_import_warnings))
            state.warnings.forEach { warning -> StatusLine(StatusKind.Warning, warning.resolve()) }
        }
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onCancel, colors = AccentButtons.text(), modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.frame_cancel))
            }
            Button(
                onClick = onImport,
                enabled = state.canImport,
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(IMPORT_CONFIRM_TAG),
                colors = AccentButtons.filled(),
            ) { Text(stringResource(Res.string.adapter_import_button)) }
        }
    }
}

@Composable
private fun PreviewList(title: String, lines: List<PreviewLineUi>) {
    SectionTitle(title)
    if (lines.isEmpty()) {
        Text(stringResource(Res.string.adapter_import_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    lines.forEach { line ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(line.action.resolve(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            line.details.forEach { detail -> StatusLine(StatusKind.Warning, detail.resolve()) }
        }
    }
}

/** The test tag of the report of an import in the screen. */
const val IMPORT_REPORT_TAG: String = "import-report"

/**
 * The report after an import in the screen that started the import: the title, the
 * summary, and the sections with their items. A report is general information, so it does not
 * show in a dialog. It stays until the screen closes or
 * a different action of the screen replaces it.
 */
@Composable
fun ImportReportCard(report: ImportReportUi, modifier: Modifier = Modifier) {
    InfoPanel(modifier = modifier, tag = IMPORT_REPORT_TAG) {
        Text(report.title.resolve(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(report.summary.resolve(), style = MaterialTheme.typography.bodyLarge)
        report.sections.forEach { (title, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.resolve(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
                items.forEach { item -> Text(item.resolve(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
