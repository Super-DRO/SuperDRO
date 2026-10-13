package org.superdro.core.ui.functions.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_unit_inch
import org.superdro.core.resources.fn_unit_mm
import org.superdro.core.resources.ic_add
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_close
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_sync
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.import_button
import org.superdro.core.resources.import_name
import org.superdro.core.resources.import_operation
import org.superdro.core.resources.import_reading
import org.superdro.core.resources.import_tab_import
import org.superdro.core.resources.import_tab_skipped
import org.superdro.core.resources.import_tab_warnings
import org.superdro.core.resources.import_target
import org.superdro.core.resources.import_target_new
import org.superdro.core.resources.import_target_this
import org.superdro.core.resources.import_title
import org.superdro.core.resources.import_units
import org.superdro.core.resources.import_writing
import org.superdro.core.resources.import_nothing
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.RowAction
import org.superdro.core.ui.functions.common.ChoiceRow
import org.superdro.core.ui.functions.common.ErrorBlock
import org.superdro.core.ui.functions.common.ErrorBox
import org.superdro.core.ui.functions.common.FieldGroupTitle
import org.superdro.core.ui.functions.common.FunctionActions
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tags of the import preview screen. */
object ImportPreviewTags {
    const val NAME = "import-name"
    fun tab(index: Int): String = "import-tab-$index"
    fun operation(operation: ImportOperation): String = "import-op-${operation.name}"
    fun row(row: Int): String = "import-row-$row"
}

/** The events of the import preview screen. */
interface ImportPreviewEvents {
    fun onOperation(operation: ImportOperation)
    fun onUnits(metric: Boolean)
    fun onTarget(newProfile: Boolean)
    fun onName(name: String)
    fun onConfirm()
    fun onCancel()
}

/**
 * The import preview screen: the file, the problems of the full file, the choices (the operation,
 * the unit of a sub-datum file, the target and the name of a profile file), and the three lists:
 * the rows to import, the rows that the import skips, and the warnings. The import writes only after "Import".
 */
@Composable
fun ImportPreviewScreen(state: ImportUiState, events: ImportPreviewEvents, modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold(title = stringResource(Res.string.import_title), onBack = events::onCancel, modifier = modifier) {
        when (state.stage) {
            ImportStage.Reading, ImportStage.Idle, ImportStage.Closed -> Progress(stringResource(Res.string.import_reading))
            ImportStage.Preview, ImportStage.Writing -> Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { FileHeader(state) }
                        if (state.fileErrors.isNotEmpty()) item { ErrorBox(ErrorBlock(null, state.fileErrors)) }
                        item { Choices(state, events) }
                        item {
                            Tabs(
                                selected = tab,
                                counts = listOf(state.toImport.size, state.skipped.size, state.warnings.size),
                                onSelect = { tab = it },
                            )
                        }
                        when (tab) {
                            0 -> rows(state.toImport)
                            1 -> rows(state.skipped)
                            else -> if (state.warnings.isEmpty()) {
                                item { Empty() }
                            } else {
                                items(state.warnings) { warning -> ProblemLine(warning, Res.drawable.ic_warning) }
                            }
                        }
                    }
                    HorizontalDivider()
                    FunctionActions(
                        primaryLabel = stringResource(Res.string.import_button, state.importCount),
                        onPrimary = events::onConfirm,
                        onCancel = events::onCancel,
                        primaryEnabled = state.usable && state.importCount > 0 && state.stage == ImportStage.Preview &&
                            (state.kind != ImportKind.Profile || state.name.isNotBlank()),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                if (state.stage == ImportStage.Writing) {
                    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f), modifier = Modifier.fillMaxSize()) {
                        Progress(stringResource(Res.string.import_writing))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rows(rows: List<ImportRowUi>) {
    if (rows.isEmpty()) {
        item { Empty() }
    } else {
        items(rows, key = { "${it.row}-${it.label}" }) { row -> PreviewRowView(row) }
    }
}

@Composable
private fun Progress(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = accentColor())
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun FileHeader(state: ImportUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.kindText?.let { Text(it.resolve(), style = MaterialTheme.typography.titleMedium) }
        Text(state.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.provenance?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Choices(state: ImportUiState, events: ImportPreviewEvents) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.operations.size >= 2) {
            FieldGroupTitle(stringResource(Res.string.import_operation))
            state.operations.forEach { operation ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = operation == state.operation, role = Role.RadioButton) { events.onOperation(operation) }
                        .testTag(ImportPreviewTags.operation(operation)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = operation == state.operation, onClick = null, colors = accentRadioColors())
                    Text(stringResource(ImportUiTexts.operation(operation)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        val metric = state.metric
        if (state.kind == ImportKind.SubDatumCsv && metric != null) {
            FieldGroupTitle(stringResource(Res.string.import_units))
            ChoiceRow(
                options = listOf(true to stringResource(Res.string.fn_unit_mm), false to stringResource(Res.string.fn_unit_inch)),
                selected = metric,
                onSelect = events::onUnits,
            )
        }
        if (state.kind == ImportKind.Profile && state.fileErrors.isEmpty()) {
            FieldGroupTitle(stringResource(Res.string.import_target))
            ChoiceRow(
                options = listOf(true to stringResource(Res.string.import_target_new), false to stringResource(Res.string.import_target_this)),
                selected = state.newProfile,
                onSelect = events::onTarget,
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = events::onName,
                label = { Text(stringResource(Res.string.import_name)) },
                isError = state.nameError != null,
                supportingText = state.nameError?.let { error -> { Text(error.resolve()) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = accentTextFieldColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).testTag(ImportPreviewTags.NAME),
            )
        }
    }
}

@Composable
private fun Tabs(selected: Int, counts: List<Int>, onSelect: (Int) -> Unit) {
    val accent = accentColor()
    PrimaryTabRow(
        selectedTabIndex = selected,
        indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(selected), color = accent) },
    ) {
        listOf(Res.string.import_tab_import, Res.string.import_tab_skipped, Res.string.import_tab_warnings).forEachIndexed { index, text ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                selectedContentColor = accent,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(stringResource(text, counts[index])) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(ImportPreviewTags.tab(index)),
            )
        }
    }
}

@Composable
private fun Empty() {
    Text(
        stringResource(Res.string.import_nothing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun PreviewRowView(row: ImportRowUi) {
    Column(Modifier.fillMaxWidth().testTag(ImportPreviewTags.row(row.row)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${row.row}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ActionMark(row.action)
        }
        row.detail?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        row.problems.forEach { problem -> ProblemLine(problem, Res.drawable.ic_error) }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/** The action of a row: an icon and a text. */
@Composable
private fun ActionMark(action: RowAction) {
    val icon = when (action) {
        RowAction.Add -> Res.drawable.ic_add
        RowAction.Update -> Res.drawable.ic_sync
        RowAction.Unchanged, RowAction.AlreadyHere, RowAction.NotInFile -> Res.drawable.ic_check
        RowAction.Skip -> Res.drawable.ic_close
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(ImportUiTexts.action(action)), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ProblemLine(text: UiText, icon: DrawableResource) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Text(text.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
