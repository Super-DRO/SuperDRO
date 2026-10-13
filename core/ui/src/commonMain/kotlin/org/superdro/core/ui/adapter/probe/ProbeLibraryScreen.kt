package org.superdro.core.ui.adapter.probe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_lock
import org.superdro.core.resources.ic_probelib_export
import org.superdro.core.resources.ic_probelib_import
import org.superdro.core.resources.probelib_delete_button
import org.superdro.core.resources.probelib_delete_description
import org.superdro.core.resources.probelib_delete_profile_text
import org.superdro.core.resources.probelib_delete_profile_title
import org.superdro.core.resources.probelib_delete_tip_text
import org.superdro.core.resources.probelib_delete_tip_title
import org.superdro.core.resources.probelib_diameter_value
import org.superdro.core.resources.probelib_empty
import org.superdro.core.resources.probelib_export
import org.superdro.core.resources.probelib_export_superdro
import org.superdro.core.resources.probelib_export_tdprb
import org.superdro.core.resources.probelib_functions
import org.superdro.core.resources.probelib_functions_hint
import org.superdro.core.resources.probelib_import
import org.superdro.core.resources.probelib_library_for
import org.superdro.core.resources.probelib_library_shared
import org.superdro.core.resources.probelib_new_profile
import org.superdro.core.resources.probelib_port_value
import org.superdro.core.resources.probelib_row_calibrated
import org.superdro.core.resources.probelib_row_shared
import org.superdro.core.resources.probelib_row_standard
import org.superdro.core.resources.probelib_standard_tip_text
import org.superdro.core.resources.probelib_standard_tip_title
import org.superdro.core.resources.probelib_profiles
import org.superdro.core.resources.route_probe_library
import org.superdro.core.ui.adapter.common.ImportPreviewSheet
import org.superdro.core.ui.adapter.common.ImportReportCard
import org.superdro.core.ui.adapter.common.SectionTitle
import org.superdro.core.ui.components.InfoPanel
import org.superdro.core.ui.components.openState
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The events of the probe library screen. */
interface ProbeLibraryEvents {
    fun onBack()
    fun onOpen(row: ProbeLibraryRow)
    fun onNew()
    fun onAskDelete(id: String)
    fun onCancelDelete()
    fun onConfirmDelete()
    fun onDismissNotice()
    fun onExport(form: ProbeExportForm)
    fun onImport()
    fun onSelectOperation(index: Int)
    fun onConfirmImport()
    fun onCancelImport()
    fun onDismissReport()
    fun onSelect(function: ProbeFunction, id: String?)
    fun onNewForFunction(function: ProbeFunction)
    fun onEditProfile(id: String)
}

/** The test tag of the row of the probe profile [id]. */
fun probeRowTag(id: String): String = "probe-row-$id"

/** The test tag of the delete button of the probe profile [id]. */
fun probeDeleteTag(id: String): String = "probe-delete-$id"

/** The test tag of the button "New probe profile". */
const val PROBE_NEW_TAG: String = "probe-new"

/** The test tag of the button that confirms a delete. */
const val PROBE_CONFIRM_DELETE_TAG: String = "probe-confirm-delete"

/** The test tag of the notice "a standard tip is read only" in the list (not in a dialog). */
const val PROBE_STANDARD_TIP_NOTICE_TAG: String = "probe-standard-tip-notice"

/**
 * The probe library screen: the probe selectors of the machine profile, the button "New probe
 * profile", the profiles, the export, and the import.
 *
 * General information shows in the list, not in a dialog (the dialog rule): the report after an
 * import is the first item, and the notice "a standard tip is read only" shows below the row of
 * the standard tip that the user tapped. A second tap on the row closes the notice.
 */
@Composable
fun ProbeLibraryScreen(state: ProbeLibraryUiState, selectors: List<ProbeSelectorUiState>, events: ProbeLibraryEvents) {
    var exportMenu by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = stringResource(Res.string.route_probe_library),
        onBack = events::onBack,
        actions = {
            IconButton(onClick = events::onImport, enabled = state.canTransfer, modifier = Modifier.testTag("probe-import")) {
                Icon(painterResource(Res.drawable.ic_probelib_import), contentDescription = stringResource(Res.string.probelib_import))
            }
            Box {
                IconButton(onClick = { exportMenu = true }, enabled = state.canTransfer, modifier = Modifier.testTag("probe-export")) {
                    Icon(painterResource(Res.drawable.ic_probelib_export), contentDescription = stringResource(Res.string.probelib_export))
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.probelib_export_superdro)) },
                        onClick = {
                            exportMenu = false
                            events.onExport(ProbeExportForm.SuperDro)
                        },
                        modifier = Modifier.testTag("probe-export-superdro"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.probelib_export_tdprb)) },
                        onClick = {
                            exportMenu = false
                            events.onExport(ProbeExportForm.Tdprb)
                        },
                        modifier = Modifier.testTag("probe-export-tdprb"),
                    )
                }
            }
        },
    ) {
        val target = LocalTouchTargets.current
        val list = rememberLazyListState()
        LaunchedEffect(state.report) { if (state.report != null) list.animateScrollToItem(0) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = list,
                modifier = Modifier.widthIn(max = 960.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                state.report?.let { report ->
                    item(key = "import-report") { ImportReportCard(report, Modifier.padding(bottom = 8.dp)) }
                }
                item {
                    Text(
                        text = state.machineName?.let { name -> stringResource(Res.string.probelib_library_for, name) }
                            ?: stringResource(Res.string.probelib_library_shared),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectors.isNotEmpty()) {
                    item { SectionTitle(stringResource(Res.string.probelib_functions)) }
                    item {
                        Text(
                            stringResource(Res.string.probelib_functions_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(selectors, key = { selector -> "selector-${selector.function.name}" }) { selector ->
                        ProbeSelector(
                            state = selector,
                            onSelect = { id -> events.onSelect(selector.function, id) },
                            onNewProfile = { events.onNewForFunction(selector.function) },
                            onEdit = events::onEditProfile,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                item { SectionTitle(stringResource(Res.string.probelib_profiles)) }
                item {
                    FilledTonalButton(
                        onClick = events::onNew,
                        modifier = Modifier.heightIn(min = target.button).testTag(PROBE_NEW_TAG),
                    ) {
                        Icon(painterResource(Res.drawable.ic_add), contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.probelib_new_profile))
                    }
                    Spacer(Modifier.size(8.dp))
                }
                if (state.loaded && state.rows.isEmpty()) {
                    item {
                        Text(
                            stringResource(Res.string.probelib_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                val noticeRow = state.standardTipNoticeId?.takeIf { id -> state.standardTipNotice && state.rows.any { row -> row.id == id } }
                if (state.standardTipNotice && noticeRow == null) {
                    item(key = "standard-tip-notice") { StandardTipNotice() }
                }
                items(state.rows, key = { row -> row.id }) { row ->
                    val noticeOpen = row.id == noticeRow
                    ProbeRow(row, events, noticeOpen)
                    if (noticeOpen) StandardTipNotice(Modifier.padding(bottom = 8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    state.confirmDelete?.let { row -> DeleteDialog(row, events) }
    state.importPreview?.let { preview ->
        ImportPreviewSheet(preview, events::onSelectOperation, events::onConfirmImport, events::onCancelImport)
    }
}

/** The notice "a standard tip is read only" below the row of a standard tip. */
@Composable
private fun StandardTipNotice(modifier: Modifier = Modifier) {
    InfoPanel(modifier = modifier, tag = PROBE_STANDARD_TIP_NOTICE_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(Res.string.probelib_standard_tip_title), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(Res.string.probelib_standard_tip_text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProbeRow(row: ProbeLibraryRow, events: ProbeLibraryEvents, noticeOpen: Boolean = false) {
    val details = buildList {
        add(stringResource(ProbeTypeRules.label(row.type)))
        row.diameterUm?.let { um ->
            add(stringResource(Res.string.probelib_diameter_value, ProbeLengths.text(um, row.metric), stringResource(ProbeLengths.unit(row.metric))))
        }
        row.port?.let { port -> add(stringResource(Res.string.probelib_port_value, port.uppercaseChar().toString())) }
        when {
            row.standardTip -> add(stringResource(Res.string.probelib_row_standard))
            row.shared -> add(stringResource(Res.string.probelib_row_shared))
        }
        if (row.calibrated) add(stringResource(Res.string.probelib_row_calibrated))
    }
    ListItem(
        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(details.joinToString(" · ")) },
        leadingContent = when {
            row.standardTip -> { { Icon(painterResource(Res.drawable.ic_lock), contentDescription = null) } }
            row.calibrated -> { { Icon(painterResource(Res.drawable.ic_check), contentDescription = null) } }
            else -> null
        },
        trailingContent = {
            IconButton(
                onClick = { events.onAskDelete(row.id) },
                modifier = Modifier.size(56.dp).testTag(probeDeleteTag(row.id)),
            ) { Icon(painterResource(Res.drawable.ic_delete), contentDescription = stringResource(Res.string.probelib_delete_description, row.name)) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { events.onOpen(row) }
            .then(if (row.standardTip) Modifier.openState(noticeOpen) else Modifier)
            .testTag(probeRowTag(row.id)),
    )
}

@Composable
private fun DeleteDialog(row: ProbeLibraryRow, events: ProbeLibraryEvents) {
    AlertDialog(
        onDismissRequest = events::onCancelDelete,
        icon = { Icon(painterResource(Res.drawable.ic_delete), contentDescription = null) },
        title = {
            Text(
                if (row.standardTip) stringResource(Res.string.probelib_delete_tip_title) else stringResource(Res.string.probelib_delete_profile_title),
            )
        },
        text = {
            Text(
                if (row.standardTip) {
                    stringResource(Res.string.probelib_delete_tip_text, row.name)
                } else {
                    stringResource(Res.string.probelib_delete_profile_text, row.name)
                },
            )
        },
        confirmButton = {
            TextButton(colors = AccentButtons.text(), onClick = events::onConfirmDelete, modifier = Modifier.testTag(PROBE_CONFIRM_DELETE_TAG)) {
                Text(stringResource(Res.string.probelib_delete_button))
            }
        },
        dismissButton = { TextButton(colors = AccentButtons.text(), onClick = events::onCancelDelete) { Text(stringResource(Res.string.frame_cancel)) } },
    )
}
