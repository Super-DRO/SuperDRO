package org.superdro.core.ui.adapter.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_action_copy
import org.superdro.core.resources.adapter_action_export
import org.superdro.core.resources.adapter_action_forget
import org.superdro.core.resources.adapter_action_import
import org.superdro.core.resources.adapter_export_superdro
import org.superdro.core.resources.adapter_export_tdap
import org.superdro.core.resources.adapter_export_title
import org.superdro.core.resources.adapter_forget_confirm
import org.superdro.core.resources.adapter_forget_text
import org.superdro.core.resources.adapter_forget_title
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.ic_adapter_copy
import org.superdro.core.resources.ic_adapter_export
import org.superdro.core.resources.ic_adapter_import
import org.superdro.core.resources.ic_delete
import org.superdro.core.ui.adapter.common.ImportPreviewSheet
import org.superdro.core.ui.adapter.common.ImportReportCard
import org.superdro.core.ui.theme.AccentButtons

/** The test tags of the profile actions. */
object ProfileActionTags {
    const val COPY: String = "profile-copy"
    const val EXPORT: String = "profile-export"
    const val IMPORT: String = "profile-import"
    const val FORGET: String = "profile-forget"
    const val EXPORT_SUPERDRO: String = "profile-export-superdro"
    const val EXPORT_TDAP: String = "profile-export-tdap"
    const val FORGET_CONFIRM: String = "profile-forget-confirm"
}

/**
 * The buttons of the profile actions in a top app bar: copy, export, import, and forget.
 * Each icon button has a content description.
 */
@Composable
fun ProfileActionButtons(actions: ProfileActionEvents, enabled: Boolean) {
    IconButton(onClick = actions::copy, enabled = enabled, modifier = Modifier.testTag(ProfileActionTags.COPY)) {
        Icon(painterResource(Res.drawable.ic_adapter_copy), contentDescription = stringResource(Res.string.adapter_action_copy))
    }
    IconButton(onClick = actions::askExport, enabled = enabled, modifier = Modifier.testTag(ProfileActionTags.EXPORT)) {
        Icon(painterResource(Res.drawable.ic_adapter_export), contentDescription = stringResource(Res.string.adapter_action_export))
    }
    IconButton(onClick = actions::importIntoProfile, enabled = enabled, modifier = Modifier.testTag(ProfileActionTags.IMPORT)) {
        Icon(painterResource(Res.drawable.ic_adapter_import), contentDescription = stringResource(Res.string.adapter_action_import))
    }
    IconButton(onClick = actions::askForget, enabled = enabled, modifier = Modifier.testTag(ProfileActionTags.FORGET)) {
        Icon(painterResource(Res.drawable.ic_delete), contentDescription = stringResource(Res.string.adapter_action_forget))
    }
}

/**
 * The dialogs of the profile actions: the export choice, the import preview, and the forget
 * question. The report after an import shows in the screen ([profileActionsReport]).
 */
@Composable
fun ProfileActionsHost(state: ProfileActionsUiState, actions: ProfileActionEvents) {
    when (val dialog = state.dialog) {
        null -> Unit
        ProfileDialog.ExportChoice -> AlertDialog(
            onDismissRequest = actions::dismiss,
            title = { Text(stringResource(Res.string.adapter_export_title)) },
            text = {
                Column {
                    ExportOption(stringResource(Res.string.adapter_export_superdro), ProfileActionTags.EXPORT_SUPERDRO) {
                        actions.export(AdapterExportKind.SuperDro)
                    }
                    ExportOption(stringResource(Res.string.adapter_export_tdap), ProfileActionTags.EXPORT_TDAP) {
                        actions.export(AdapterExportKind.Tdap)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = actions::dismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) } },
        )
        is ProfileDialog.Preview -> ImportPreviewSheet(
            state = dialog.preview,
            onSelectOperation = {},
            onImport = actions::confirmImport,
            onCancel = actions::dismiss,
        )
        is ProfileDialog.Report -> Unit
        is ProfileDialog.ConfirmForget -> AlertDialog(
            onDismissRequest = actions::dismiss,
            title = { Text(stringResource(Res.string.adapter_forget_title, dialog.alias)) },
            text = { Text(stringResource(Res.string.adapter_forget_text)) },
            confirmButton = {
                TextButton(
                    onClick = actions::confirmForget,
                    colors = AccentButtons.text(),
                    modifier = Modifier.testTag(ProfileActionTags.FORGET_CONFIRM),
                ) { Text(stringResource(Res.string.adapter_forget_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = actions::dismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) } },
        )
    }
}

/** The key of the grid item of the report after an import. */
private const val REPORT_ITEM_KEY: String = "profile-import-report"

/**
 * The report after an import as the first item of the grid of the screen, over the full
 * width (the report shows in the screen, not in a dialog).
 */
fun LazyGridScope.profileActionsReport(state: ProfileActionsUiState) {
    val report = (state.dialog as? ProfileDialog.Report)?.report ?: return
    item(key = REPORT_ITEM_KEY, span = { GridItemSpan(maxLineSpan) }) { ImportReportCard(report) }
}

/** The state of the grid of a screen with the profile actions. A new report scrolls the grid to it. */
@Composable
fun rememberProfileActionsGridState(state: ProfileActionsUiState): LazyGridState {
    val grid = rememberLazyGridState()
    val report = (state.dialog as? ProfileDialog.Report)?.report
    LaunchedEffect(report) { if (report != null) grid.animateScrollToItem(0) }
    return grid
}

@Composable
private fun ExportOption(text: String, tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = AccentButtons.text(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
    ) { Text(text, modifier = Modifier.fillMaxWidth()) }
}
