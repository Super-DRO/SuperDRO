package org.superdro.core.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.camera.FakeUvcCameraBackend
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.KeepAliveSummary
import org.superdro.core.connection.NetworkInterfaceInfo
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.NoBatteryOptimization
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.TransportFactory
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.sound.NoUiSounds
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.tabs.DisconnectAllCommand
import org.superdro.core.ui.tabs.PauseAndDisconnectAll
import org.superdro.core.util.FilePicker
import org.superdro.core.util.InMemorySecretStore
import org.superdro.core.util.NoFilePicker
import org.superdro.core.util.SecretStore

/**
 * The platform bindings of the JVM tests: each value does nothing (see the table of
 * [coreDiModule]). A test imports it with [coreDiModule] and a `SqlDriver`. An app gives the real
 * platform module.
 */
val noOpPlatformModule = DI.Module("noOpPlatformModule") {
    bindSingleton<Map<TransportKind, TransportFactory>> { emptyMap() }
    bindSingleton<CapabilitySource> {
        object : CapabilitySource {
            override val state: StateFlow<CapabilityState> = MutableStateFlow(CapabilityState())
        }
    }
    bindSingleton<ConnectionKeepAlive> {
        object : ConnectionKeepAlive {
            override fun update(summary: KeepAliveSummary?) = Unit
        }
    }
    bindSingleton<UiSounds> { NoUiSounds }
    bindSingleton<FilePicker> { NoFilePicker }
    bindSingleton<SecretStore> { InMemorySecretStore() }
    bindSingleton<NetworkInterfaces> {
        object : NetworkInterfaces {
            override val interfaces: StateFlow<List<NetworkInterfaceInfo>> = MutableStateFlow(emptyList())
        }
    }
    bindSingleton<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER) { Dispatchers.IO }
    bindSingleton<StreamRecorder> { StreamRecorder.None }
    bindSingleton<UvcCameraBackend> { FakeUvcCameraBackend() }
    bindSingleton<List<AdapterDiscovery>> { emptyList() }
    bindSingleton<DebugAdapter> { NoDebugAdapter }
    bindSingleton<DisconnectAllCommand> { PauseAndDisconnectAll(instance(), instance()) }
    bindSingleton<BatteryOptimization> { NoBatteryOptimization }
}
