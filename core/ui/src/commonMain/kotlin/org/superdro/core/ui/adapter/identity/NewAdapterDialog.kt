package org.superdro.core.ui.adapter.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.model.DeclaredInput
import org.superdro.core.resources.Res
import org.superdro.core.resources.adapter_import_model
import org.superdro.core.resources.adapter_input_probe
import org.superdro.core.resources.adapter_input_tach
import org.superdro.core.resources.adapter_input_w
import org.superdro.core.resources.adapter_input_x
import org.superdro.core.resources.adapter_input_y
import org.superdro.core.resources.adapter_input_z
import org.superdro.core.resources.adapter_prompt_create
import org.superdro.core.resources.adapter_prompt_differs
import org.superdro.core.resources.adapter_prompt_identity
import org.superdro.core.resources.adapter_prompt_inputs
import org.superdro.core.resources.adapter_prompt_keep
import org.superdro.core.resources.adapter_prompt_known
import org.superdro.core.resources.adapter_prompt_later
import org.superdro.core.resources.adapter_prompt_legacy
import org.superdro.core.resources.adapter_prompt_name
import org.superdro.core.resources.adapter_prompt_no_profile
import org.superdro.core.resources.adapter_prompt_text
import org.superdro.core.resources.adapter_prompt_title
import org.superdro.core.resources.adapter_prompt_use
import org.superdro.core.resources.frame_menu_disconnect
import org.superdro.core.resources.frame_save
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.common.TextValueField
import org.superdro.core.ui.navigation.NewAdapterPromptRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.accentRadioColors
import org.superdro.core.ui.theme.accentCheckboxColors

/** The test tags of the "new adapter" prompt. */
object NewAdapterTags {
    const val NAME: String = "new-adapter-name"
    const val CONFIRM: String = "new-adapter-confirm"
    const val DISCONNECT: String = "new-adapter-disconnect"
    const val CREATE: String = "new-adapter-create"
    fun use(profileId: String): String = "new-adapter-use-$profileId"
    fun declared(input: DeclaredInput): String = "new-adapter-input-${input.name}"
}

/** The events of the "new adapter" prompt. */
interface NewAdapterEvents {
    fun onName(text: String)
    fun onUse(profileId: String?)
    fun onToggleDeclared(input: DeclaredInput)
    fun onConfirm()
    fun onDisconnect()
    fun onDismiss()
}

/** The events of [presenter]. [close] removes the dialog entry. */
fun AdapterIdentityPresenter.events(close: () -> Unit): NewAdapterEvents = object : NewAdapterEvents {
    override fun onName(text: String) = this@events.onName(text)
    override fun onUse(profileId: String?) = this@events.onUse(profileId)
    override fun onToggleDeclared(input: DeclaredInput) = this@events.onToggleDeclared(input)
    override fun onConfirm() = this@events.onConfirm()
    override fun onDisconnect() {
        this@events.onDisconnect()
        close()
    }
    override fun onDismiss() {
        this@events.onDismiss()
        close()
    }
}

/**
 * The "new adapter" prompt: "It looks like you connected a new adapter", the reason, the stored
 * profiles with the identity of the link, the name of a new profile, and the six check boxes of
 * a legacy adapter.
 */
@Composable
fun NewAdapterDialog(state: NewAdapterPromptUi, events: NewAdapterEvents) {
    val createLabel = when {
        state.reason == PromptReason.Known -> stringResource(Res.string.adapter_prompt_keep, state.currentAlias.orEmpty())
        state.updateCurrent -> stringResource(Res.string.frame_save)
        else -> stringResource(Res.string.adapter_prompt_create)
    }
    AlertDialog(
        onDismissRequest = events::onDismiss,
        title = { Text(stringResource(Res.string.adapter_prompt_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.adapter_prompt_text), style = MaterialTheme.typography.bodyLarge)
                Text(reasonText(state), style = MaterialTheme.typography.bodyMedium)
                state.model?.let { model -> Text(stringResource(Res.string.adapter_import_model, model), style = MaterialTheme.typography.bodyMedium) }
                state.identity?.let { identity -> Text(stringResource(Res.string.adapter_prompt_identity, identity), style = MaterialTheme.typography.bodyMedium) }
                if (state.matches.isNotEmpty()) {
                    state.matches.forEach { (id, alias) ->
                        Choice(stringResource(Res.string.adapter_prompt_use, alias), selected = state.useProfileId == id, tag = NewAdapterTags.use(id)) { events.onUse(id) }
                    }
                    Choice(createLabel, selected = state.useProfileId == null, tag = NewAdapterTags.CREATE) { events.onUse(null) }
                }
                if (state.useProfileId == null && state.reason != PromptReason.Known) {
                    TextValueField(
                        label = stringResource(Res.string.adapter_prompt_name),
                        value = state.name,
                        onValueChange = events::onName,
                        error = state.nameError,
                        enabled = !state.busy,
                        tag = NewAdapterTags.NAME,
                    )
                    if (state.legacy) {
                        Text(stringResource(Res.string.adapter_prompt_inputs), style = MaterialTheme.typography.titleSmall)
                        DeclaredInput.entries.forEach { input ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .toggleable(value = input in state.declared, role = Role.Checkbox, onValueChange = { events.onToggleDeclared(input) })
                                    .testTag(NewAdapterTags.declared(input)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = input in state.declared, onCheckedChange = null, colors = accentCheckboxColors())
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(declaredName(input)), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                } else {
                    state.nameError?.let { error -> Text(error.resolve(), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = events::onConfirm, enabled = !state.busy, colors = AccentButtons.text(), modifier = Modifier.testTag(NewAdapterTags.CONFIRM)) {
                Text(if (state.useProfileId != null) stringResource(Res.string.adapter_prompt_use, state.matches.firstOrNull { it.first == state.useProfileId }?.second.orEmpty()) else createLabel)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = events::onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.adapter_prompt_later)) }
                TextButton(onClick = events::onDisconnect, colors = AccentButtons.text(), modifier = Modifier.testTag(NewAdapterTags.DISCONNECT)) {
                    Text(stringResource(Res.string.frame_menu_disconnect))
                }
            }
        },
    )
}

@Composable
private fun reasonText(state: NewAdapterPromptUi): String = when (state.reason) {
    PromptReason.NoProfile -> stringResource(Res.string.adapter_prompt_no_profile)
    PromptReason.Differs -> stringResource(Res.string.adapter_prompt_differs, state.currentAlias.orEmpty())
    PromptReason.Known -> stringResource(Res.string.adapter_prompt_known)
    PromptReason.Legacy -> stringResource(Res.string.adapter_prompt_legacy)
}

/** The name of a declared input: "Input X" to "Input W", "Probe", and "Tach". */
fun declaredName(input: DeclaredInput): StringResource = when (input) {
    DeclaredInput.X -> Res.string.adapter_input_x
    DeclaredInput.Y -> Res.string.adapter_input_y
    DeclaredInput.Z -> Res.string.adapter_input_z
    DeclaredInput.W -> Res.string.adapter_input_w
    DeclaredInput.Probe -> Res.string.adapter_input_probe
    DeclaredInput.Tach -> Res.string.adapter_input_tach
}

@Composable
private fun Choice(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = accentRadioColors())
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Gives the identity presenter of the tab of [scope]. */
fun identityPresenter(scope: TabEntryScope, deps: AdapterUiDeps): AdapterIdentityPresenter =
    scope.tabPresenter(AdapterIdentityPresenter.NAME) { AdapterIdentityPresenter(scope.deviceId, deps) }

/**
 * The tab effect of the identity rules: it keeps the identity presenter of the tab alive and
 * pushes the "new adapter" prompt on the back stack of the tab when the presenter asks for it.
 */
@Composable
fun AdapterIdentityEffect(scope: TabEntryScope) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = identityPresenter(scope, deps)
    val prompt by presenter.prompt.collectAsState()
    val id = prompt?.id
    LaunchedEffect(id) {
        if (id != null && presenter.needsPush(id)) {
            presenter.onShown(id)
            scope.navigator.push(NewAdapterPromptRoute)
        }
    }
}

/** The dialog entry of [NewAdapterPromptRoute]. A prompt that the presenter closed removes the entry. */
@Composable
fun NewAdapterEntry(scope: TabEntryScope, route: NewAdapterPromptRoute) {
    val deps = rememberInstance<AdapterUiDeps>()
    val presenter = identityPresenter(scope, deps)
    val prompt by presenter.prompt.collectAsState()
    val current = prompt
    if (current == null) {
        LaunchedEffect(Unit) { scope.navigator.pop(route) }
    } else {
        NewAdapterDialog(current, presenter.events { scope.navigator.pop(route) })
    }
}
