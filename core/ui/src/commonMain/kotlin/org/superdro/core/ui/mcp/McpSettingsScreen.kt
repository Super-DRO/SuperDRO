package org.superdro.core.ui.mcp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.mcp.McpRules
import org.superdro.core.mcp.McpServerState
import org.superdro.core.model.McpActivation
import org.superdro.core.model.McpConfirmation
import org.superdro.core.model.McpScope
import org.superdro.core.resources.Res
import org.superdro.core.resources.ic_content_copy
import org.superdro.core.resources.ic_pause
import org.superdro.core.resources.ic_play_arrow
import org.superdro.core.resources.ic_warning
import org.superdro.core.resources.mcp_activation
import org.superdro.core.resources.mcp_activation_always_on
import org.superdro.core.resources.mcp_activation_on_demand
import org.superdro.core.resources.mcp_addresses
import org.superdro.core.resources.mcp_clients
import org.superdro.core.resources.mcp_clients_label
import org.superdro.core.resources.mcp_command
import org.superdro.core.resources.mcp_command_copy
import org.superdro.core.resources.mcp_confirmation
import org.superdro.core.resources.mcp_confirmation_all
import org.superdro.core.resources.mcp_confirmation_off
import org.superdro.core.resources.mcp_confirmation_risky
import org.superdro.core.resources.mcp_confirmation_summary
import org.superdro.core.resources.mcp_diagnostics_duration
import org.superdro.core.resources.mcp_diagnostics_empty
import org.superdro.core.resources.mcp_diagnostics_hide
import org.superdro.core.resources.mcp_diagnostics_hint
import org.superdro.core.resources.mcp_diagnostics_show
import org.superdro.core.resources.mcp_enable
import org.superdro.core.resources.mcp_interface
import org.superdro.core.resources.mcp_interface_addresses
import org.superdro.core.resources.mcp_interface_no_address
import org.superdro.core.resources.mcp_no_address
import org.superdro.core.resources.mcp_port
import org.superdro.core.resources.mcp_qr_description
import org.superdro.core.resources.mcp_qr_too_long
import org.superdro.core.resources.mcp_retry
import org.superdro.core.resources.mcp_scope_control
import org.superdro.core.resources.mcp_scope_control_summary
import org.superdro.core.resources.mcp_scope_populate
import org.superdro.core.resources.mcp_scope_populate_summary
import org.superdro.core.resources.mcp_scope_read
import org.superdro.core.resources.mcp_scope_read_summary
import org.superdro.core.resources.mcp_section_access
import org.superdro.core.resources.mcp_section_diagnostics
import org.superdro.core.resources.mcp_section_network
import org.superdro.core.resources.mcp_section_status
import org.superdro.core.resources.mcp_section_token
import org.superdro.core.resources.mcp_start
import org.superdro.core.resources.mcp_status_line
import org.superdro.core.resources.mcp_stop
import org.superdro.core.resources.mcp_title
import org.superdro.core.resources.mcp_token_caution
import org.superdro.core.resources.mcp_token_copy
import org.superdro.core.resources.mcp_token_hide
import org.superdro.core.resources.mcp_token_hint
import org.superdro.core.resources.mcp_token_new
import org.superdro.core.resources.mcp_token_new_title
import org.superdro.core.resources.mcp_token_new_warning
import org.superdro.core.resources.mcp_token_shown
import org.superdro.core.resources.mcp_url
import org.superdro.core.resources.mcp_warning_cancel
import org.superdro.core.resources.mcp_warning_confirm
import org.superdro.core.resources.mcp_warning_confirmation_off
import org.superdro.core.resources.mcp_warning_confirmation_off_title
import org.superdro.core.resources.mcp_warning_control
import org.superdro.core.resources.mcp_warning_control_title
import org.superdro.core.resources.mcp_warning_plain_http
import org.superdro.core.resources.mcp_warning_plain_http_confirm
import org.superdro.core.resources.mcp_warning_plain_http_confirmed
import org.superdro.core.resources.mcp_warning_populate
import org.superdro.core.resources.mcp_warning_populate_title
import org.superdro.core.resources.mcp_warning_trusted
import org.superdro.core.ui.components.InfoPanel
import org.superdro.core.ui.components.OpenCloseIcon
import org.superdro.core.ui.components.openState
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentSwitchColors
import org.superdro.core.ui.theme.accentTextFieldColors

/** The test tag of the MCP settings screen. */
const val MCP_SCREEN_TAG: String = "mcp-screen"

/** The test tag of the switch of the server. */
const val MCP_SWITCH_TAG: String = "mcp-switch"

/** The test tag of the port field. */
const val MCP_PORT_TAG: String = "mcp-port"

/** The test tag of the token text. */
const val MCP_TOKEN_TAG: String = "mcp-token"

/** The test tag of the audit log in the diagnostics section (not in a dialog). */
const val MCP_AUDIT_TAG: String = "mcp-audit"

/** The test tag of the button that opens and closes the audit log. */
const val MCP_AUDIT_BUTTON_TAG: String = "mcp-audit-button"

/** The test tag of the warning dialog "Use only on trusted networks". */
const val MCP_WARNING_TAG: String = "mcp-warning"

/** The test tag of the confirm button of a warning dialog. */
const val MCP_WARNING_CONFIRM_TAG: String = "mcp-warning-confirm"

/** The test tag of the switch of [scope]. */
fun mcpScopeTag(scope: McpScope): String = "mcp-scope-${scope.name}"

/**
 * The screen `Settings > MCP server`. It is stateless: it gets the [state] of
 * [McpSettingsPresenter] and calls the [events]. The sections: Status, Access, Network, Token,
 * and Diagnostics.
 *
 * @param onCopy Copies a text (the token or the command) to the clipboard.
 * @param listState The scroll state of the list. A screenshot test gives a start position.
 */
@Composable
fun McpSettingsScreen(
    state: McpSettingsUiState,
    events: McpSettingsEvents,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    ScreenScaffold(title = stringResource(Res.string.mcp_title), onBack = onBack, modifier = modifier.testTag(MCP_SCREEN_TAG)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 920.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "status") { StatusCard(state, events) }
                item(key = "access") { AccessCard(state, events) }
                item(key = "network") { NetworkCard(state, events) }
                item(key = "token") { TokenCard(state, events, onCopy) }
                item(key = "diagnostics") { DiagnosticsCard(state, events) }
                if (state.auditOpen) {
                    if (state.audit.isEmpty()) {
                        item(key = "audit-empty") { AuditEmpty() }
                    } else {
                        items(state.audit, key = { line -> line.id }) { line -> AuditRow(line) }
                    }
                }
            }
        }
    }
    McpSettingsDialogs(state, events)
}

// -------------------------------------------------------------------------------------------
// Status

@Composable
private fun StatusCard(state: McpSettingsUiState, events: McpSettingsEvents) {
    val status = state.status
    Section(Res.string.mcp_section_status) {
        SwitchRow(
            text = stringResource(Res.string.mcp_enable),
            checked = state.config.enabled,
            onChange = events::onEnabledChange,
            modifier = Modifier.testTag(MCP_SWITCH_TAG),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            val (icon, tint) = mcpStateIcon(status.state)
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(Res.string.mcp_status_line, stringResource(McpTexts.status(status.state))),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        status.blockText?.let { text -> Hint(text.resolve()) }
        status.errorText?.let { text -> Text(text.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        (status.state as? McpServerState.Failed)?.let { failed ->
            Hint(stringResource(Res.string.mcp_retry, 5))
        }
        LabelValue(stringResource(Res.string.mcp_addresses), status.urls.joinToString(", ").ifEmpty { stringResource(Res.string.mcp_no_address) })
        LabelValue(stringResource(Res.string.mcp_clients_label), pluralStringResource(Res.plurals.mcp_clients, status.clients, status.clients))
        SubTitle(Res.string.mcp_activation)
        Choice(
            options = McpActivation.entries,
            selected = state.config.activation,
            text = { activation -> stringResource(if (activation == McpActivation.AlwaysOn) Res.string.mcp_activation_always_on else Res.string.mcp_activation_on_demand) },
            onSelect = events::onActivation,
        )
        if (status.canStartStop) {
            FilledTonalButton(onClick = events::onStartStop, modifier = Modifier.padding(top = 8.dp).heightIn(min = LocalTouchTargets.current.button)) {
                Icon(painterResource(if (status.running) Res.drawable.ic_pause else Res.drawable.ic_play_arrow), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (status.running) Res.string.mcp_stop else Res.string.mcp_start))
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Access

@Composable
private fun AccessCard(state: McpSettingsUiState, events: McpSettingsEvents) {
    Section(Res.string.mcp_section_access) {
        SwitchRow(
            text = stringResource(Res.string.mcp_scope_read),
            summary = stringResource(Res.string.mcp_scope_read_summary),
            checked = true,
            enabled = false,
            onChange = {},
            modifier = Modifier.testTag(mcpScopeTag(McpScope.Read)),
        )
        SwitchRow(
            text = stringResource(Res.string.mcp_scope_populate),
            summary = stringResource(Res.string.mcp_scope_populate_summary),
            checked = McpScope.Populate in state.config.scopes,
            onChange = { on -> events.onScope(McpScope.Populate, on) },
            modifier = Modifier.testTag(mcpScopeTag(McpScope.Populate)),
        )
        SwitchRow(
            text = stringResource(Res.string.mcp_scope_control),
            summary = stringResource(Res.string.mcp_scope_control_summary),
            checked = McpScope.Control in state.config.scopes,
            onChange = { on -> events.onScope(McpScope.Control, on) },
            modifier = Modifier.testTag(mcpScopeTag(McpScope.Control)),
        )
        if (state.config.scopes.any { scope -> scope != McpScope.Read }) {
            Warning(UiText.of(Res.string.mcp_warning_populate))
        }
        SubTitle(Res.string.mcp_confirmation)
        Hint(stringResource(Res.string.mcp_confirmation_summary))
        Choice(
            options = McpConfirmation.entries,
            selected = state.config.confirmation,
            text = { confirmation ->
                stringResource(
                    when (confirmation) {
                        McpConfirmation.Risky -> Res.string.mcp_confirmation_risky
                        McpConfirmation.All -> Res.string.mcp_confirmation_all
                        McpConfirmation.Off -> Res.string.mcp_confirmation_off
                    },
                )
            },
            onSelect = events::onConfirmation,
        )
    }
}

// -------------------------------------------------------------------------------------------
// Network

@Composable
private fun NetworkCard(state: McpSettingsUiState, events: McpSettingsEvents) {
    Section(Res.string.mcp_section_network) {
        SubTitle(Res.string.mcp_interface)
        state.interfaces.forEach { choice ->
            val name = stringResource(McpTexts.bindInterface(choice.bind))
            val text = if (choice.addresses.isEmpty()) {
                stringResource(Res.string.mcp_interface_no_address, name)
            } else {
                stringResource(Res.string.mcp_interface_addresses, name, choice.addresses.joinToString(", "))
            }
            RadioRow(text = text, selected = state.config.bindInterface == choice.bind, onClick = { events.onInterface(choice.bind) })
        }
        OutlinedTextField(
            colors = accentTextFieldColors(),
            value = state.portText,
            onValueChange = events::onPortText,
            label = { Text(stringResource(Res.string.mcp_port)) },
            singleLine = true,
            isError = state.portProblem != null,
            supportingText = state.portProblem?.let { text -> { Text(text.resolve()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(MCP_PORT_TAG),
        )
        if (McpRules.needsPlainHttpWarning(state.config)) {
            Warning(UiText.of(Res.string.mcp_warning_plain_http), title = UiText.of(Res.string.mcp_warning_trusted))
            OutlinedButton(colors = AccentButtons.outlined(), onClick = events::onConfirmPlainHttp, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.mcp_warning_plain_http_confirm))
            }
        } else if (state.config.plainHttpConfirmed) {
            Hint(stringResource(Res.string.mcp_warning_plain_http_confirmed))
        }
    }
}

// -------------------------------------------------------------------------------------------
// Token

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenCard(state: McpSettingsUiState, events: McpSettingsEvents, onCopy: (String) -> Unit) {
    Section(Res.string.mcp_section_token) {
        val token = state.token
        val command = state.command
        if (token == null) {
            Hint(stringResource(Res.string.mcp_token_hint))
        } else {
            Hint(stringResource(Res.string.mcp_token_shown))
            Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp).testTag(MCP_TOKEN_TAG))
            state.status.urls.firstOrNull()?.let { url -> LabelValue(stringResource(Res.string.mcp_url), url) }
            if (command != null) {
                SubTitle(Res.string.mcp_command)
                Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                val code = remember(command) { QrCode.encode(command) }
                if (code != null) {
                    QrCodeImage(code, modifier = Modifier.padding(vertical = 8.dp).size(240.dp))
                } else {
                    Hint(stringResource(Res.string.mcp_qr_too_long))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LocalTouchTargets.current.gap), modifier = Modifier.padding(top = 4.dp)) {
            if (token != null) {
                OutlinedButton(
                    colors = AccentButtons.outlined(),
                    onClick = {
                        onCopy(token)
                        events.onTokenCopied()
                    },
                    modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
                ) {
                    Icon(painterResource(Res.drawable.ic_content_copy), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.mcp_token_copy))
                }
                if (command != null) {
                    OutlinedButton(
                        colors = AccentButtons.outlined(),
                        onClick = {
                            onCopy(command)
                            events.onCommandCopied()
                        },
                        modifier = Modifier.heightIn(min = LocalTouchTargets.current.button),
                    ) {
                        Icon(painterResource(Res.drawable.ic_content_copy), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.mcp_command_copy))
                    }
                }
                OutlinedButton(colors = AccentButtons.outlined(), onClick = events::onHideToken, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                    Text(stringResource(Res.string.mcp_token_hide))
                }
            }
            OutlinedButton(colors = AccentButtons.outlined(), onClick = events::onNewToken, modifier = Modifier.heightIn(min = LocalTouchTargets.current.button)) {
                Text(stringResource(Res.string.mcp_token_new))
            }
        }
        Warning(UiText.of(Res.string.mcp_token_caution))
    }
}

/** The QR code [code] with a quiet zone of four modules, dark on white for each reader. */
@Composable
fun QrCodeImage(code: QrCode, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.mcp_qr_description)
    Canvas(modifier.semantics { contentDescription = description }) {
        val total = code.size + 8
        val module = minOf(size.width, size.height) / total
        drawRect(Color.White, size = Size(module * total, module * total))
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code[x, y]) drawRect(Color.Black, topLeft = Offset((x + 4) * module, (y + 4) * module), size = Size(module, module))
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Diagnostics (the dialog rule: the audit log shows in the screen)

@Composable
private fun DiagnosticsCard(state: McpSettingsUiState, events: McpSettingsEvents) {
    Section(Res.string.mcp_section_diagnostics) {
        Hint(stringResource(Res.string.mcp_diagnostics_hint))
        val open = state.auditOpen
        OutlinedButton(
            colors = AccentButtons.outlined(),
            onClick = if (open) events::onHideAudit else events::onShowAudit,
            modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).openState(open).testTag(MCP_AUDIT_BUTTON_TAG),
        ) {
            Text(stringResource(if (open) Res.string.mcp_diagnostics_hide else Res.string.mcp_diagnostics_show))
            Spacer(Modifier.width(8.dp))
            OpenCloseIcon(open)
        }
    }
}

@Composable
private fun AuditEmpty() {
    InfoPanel(tag = MCP_AUDIT_TAG) { Text(stringResource(Res.string.mcp_diagnostics_empty), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun AuditRow(line: McpAuditLine) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag(MCP_AUDIT_TAG)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${line.time}  ${line.client}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(line.name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(line.result, style = MaterialTheme.typography.labelLarge, color = if (line.result == "ok") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text(stringResource(Res.string.mcp_diagnostics_duration, line.durationMs.toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// The dialogs

/**
 * The dialogs of the screen: the warnings before a scope goes on, before the confirmation goes
 * off, and before the first start on a LAN interface, and the question before "New token".
 */
@Composable
internal fun McpSettingsDialogs(state: McpSettingsUiState, events: McpSettingsEvents) {
    when (state.dialog) {
        McpDialog.PlainHttpWarning -> WarningDialog(
            title = Res.string.mcp_warning_trusted,
            text = Res.string.mcp_warning_plain_http,
            confirm = Res.string.mcp_warning_plain_http_confirm,
            events = events,
        )
        McpDialog.PopulateWarning -> WarningDialog(Res.string.mcp_warning_populate_title, Res.string.mcp_warning_populate, Res.string.mcp_warning_confirm, events)
        McpDialog.ControlWarning -> WarningDialog(Res.string.mcp_warning_control_title, Res.string.mcp_warning_control, Res.string.mcp_warning_confirm, events)
        McpDialog.ConfirmationOffWarning ->
            WarningDialog(Res.string.mcp_warning_confirmation_off_title, Res.string.mcp_warning_confirmation_off, Res.string.mcp_warning_confirm, events)
        McpDialog.NewToken -> WarningDialog(Res.string.mcp_token_new_title, Res.string.mcp_token_new_warning, Res.string.mcp_token_new, events)
        null -> Unit
    }
}

/** WARNING: only the confirm button applies the change. A tap outside keeps the old setting. */
@Composable
private fun WarningDialog(title: StringResource, text: StringResource, confirm: StringResource, events: McpSettingsEvents) {
    AlertDialog(
        onDismissRequest = events::onDismissDialog,
        modifier = Modifier.testTag(MCP_WARNING_TAG),
        icon = { Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            TextButton(colors = AccentButtons.text(), onClick = events::onConfirmDialog, modifier = Modifier.testTag(MCP_WARNING_CONFIRM_TAG)) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(colors = AccentButtons.text(), onClick = events::onDismissDialog) { Text(stringResource(Res.string.mcp_warning_cancel)) }
        },
    )
}

// -------------------------------------------------------------------------------------------
// The parts

@Composable
private fun Section(title: StringResource, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
            content()
        }
    }
}

@Composable
private fun SubTitle(title: StringResource) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 120.dp).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** A warning text with an icon. */
@Composable
private fun Warning(text: UiText, title: UiText? = null) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                title?.let { value -> Text(value.resolve(), style = MaterialTheme.typography.titleSmall) }
                Text(text.resolve(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, summary: String? = null, enabled: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTouchTargets.current.button).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            summary?.let { line -> Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = accentSwitchColors())
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = accentRadioColors())
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, text: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        options.forEach { option -> RadioRow(text = text(option), selected = option == selected, onClick = { onSelect(option) }) }
    }
}
