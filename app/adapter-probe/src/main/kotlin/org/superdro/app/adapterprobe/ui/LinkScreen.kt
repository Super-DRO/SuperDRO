package org.superdro.app.adapterprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.app.adapterprobe.LinkType
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.PairedListState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_baud_rate
import org.superdro.core.resources.probe_bt_off
import org.superdro.core.resources.probe_bt_permission_missing
import org.superdro.core.resources.probe_bt_request_permission
import org.superdro.core.resources.probe_bt_turn_on
import org.superdro.core.resources.probe_fake_scenario
import org.superdro.core.resources.probe_last_messages
import org.superdro.core.resources.probe_link_bluetooth
import org.superdro.core.resources.probe_link_fake
import org.superdro.core.resources.probe_link_none
import org.superdro.core.resources.probe_link_selected
import org.superdro.core.resources.probe_link_state
import org.superdro.core.resources.probe_link_tcp
import org.superdro.core.resources.probe_link_type
import org.superdro.core.resources.probe_link_usb
import org.superdro.core.resources.probe_no_paired
import org.superdro.core.resources.probe_no_usb
import org.superdro.core.resources.probe_open_bt_settings
import org.superdro.core.resources.probe_paired_devices
import org.superdro.core.resources.probe_permission_granted
import org.superdro.core.resources.probe_permission_not_granted
import org.superdro.core.resources.probe_permissions
import org.superdro.core.resources.probe_request_permissions
import org.superdro.core.resources.probe_rx_bytes
import org.superdro.core.resources.probe_state_closed
import org.superdro.core.resources.probe_state_connected
import org.superdro.core.resources.probe_state_open
import org.superdro.core.resources.probe_tcp_host
import org.superdro.core.resources.probe_tcp_invalid
import org.superdro.core.resources.probe_tcp_port
import org.superdro.core.resources.probe_usb_devices
import org.superdro.core.resources.probe_usb_permission
import org.superdro.core.resources.probe_valid_messages

private val TYPE_TITLES: Map<LinkType, StringResource> = mapOf(
    LinkType.Bluetooth to Res.string.probe_link_bluetooth,
    LinkType.Usb to Res.string.probe_link_usb,
    LinkType.Tcp to Res.string.probe_link_tcp,
    LinkType.Fake to Res.string.probe_link_fake,
)

/** The Link screen: the link type and its devices, and the state of the current link. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkScreen(presenter: LinkPresenter, actions: ActivityActions) {
    val state by presenter.state.collectAsState()
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.probe_link_type), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.types.forEach { type ->
                    FilterChip(
                        selected = state.choice.type == type,
                        onClick = { presenter.selectType(type) },
                        label = { Text(stringResource(TYPE_TITLES.getValue(type))) },
                    )
                }
            }
            when (state.choice.type) {
                LinkType.Bluetooth -> {
                    Text(stringResource(Res.string.probe_paired_devices), style = MaterialTheme.typography.titleSmall)
                    when (state.paired.state) {
                        PairedListState.PermissionMissing -> {
                            Text(stringResource(Res.string.probe_bt_permission_missing))
                            Button(colors = ProbeButtons.filled(), onClick = presenter::requestBluetoothPermission) {
                                Text(stringResource(Res.string.probe_bt_request_permission))
                            }
                        }
                        PairedListState.RadioOff -> {
                            Text(stringResource(Res.string.probe_bt_off))
                            Button(colors = ProbeButtons.filled(), onClick = actions.enableBluetooth) { Text(stringResource(Res.string.probe_bt_turn_on)) }
                        }
                        PairedListState.NoPaired, PairedListState.Paired -> {
                            if (state.paired.state == PairedListState.NoPaired) Text(stringResource(Res.string.probe_no_paired))
                            state.paired.devices.forEach { adapter ->
                                DeviceRow(adapter, (adapter.address as? AdapterAddress.BluetoothSpp)?.mac == state.choice.bluetooth) {
                                    presenter.selectBluetooth(adapter)
                                }
                            }
                            OutlinedButton(colors = ProbeButtons.outlined(), onClick = actions.openBluetoothSettings) { Text(stringResource(Res.string.probe_open_bt_settings)) }
                        }
                        null -> Unit
                    }
                }
                LinkType.Usb -> {
                    Text(stringResource(Res.string.probe_usb_devices), style = MaterialTheme.typography.titleSmall)
                    if (state.usb.isEmpty()) Text(stringResource(Res.string.probe_no_usb))
                    state.usb.forEach { adapter ->
                        val address = adapter.address as? AdapterAddress.UsbSerial
                        val chosen = state.choice.usb
                        DeviceRow(adapter, address != null && chosen != null && address.vendorId == chosen.vendorId && address.productId == chosen.productId && address.portIndex == chosen.portIndex) {
                            presenter.selectUsb(adapter)
                        }
                        if (adapter.needsPermission) {
                            OutlinedButton(colors = ProbeButtons.outlined(), onClick = { presenter.selectUsb(adapter) }) { Text(stringResource(Res.string.probe_usb_permission)) }
                        }
                    }
                    Text(stringResource(Res.string.probe_baud_rate), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.baudRates.forEach { baud ->
                            FilterChip(selected = state.choice.baudRate == baud, onClick = { presenter.selectBaud(baud) }, label = { Text(baud.toString()) })
                        }
                    }
                }
                LinkType.Tcp -> {
                    ProbeTextField(
                        value = state.choice.host,
                        onValueChange = presenter::setHost,
                        label = { Text(stringResource(Res.string.probe_tcp_host)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ProbeTextField(
                        value = state.choice.port,
                        onValueChange = presenter::setPort,
                        label = { Text(stringResource(Res.string.probe_tcp_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(200.dp),
                    )
                    if (!state.tcpValid) Text(stringResource(Res.string.probe_tcp_invalid), color = MaterialTheme.colorScheme.error)
                }
                LinkType.Fake -> {
                    Text(stringResource(Res.string.probe_fake_scenario), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.scenarios.forEach { name ->
                            FilterChip(selected = state.choice.fakeScenario == name, onClick = { presenter.selectScenario(name) }, label = { Text(name) })
                        }
                    }
                }
            }
            Text(
                state.selected?.let { stringResource(Res.string.probe_link_selected, it) } ?: stringResource(Res.string.probe_link_none),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(Res.string.probe_permissions), style = MaterialTheme.typography.titleSmall)
            state.permissions.forEach { row ->
                Text(
                    "${row.permission.substringAfterLast('.')}: " +
                        stringResource(if (row.granted) Res.string.probe_permission_granted else Res.string.probe_permission_not_granted),
                )
            }
            Button(colors = ProbeButtons.filled(), onClick = presenter::requestPermissions) { Text(stringResource(Res.string.probe_request_permissions)) }
        }
        Card(Modifier.weight(1f).fillMaxSize()) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.probe_link_state), style = MaterialTheme.typography.titleMedium)
                val info = state.info
                Text(
                    stringResource(
                        when {
                            info.connected -> Res.string.probe_state_connected
                            info.open -> Res.string.probe_state_open
                            else -> Res.string.probe_state_closed
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                info.closedReason?.takeIf { !info.open }?.let { Text(it) }
                Text(stringResource(Res.string.probe_rx_bytes, info.rxBytes.toString()))
                Text(stringResource(Res.string.probe_valid_messages, info.validMessages.toString()))
                Text(stringResource(Res.string.probe_last_messages), style = MaterialTheme.typography.titleSmall)
                info.lastMessages.forEach { text -> Text(text, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

@Composable
private fun DeviceRow(adapter: DiscoveredAdapter, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, colors = probeRadioColors())
        Column {
            Text(adapter.displayName, style = MaterialTheme.typography.bodyLarge)
            adapter.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
