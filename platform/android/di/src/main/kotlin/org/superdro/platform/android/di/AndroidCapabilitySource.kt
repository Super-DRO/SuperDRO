package org.superdro.platform.android.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.connection.AttachedUsbDevice
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.LinkCapability

/**
 * The Android capability source. It combines three monitors of the platform modules into one
 * [CapabilityState]:
 *
 * - the Bluetooth radio and `BLUETOOTH_CONNECT` (`BluetoothCapabilityMonitor.capability`),
 * - the network and the local network permission (`NetworkCapabilityMonitor.capability`),
 * - the attached USB serial devices and their permission (`UsbDeviceMonitor.attached`).
 *
 * A session in `WaitingForDevice` collects [state] and starts again at once when its capability
 * comes back. The class takes the flows, not the monitors, so that a JVM unit test can examine it.
 */
class AndroidCapabilitySource(
    bluetooth: StateFlow<LinkCapability>,
    network: StateFlow<LinkCapability>,
    usb: StateFlow<List<AttachedUsbDevice>>,
    scope: CoroutineScope,
) : CapabilitySource {

    override val state: StateFlow<CapabilityState> =
        combine(bluetooth, network, usb, ::CapabilityState)
            .stateIn(scope, SharingStarted.Eagerly, CapabilityState(bluetooth.value, network.value, usb.value))
}
