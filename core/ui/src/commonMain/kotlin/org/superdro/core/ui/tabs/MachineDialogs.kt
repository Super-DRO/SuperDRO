package org.superdro.core.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.resources.Res
import org.superdro.core.resources.add_device_error_host_blank
import org.superdro.core.resources.add_device_error_host_not_valid
import org.superdro.core.resources.add_device_error_port
import org.superdro.core.resources.add_device_wifi_host
import org.superdro.core.resources.add_device_wifi_port
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.frame_edit_link
import org.superdro.core.resources.frame_no_link
import org.superdro.core.resources.frame_rename_name
import org.superdro.core.resources.frame_save
import org.superdro.core.resources.route_edit_machine
import org.superdro.core.resources.route_rename_machine
import org.superdro.core.resources.service_battery_not_now
import org.superdro.core.resources.service_battery_continue
import org.superdro.core.resources.service_battery_reason
import org.superdro.core.resources.service_battery_title
import org.superdro.core.store.StoreResult
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.linkName
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.tabs.adddevice.HostCheck
import org.superdro.core.ui.tabs.remembered.addressText
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.resolve
import org.superdro.core.ui.text.toUiText
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.accentTextFieldColors

/**
 * The state of the rename dialog and the edit dialog of a machine.
 *
 * @property address The link of the profile, or null. Only a Wi-Fi link can change here.
 * @property done True after the save: the dialog closes.
 */
@Immutable
data class EditMachineUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val nameError: UiText? = null,
    val address: AdapterAddress? = null,
    val host: String = "",
    val port: String = "",
    val hostError: UiText? = null,
    val portError: UiText? = null,
    val busy: Boolean = false,
    val done: Boolean = false,
)

/**
 * The presenter of the rename dialog (the tab menu "Rename") and of the edit dialog (the pane
 * "Edit") of the machine [profileId]. The name must be unique. The edit dialog also changes
 * the host and the port of a Wi-Fi link. A running session gets the new link at once.
 */
class EditMachinePresenter(
    private val profileId: String,
    private val connections: Connections,
    private val machines: MachineRepository,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val state = MutableStateFlow(EditMachineUiState())

    /** The state of the dialog. */
    val uiState: StateFlow<EditMachineUiState> = state.asStateFlow()

    init {
        scope.launch {
            val profile = machines.loadProfile(profileId)
            val address = profile?.adapter
            state.update { old ->
                old.copy(
                    loaded = true,
                    name = profile?.name.orEmpty(),
                    address = address,
                    host = (address as? AdapterAddress.Tcp)?.host.orEmpty(),
                    port = (address as? AdapterAddress.Tcp)?.port?.toString().orEmpty(),
                )
            }
        }
    }

    fun setName(text: String) = state.update { old -> old.copy(name = text, nameError = null) }

    fun setHost(text: String) = state.update { old -> old.copy(host = text, hostError = null) }

    fun setPort(text: String) = state.update { old -> old.copy(port = text, portError = null) }

    /** Saves the name and, for a Wi-Fi link, the host and the port. */
    fun save() {
        val current = state.value
        if (current.busy || !current.loaded) return
        var newAddress: AdapterAddress? = current.address
        if (current.address is AdapterAddress.Tcp) {
            val hostError = when (HostCheck.check(current.host)) {
                HostCheck.Result.Valid -> null
                HostCheck.Result.Blank -> UiText.of(Res.string.add_device_error_host_blank)
                HostCheck.Result.NotValid -> UiText.of(Res.string.add_device_error_host_not_valid)
            }
            val port = HostCheck.port(current.port)
            if (hostError != null || port == null) {
                state.update { old -> old.copy(hostError = hostError, portError = if (port == null) UiText.of(Res.string.add_device_error_port) else null) }
                return
            }
            newAddress = AdapterAddress.Tcp(current.host.trim(), port)
        }
        state.update { old -> old.copy(busy = true) }
        scope.launch {
            val renamed = machines.renameProfile(profileId, current.name)
            if (renamed is StoreResult.Refused) {
                state.update { old -> old.copy(busy = false, nameError = renamed.refusal.toUiText()) }
                return@launch
            }
            var profile = (renamed as StoreResult.Ok).value
            if (newAddress != null && newAddress != profile.adapter) {
                val address = newAddress
                profile = (machines.updateProfile(profileId) { old -> old.copy(adapter = address) } as? StoreResult.Ok)?.value ?: profile
                val session = connections.sessions.value[DeviceId(profileId)]
                if (session != null && session.state.value != SessionState.Disconnected) {
                    connections.connect(DeviceRef(DeviceId(profileId), profile.name, address, profile.twoWayCommunication))
                }
            }
            state.update { old -> old.copy(busy = false, done = true) }
        }
    }
}

/**
 * The rename dialog ([editLink] false) or the edit dialog ([editLink] true) of a machine. The
 * edit dialog shows the link: a Wi-Fi link has fields for the host and the port, each other link
 * shows its address read only.
 */
@Composable
fun EditMachineDialog(state: EditMachineUiState, presenter: EditMachinePresenter, editLink: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editLink) Res.string.route_edit_machine else Res.string.route_rename_machine)) },
        text = {
            Column {
                OutlinedTextField(
                    colors = accentTextFieldColors(),
                    value = state.name,
                    onValueChange = presenter::setName,
                    label = { Text(stringResource(Res.string.frame_rename_name)) },
                    singleLine = true,
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { error -> { Text(error.resolve()) } },
                    modifier = Modifier.fillMaxWidth().testTag("edit-name"),
                )
                if (editLink) {
                    val address = state.address
                    Text(
                        text = stringResource(Res.string.frame_edit_link) + ": " +
                            (address?.let { stringResource(linkName(it.kind)) } ?: stringResource(Res.string.frame_no_link)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (address is AdapterAddress.Tcp) {
                        OutlinedTextField(
                            colors = accentTextFieldColors(),
                            value = state.host,
                            onValueChange = presenter::setHost,
                            label = { Text(stringResource(Res.string.add_device_wifi_host)) },
                            singleLine = true,
                            isError = state.hostError != null,
                            supportingText = state.hostError?.let { error -> { Text(error.resolve()) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().testTag("edit-host"),
                        )
                        OutlinedTextField(
                            colors = accentTextFieldColors(),
                            value = state.port,
                            onValueChange = presenter::setPort,
                            label = { Text(stringResource(Res.string.add_device_wifi_port)) },
                            singleLine = true,
                            isError = state.portError != null,
                            supportingText = state.portError?.let { error -> { Text(error.resolve()) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("edit-port"),
                        )
                    } else if (address != null) {
                        Text(addressText(address), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(colors = AccentButtons.text(), onClick = presenter::save, enabled = !state.busy && state.loaded, modifier = Modifier.testTag("edit-save")) {
                Text(stringResource(Res.string.frame_save))
            }
        },
        dismissButton = { TextButton(colors = AccentButtons.text(), onClick = onDismiss) { Text(stringResource(Res.string.frame_cancel)) } },
    )
}

/**
 * The dialog that tells why SuperDRO asks for the battery optimization exemption, before
 * the system request. The text is: "SuperDRO keeps the links to your machines open while the
 * screen is off." "Continue" sends the request; "Not now" closes the dialog. The buttons do not use
 * `primary` for the text.
 */
@Composable
fun BatteryOptimizationDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.service_battery_title)) },
        text = { Text(stringResource(Res.string.service_battery_reason)) },
        confirmButton = { TextButton(colors = AccentButtons.text(), onClick = onContinue) { Text(stringResource(Res.string.service_battery_continue)) } },
        dismissButton = { TextButton(colors = AccentButtons.text(), onClick = onDismiss) { Text(stringResource(Res.string.service_battery_not_now)) } },
    )
}
