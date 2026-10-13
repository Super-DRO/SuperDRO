package org.superdro.core.ui.settings.logs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_check
import org.superdro.core.resources.ic_circle
import org.superdro.core.resources.ic_delete
import org.superdro.core.resources.ic_share
import org.superdro.core.resources.route_logs
import org.superdro.core.resources.settings_logs_clear
import org.superdro.core.resources.settings_logs_empty
import org.superdro.core.resources.settings_logs_export
import org.superdro.core.resources.settings_logs_export_record_description
import org.superdro.core.resources.settings_logs_filter_description
import org.superdro.core.resources.settings_logs_level_debug
import org.superdro.core.resources.settings_logs_level_error
import org.superdro.core.resources.settings_logs_level_info
import org.superdro.core.resources.settings_logs_level_warning
import org.superdro.core.resources.settings_logs_line_count
import org.superdro.core.resources.settings_logs_note
import org.superdro.core.resources.settings_logs_record_closed
import org.superdro.core.resources.settings_logs_record_detail
import org.superdro.core.resources.settings_logs_record_link
import org.superdro.core.resources.settings_logs_record_no_session
import org.superdro.core.resources.settings_logs_record_note
import org.superdro.core.resources.settings_logs_record_open
import org.superdro.core.resources.settings_logs_record_paused
import org.superdro.core.resources.settings_logs_record_title
import org.superdro.core.resources.settings_logs_record_truncated
import org.superdro.core.resources.settings_logs_records_empty
import org.superdro.core.resources.settings_logs_records_title
import org.superdro.core.resources.settings_logs_tab_lines
import org.superdro.core.resources.settings_logs_tab_record
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.settings.components.NoticeKind
import org.superdro.core.ui.settings.components.SETTINGS_MAX_WIDTH
import org.superdro.core.ui.settings.components.SettingsColumn
import org.superdro.core.ui.settings.components.SettingsNote
import org.superdro.core.ui.settings.components.SettingsNotice
import org.superdro.core.ui.settings.components.SettingsSection
import org.superdro.core.ui.settings.components.SettingsSwitch
import org.superdro.core.ui.settings.help.HelpButton
import org.superdro.core.ui.settings.help.HelpTopic
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentColor

/** The test tag of the list of the log lines. */
const val LOG_LINES_TAG: String = "log-lines"

/** The test tag of the "Export" button of the lines. */
const val LOG_EXPORT_TAG: String = "log-export"

/** The test tag of the filter chip of [filter]. */
fun logFilterTag(filter: LogFilter): String = when (filter) {
    LogFilter.All -> "log-filter:all"
    LogFilter.App -> "log-filter:app"
    is LogFilter.Machine -> "log-filter:${filter.device.value}"
}

/** The test tag of the record switch of [device]. */
fun recordSwitchTag(device: DeviceId): String = "record-switch:${device.value}"

/** The test tag of the export button of the record [id]. */
fun recordExportTag(id: Long): String = "record-export:$id"

/**
 * The log screen: the Arbor lines of the sessions and of the app with a filter
 * by machine and "Export", and the debug option "Record the byte stream" with the
 * export of each recorded link. The screen is stateless: [state] comes from [LogsPresenter].
 */
@Composable
fun LogsScreen(state: LogsUiState, events: LogsEvents, onBack: () -> Unit) {
    ScreenScaffold(
        title = stringResource(Res.string.route_logs),
        onBack = onBack,
        actions = { HelpButton(HelpTopic.Logs) },
    ) {
        Column(Modifier.fillMaxSize()) {
            val index = state.tab.ordinal
            SecondaryTabRow(
                selectedTabIndex = index,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(index, matchContentSize = false), color = accentColor())
                },
            ) {
                LogsTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { events.onSelectTab(tab) },
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag("logs-tab:${tab.name}"),
                        text = {
                            Text(stringResource(if (tab == LogsTab.Lines) Res.string.settings_logs_tab_lines else Res.string.settings_logs_tab_record))
                        },
                    )
                }
            }
            when (state.tab) {
                LogsTab.Lines -> LinesPart(state, events)
                LogsTab.Record -> RecordPart(state, events)
            }
        }
    }
}

@Composable
private fun LinesPart(state: LogsUiState, events: LogsEvents) {
    val target = LocalTouchTargets.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.filters.forEach { item ->
                val label = item.label.resolve()
                val description = stringResource(Res.string.settings_logs_filter_description, label)
                FilterChip(
                    selected = item.filter == state.filter,
                    onClick = { events.onSelectFilter(item.filter) },
                    label = { Text(label) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(logFilterTag(item.filter))
                        .semantics { contentDescription = description },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(target.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.settings_logs_line_count, state.lines.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = events::onClear,
                enabled = state.lines.isNotEmpty(),
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = target.button),
            ) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_logs_clear))
            }
            FilledTonalButton(
                onClick = events::onExport,
                enabled = !state.busy && state.lines.isNotEmpty(),
                modifier = Modifier.heightIn(min = target.button).testTag(LOG_EXPORT_TAG),
            ) {
                Icon(painterResource(Res.drawable.ic_share), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_logs_export))
            }
        }
        Text(
            stringResource(Res.string.settings_logs_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    stringResource(Res.string.settings_logs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag(LOG_LINES_TAG)) {
                items(state.lines, key = { line -> line.sequence }) { line -> LogLineRow(line) }
            }
        }
    }
}

/** One line: the level badge with its letter, the time, the machine, the tag, and the text. */
@Composable
private fun LogLineRow(line: LogLineUiState) {
    val levelName = stringResource(
        when (line.level) {
            LogLevel.Debug -> Res.string.settings_logs_level_debug
            LogLevel.Info -> Res.string.settings_logs_level_info
            LogLevel.Warning -> Res.string.settings_logs_level_warning
            LogLevel.Error -> Res.string.settings_logs_level_error
        },
    )
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (line.level) {
        LogLevel.Error -> scheme.error to scheme.onError
        LogLevel.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        LogLevel.Info -> scheme.surfaceContainerHighest to scheme.onSurface
        LogLevel.Debug -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Surface(
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.extraSmall,
            border = if (line.level == LogLevel.Debug || line.level == LogLevel.Info) BorderStroke(1.dp, scheme.outlineVariant) else null,
            modifier = Modifier.size(22.dp).semantics { contentDescription = levelName },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(line.level.letter.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(line.time, style = mono, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val source = listOfNotNull(line.machine, line.tag).joinToString(" · ")
            Text(
                "$source: ${line.text}",
                style = mono,
                color = scheme.onSurface,
            )
            if (line.error != null) Text(line.error, style = mono, color = scheme.error)
        }
    }
}

@Composable
private fun RecordPart(state: LogsUiState, events: LogsEvents) {
    SettingsColumn {
        SettingsSection(stringResource(Res.string.settings_logs_record_title)) {
            SettingsNote(stringResource(Res.string.settings_logs_record_note))
            if (state.sessions.isEmpty()) SettingsNote(stringResource(Res.string.settings_logs_record_no_session))
            state.sessions.forEach { session ->
                SettingsSwitch(
                    title = session.name,
                    checked = session.recording,
                    onCheckedChange = { on -> events.onRecordChange(session.deviceId, on) },
                    summary = if (session.available) null else stringResource(Res.string.settings_logs_record_paused),
                    enabled = session.available,
                    tag = recordSwitchTag(session.deviceId),
                )
            }
        }
        SettingsSection(stringResource(Res.string.settings_logs_records_title)) {
            if (state.records.isEmpty()) SettingsNote(stringResource(Res.string.settings_logs_records_empty))
            state.records.forEach { record -> RecordRow(record, state.maxBytesPerLink, events) }
        }
    }
}

/** The state of a record: an icon and a text. An open record uses the roles of a status that needs attention. */
@Composable
private fun RecordState(open: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (open) scheme.tertiaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (open) scheme.onTertiaryContainer else scheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (open) Res.drawable.ic_circle else Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (open) Res.string.settings_logs_record_open else Res.string.settings_logs_record_closed),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun RecordRow(record: RecordedLinkUiState, maxBytes: Long, events: LogsEvents) {
    val target = LocalTouchTargets.current
    val title = stringResource(Res.string.settings_logs_record_link, record.name, record.link)
    Column(Modifier.fillMaxWidth().widthIn(max = SETTINGS_MAX_WIDTH).padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = target.button), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(Res.string.settings_logs_record_detail, record.started, record.byteCount, record.chunkCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(target.gap))
            RecordState(record.open)
            Spacer(Modifier.width(target.gap))
            val description = stringResource(Res.string.settings_logs_export_record_description, title)
            OutlinedButton(
                onClick = { events.onExportRecord(record.id) },
                colors = AccentButtons.outlined(),
                modifier = Modifier.heightIn(min = target.button).testTag(recordExportTag(record.id)).semantics { contentDescription = description },
            ) {
                Icon(painterResource(Res.drawable.ic_share), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_logs_export))
            }
        }
        if (record.truncated) {
            SettingsNotice(stringResource(Res.string.settings_logs_record_truncated, maxBytes), kind = NoticeKind.Info)
        }
    }
}
