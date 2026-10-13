package org.superdro.app.adapterprobe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.adapterprobe.PromptKind
import org.superdro.core.adapterprobe.PromptRequest
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_cancel
import org.superdro.core.resources.probe_current_mode
import org.superdro.core.resources.probe_debug_fake
import org.superdro.core.resources.probe_debug_menu
import org.superdro.core.resources.probe_debug_tcp_server
import org.superdro.core.resources.probe_next
import org.superdro.core.resources.probe_no
import org.superdro.core.resources.probe_number_hint
import org.superdro.core.resources.probe_send
import org.superdro.core.resources.probe_skip
import org.superdro.core.resources.probe_tab_console
import org.superdro.core.resources.probe_tab_link
import org.superdro.core.resources.probe_tab_report
import org.superdro.core.resources.probe_tab_test
import org.superdro.core.resources.probe_tab_tests
import org.superdro.core.resources.probe_title
import org.superdro.core.resources.probe_type_yes
import org.superdro.core.resources.probe_yes

/** The presenters of the screens. */
class Presenters(
    val app: AppPresenter,
    val link: LinkPresenter,
    val tests: TestsPresenter,
    val test: TestPresenter,
    val console: ConsolePresenter,
    val report: ReportPresenter,
)

/** The actions of the activity: the share sheet. */
class ActivityActions(val share: (String) -> Unit, val openBluetoothSettings: () -> Unit)

private val TAB_TITLES: Map<Screen, StringResource> = mapOf(
    Screen.Link to Res.string.probe_tab_link,
    Screen.Tests to Res.string.probe_tab_tests,
    Screen.Test to Res.string.probe_tab_test,
    Screen.Console to Res.string.probe_tab_console,
    Screen.Report to Res.string.probe_tab_report,
)

/** The frame of the probe app: the title, the tabs of the screens, and the debug menu. */
@Composable
fun ProbeApp(presenters: Presenters, actions: ActivityActions) {
    val frame by presenters.app.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.probe_title)) },
                actions = {
                    if (frame.debugMenu) {
                        TextButton(colors = ProbeButtons.text(), onClick = { menu = true }) { Text(stringResource(Res.string.probe_debug_menu)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.probe_debug_fake)) },
                                onClick = {
                                    menu = false
                                    presenters.link.connectFake()
                                    presenters.app.show(Screen.Link)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.probe_debug_tcp_server)) },
                                onClick = {
                                    menu = false
                                    presenters.link.connectFakeTcp()
                                    presenters.app.show(Screen.Link)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // In light mode `primary` is not a text color: the
            // tab texts use onSurface and the indicator uses the accent line.
            PrimaryTabRow(
                selectedTabIndex = frame.screen.ordinal,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(frame.screen.ordinal, matchContentSize = true),
                        width = Dp.Unspecified,
                        color = probeAccentLine(),
                    )
                },
            ) {
                Screen.entries.forEach { screen ->
                    Tab(
                        selected = frame.screen == screen,
                        onClick = { presenters.app.show(screen) },
                        selectedContentColor = MaterialTheme.colorScheme.onSurface,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(TAB_TITLES.getValue(screen))) },
                    )
                }
            }
            when (frame.screen) {
                Screen.Link -> LinkScreen(presenters.link, actions)
                Screen.Tests -> TestsScreen(presenters.tests) { test -> presenters.app.openTest(test) }
                Screen.Test -> TestScreen(presenters.test)
                Screen.Console -> ConsoleScreen(presenters.console)
                Screen.Report -> ReportScreen(presenters.report, actions)
            }
        }
    }
}

/**
 * The dialog of a prompt: "Next" (and "Skip"), "Yes" and "No", a number, or the
 * caution with the field for the typed `yes`.
 */
@Composable
fun PromptDialog(prompt: PromptRequest, onAnswer: (String) -> Unit) {
    var text by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(prompt.command ?: stringResource(Res.string.probe_title)) },
        text = {
            Column {
                prompt.caution?.let { caution ->
                    Text(caution, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                prompt.currentMode?.let { mode ->
                    Text(stringResource(Res.string.probe_current_mode, mode), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                }
                Text(prompt.text.trim(), style = MaterialTheme.typography.bodyLarge)
                when (prompt.kind) {
                    PromptKind.TypedYes -> {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(Res.string.probe_type_yes), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = text, onValueChange = { text = it }, colors = probeTextFieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    PromptKind.Number -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        colors = probeTextFieldColors(), singleLine = true,
                        label = { Text(stringResource(Res.string.probe_number_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (prompt.kind) {
                PromptKind.Next -> TextButton(colors = ProbeButtons.text(), onClick = { onAnswer("") }) { Text(stringResource(Res.string.probe_next)) }
                PromptKind.NextOrSkip -> Row {
                    TextButton(colors = ProbeButtons.text(), onClick = { onAnswer("skip") }) { Text(stringResource(Res.string.probe_skip)) }
                    TextButton(colors = ProbeButtons.text(), onClick = { onAnswer("") }) { Text(stringResource(Res.string.probe_next)) }
                }
                PromptKind.YesNo -> Row {
                    TextButton(colors = ProbeButtons.text(), onClick = { onAnswer("no") }) { Text(stringResource(Res.string.probe_no)) }
                    TextButton(colors = ProbeButtons.text(), onClick = { onAnswer("yes") }) { Text(stringResource(Res.string.probe_yes)) }
                }
                PromptKind.Number -> TextButton(colors = ProbeButtons.text(), onClick = { onAnswer(text) }) { Text(stringResource(Res.string.probe_next)) }
                PromptKind.TypedYes -> Row {
                    TextButton(colors = ProbeButtons.text(), onClick = { onAnswer("") }) { Text(stringResource(Res.string.probe_cancel)) }
                    TextButton(colors = ProbeButtons.text(), onClick = { onAnswer(text) }) { Text(stringResource(Res.string.probe_send)) }
                }
            }
        },
    )
}
