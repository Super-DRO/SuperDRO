package org.superdro.app.adapterprobe.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.app.adapterprobe.LinkType
import org.superdro.core.adapterprobe.ProbeTestId
import org.superdro.core.adapterprobe.ProbeTexts
import org.superdro.core.adapterprobe.RunStatus
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_caution_one_link
import org.superdro.core.resources.probe_countdown
import org.superdro.core.resources.probe_files
import org.superdro.core.resources.probe_link_needed
import org.superdro.core.resources.probe_link_selected
import org.superdro.core.resources.probe_live_messages
import org.superdro.core.resources.probe_no
import org.superdro.core.resources.probe_option_axis
import org.superdro.core.resources.probe_option_bt_address
import org.superdro.core.resources.probe_option_duration
import org.superdro.core.resources.probe_option_keepalive
import org.superdro.core.resources.probe_option_label
import org.superdro.core.resources.probe_option_pair
import org.superdro.core.resources.probe_option_probe
import org.superdro.core.resources.probe_option_ref_runs
import org.superdro.core.resources.probe_options
import org.superdro.core.resources.probe_questions
import org.superdro.core.resources.probe_start
import org.superdro.core.resources.probe_status_done
import org.superdro.core.resources.probe_status_failed
import org.superdro.core.resources.probe_status_not_done
import org.superdro.core.resources.probe_status_running
import org.superdro.core.resources.probe_status_stopped
import org.superdro.core.resources.probe_steps
import org.superdro.core.resources.probe_stop
import org.superdro.core.resources.probe_summary
import org.superdro.core.resources.probe_tablet
import org.superdro.core.resources.probe_tt1_tcp_needed
import org.superdro.core.resources.probe_tt2_attaches
import org.superdro.core.resources.probe_tt2_dialog_question
import org.superdro.core.resources.probe_tt2_no_attach
import org.superdro.core.resources.probe_tt2_write
import org.superdro.core.resources.probe_yes

private fun statusText(status: RunStatus?): StringResource = when (status) {
    null, RunStatus.Idle -> Res.string.probe_status_not_done
    RunStatus.Running -> Res.string.probe_status_running
    RunStatus.Done -> Res.string.probe_status_done
    RunStatus.Stopped -> Res.string.probe_status_stopped
    RunStatus.Failed -> Res.string.probe_status_failed
}

/** The Tests screen: T01 to T16, TT1, and TT2 with the questions, the state, and the last result. */
@Composable
fun TestsScreen(presenter: TestsPresenter, onOpen: (ProbeTestId) -> Unit) {
    val rows by presenter.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(rows, key = { it.id.name }) { row ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(row.id) }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${row.id.name}  ${row.id.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(statusText(row.status)), style = MaterialTheme.typography.labelLarge)
                }
                Text(stringResource(Res.string.probe_questions, row.id.questions.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                row.result?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            HorizontalDivider()
        }
    }
}

/**
 * The Test screen: the step text, the countdown, the "Next" button, the cautions
 * with the typed confirmation, and the live messages. At the end, the summary lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestScreen(presenter: TestPresenter) {
    val state by presenter.state.collectAsState()
    val run = state.run
    val test = state.test
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${test.name}  ${test.title}", style = MaterialTheme.typography.headlineSmall)
            Text(test.purpose, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.probe_questions, test.questions.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            if (state.oneLinkCaution) Text(stringResource(Res.string.probe_caution_one_link), color = MaterialTheme.colorScheme.error)
            // The warnings and the cautions of the test, then its steps, before the start.
            ProbeTexts.testCautions(test).forEach { caution -> Text(caution, color = MaterialTheme.colorScheme.error) }
            if (state.planSteps.isNotEmpty()) PlanSteps(state.planSteps)
            when {
                test == ProbeTestId.TT2 -> Text(stringResource(Res.string.probe_tablet, state.tablet))
                test == ProbeTestId.TT1 && state.linkKind != LinkType.Tcp -> Text(stringResource(Res.string.probe_tt1_tcp_needed), color = MaterialTheme.colorScheme.error)
                state.link == null -> Text(stringResource(Res.string.probe_link_needed), color = MaterialTheme.colorScheme.error)
                else -> Text(stringResource(Res.string.probe_link_selected, state.link.orEmpty()))
            }
            if (test != ProbeTestId.TT2) TestOptionsPanel(test, state.options, presenter)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(colors = ProbeButtons.filled(), onClick = { presenter.start() }, enabled = !run.running) { Text(stringResource(if (test == ProbeTestId.TT2) Res.string.probe_tt2_write else Res.string.probe_start)) }
                OutlinedButton(colors = ProbeButtons.outlined(), onClick = presenter::stop, enabled = run.running) { Text(stringResource(Res.string.probe_stop)) }
                Text(stringResource(statusText(run.status.takeIf { run.test == test })), modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (test == ProbeTestId.TT2) Tt2Panel(state, presenter)
            run.step?.takeIf { run.test == test }?.let { step -> Text(step, style = MaterialTheme.typography.titleMedium) }
            Countdown(run.countdownEndNanos)
            if (run.test == test && (run.logName != null)) {
                Text(stringResource(Res.string.probe_files, listOfNotNull(run.logName, run.binName).joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
            if (run.test == test && run.summary.isNotEmpty()) {
                Text(stringResource(Res.string.probe_summary), style = MaterialTheme.typography.titleMedium)
                run.summary.forEach { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
        Card(Modifier.weight(1f).fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(Res.string.probe_live_messages), style = MaterialTheme.typography.titleMedium)
                LiveLines(state.liveLines)
            }
        }
    }
    run.prompt?.let { prompt -> PromptDialog(prompt) { answer -> presenter.answer(answer) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestOptionsPanel(test: ProbeTestId, options: OptionsUi, presenter: TestPresenter) {
    Text(stringResource(Res.string.probe_options), style = MaterialTheme.typography.titleSmall)
    if (test.needsAxis) {
        Text(stringResource(Res.string.probe_option_axis))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val axes = if (test == ProbeTestId.T11) "xyzw" else ProbeTexts.AXES
            axes.forEach { axis ->
                FilterChip(selected = options.axis == axis, onClick = { presenter.setOptions { it.copy(axis = axis) } }, label = { Text(axis.toString()) })
            }
        }
    }
    if (test == ProbeTestId.T04) {
        ProbeTextField(options.pair, { text -> presenter.setOptions { it.copy(pair = text) } }, label = { Text(stringResource(Res.string.probe_option_pair)) })
    }
    if (test == ProbeTestId.T09) {
        Text(stringResource(Res.string.probe_option_probe))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("P", "Q").forEach { prefix ->
                FilterChip(selected = options.probePrefix == prefix, onClick = { presenter.setOptions { it.copy(probePrefix = prefix) } }, label = { Text(prefix) })
            }
        }
    }
    if (test == ProbeTestId.T11) {
        Text(stringResource(Res.string.probe_option_ref_runs))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2).forEach { runs ->
                FilterChip(selected = options.refRuns == runs, onClick = { presenter.setOptions { it.copy(refRuns = runs) } }, label = { Text(runs.toString()) })
            }
        }
    }
    if (test == ProbeTestId.T02) {
        ProbeTextField(options.btAddress, { text -> presenter.setOptions { it.copy(btAddress = text) } }, label = { Text(stringResource(Res.string.probe_option_bt_address)) }, modifier = Modifier.fillMaxWidth())
    }
    if (test in OptionsUi.DURATION_OPTION) {
        ProbeTextField(
            options.duration,
            { text -> presenter.setOptions { it.copy(duration = text) } },
            label = { Text(stringResource(Res.string.probe_option_duration)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
    ProbeTextField(options.label, { text -> presenter.setOptions { it.copy(label = text) } }, label = { Text(stringResource(Res.string.probe_option_label)) })
    if (test.startKeepAlive == null || test == ProbeTestId.T01) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = options.keepAlive, onCheckedChange = { on -> presenter.setOptions { it.copy(keepAlive = on) } })
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.probe_option_keepalive))
        }
    }
}


@Composable
private fun Tt2Panel(state: TestUiState, presenter: TestPresenter) {
    LaunchedEffect(Unit) {
        while (true) {
            presenter.reloadAttaches()
            delay(2_000)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(Res.string.probe_tt2_attaches), style = MaterialTheme.typography.titleSmall)
    if (state.attaches.isEmpty()) Text(stringResource(Res.string.probe_tt2_no_attach))
    state.attaches.forEachIndexed { index, attach ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "${index + 1}: ${ProbeTexts.utcMinute(attach.epochMs)} UTC, 0x%04x:0x%04x %s, hasPermission() %s".format(
                    attach.vendorId, attach.productId, attach.name.orEmpty(), attach.hasPermission,
                ),
            )
            Text(stringResource(Res.string.probe_tt2_dialog_question))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = attach.dialogShown == true, onClick = { presenter.answerAttach(index, true) }, label = { Text(stringResource(Res.string.probe_yes)) })
                FilterChip(selected = attach.dialogShown == false, onClick = { presenter.answerAttach(index, false) }, label = { Text(stringResource(Res.string.probe_no)) })
            }
        }
    }
}

/**
 * The steps of a test ([ProbeTexts.testSteps]). Each step has its number. A caution is in the error color, and a
 * sub-step has an indent.
 */
@Composable
private fun PlanSteps(steps: List<String>) {
    Text(stringResource(Res.string.probe_steps), style = MaterialTheme.typography.titleSmall)
    steps.forEach { step ->
        when {
            step.startsWith("CAUTION:") || step.startsWith("WARNING:") -> Text(step, color = MaterialTheme.colorScheme.error)
            SUB_STEP.containsMatchIn(step) -> Text(step, modifier = Modifier.padding(start = 24.dp))
            else -> Text(step)
        }
    }
}

private val SUB_STEP = Regex("^\\d+\\.\\d+\\. ")

/** The countdown of a timed step. */
@Composable
fun Countdown(endNanos: Long?) {
    if (endNanos == null) return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(endNanos) {
        while (true) {
            now = SystemClock.elapsedRealtimeNanos()
            delay(200)
        }
    }
    val left = ((endNanos - now).coerceAtLeast(0L) + 999_999_999L) / 1_000_000_000L
    Text(stringResource(Res.string.probe_countdown, left.toString()), style = MaterialTheme.typography.headlineMedium)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/** The live message lines, with the last line at the bottom. */
@Composable
fun LiveLines(lines: List<String>, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(lines) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    LazyColumn(modifier.fillMaxSize(), state = list) {
        itemsIndexed(lines) { _, line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
