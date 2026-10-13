package org.superdro.app.adapterprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.adapterprobe.PrintLevel
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_console_hint
import org.superdro.core.resources.probe_console_open
import org.superdro.core.resources.probe_console_quit
import org.superdro.core.resources.probe_filter
import org.superdro.core.resources.probe_files
import org.superdro.core.resources.probe_link_needed
import org.superdro.core.resources.probe_report_empty
import org.superdro.core.resources.probe_report_refresh
import org.superdro.core.resources.probe_report_share
import org.superdro.core.resources.probe_send

/** The Console screen: the adapter commands and the live messages with the filters. */
@Composable
fun ConsoleScreen(presenter: ConsolePresenter) {
    val state by presenter.state.collectAsState()
    var line by remember { mutableStateOf("") }
    var noLink by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.open) {
                OutlinedButton(colors = ProbeButtons.outlined(), onClick = presenter::quit) { Text(stringResource(Res.string.probe_console_quit)) }
            } else {
                Button(colors = ProbeButtons.filled(), onClick = { noLink = !presenter.open() }) { Text(stringResource(Res.string.probe_console_open)) }
            }
            Text(stringResource(Res.string.probe_filter))
            listOf(PrintLevel.All, PrintLevel.Changes, PrintLevel.Events, PrintLevel.None).forEach { level ->
                FilterChip(selected = state.level == level, onClick = { presenter.setLevel(level) }, label = { Text(level.text) })
            }
        }
        if (noLink && !state.open) Text(stringResource(Res.string.probe_link_needed), color = MaterialTheme.colorScheme.error)
        state.logName?.let { Text(stringResource(Res.string.probe_files, it), style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line,
                onValueChange = { line = it },
                label = { Text(stringResource(Res.string.probe_console_hint)) },
                colors = probeTextFieldColors(), singleLine = true,
                enabled = state.open,
                // The commands are lower case ASCII: no capital letters and no correction of the keyboard.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    presenter.submit(line)
                    line = ""
                }),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            Button(colors = ProbeButtons.filled(), onClick = {
                presenter.submit(line)
                line = ""
            }, enabled = state.open && line.isNotBlank()) { Text(stringResource(Res.string.probe_send)) }
        }
        Card(Modifier.fillMaxSize()) { LiveLines(state.lines, Modifier.padding(12.dp)) }
    }
    state.prompt?.let { prompt -> PromptDialog(prompt) { answer -> presenter.answer(answer) } }
}

/** The Report screen: the report and the share button. */
@Composable
fun ReportScreen(presenter: ReportPresenter, actions: ActivityActions) {
    val state by presenter.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(colors = ProbeButtons.filled(), onClick = { actions.share(state.text) }) { Text(stringResource(Res.string.probe_report_share)) }
            OutlinedButton(colors = ProbeButtons.outlined(), onClick = presenter::refresh) { Text(stringResource(Res.string.probe_report_refresh)) }
        }
        if (state.runs == 0) Text(stringResource(Res.string.probe_report_empty))
        Card(Modifier.fillMaxSize()) {
            Text(
                state.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}
