package org.superdro.core.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.DI
import org.kodein.di.bindEagerSingleton
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSetup
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.ReconnectPolicy
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.WantedLink
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.withFakeTransport
import org.superdro.core.model.TransportKind
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.wantedMachines
import org.superdro.core.ui.settings.logs.RecordedStreams

/**
 * The bindings of `core:connection`: the request bus, the
 * connection manager with the stored setup of each device, and the keep-alive controller.
 *
 * `core:connection` does not depend on `core:store`. Thus this module binds [WantedLinkSource] to
 * `TabStore` and the machine profiles, and [DeviceSetupSource] to the repositories.
 */
internal val connectionModule = DI.Module("connectionModule") {
    bindSingleton { ConnectionRequestManager() }
    bindSingleton<WantedLinkSource> { tabStoreWantedLinks(tabs = instance(), machines = instance()) }
    bindSingleton<DeviceSetupSource> { repositorySetups(machines = instance(), adapters = instance()) }
    bindSingleton { ReconnectPolicy() }
    bindSingleton {
        val factories = instance<Map<TransportKind, TransportFactory>>()
        ConnectionManager(
            // A debug build adds the transport of the link type Fake.
            factories = instance<DebugAdapter>().withFakeTransport(factories),
            capabilities = instance<CapabilitySource>(),
            wantedLinks = instance(),
            clock = instance(),
            scope = ConnectionManager.defaultScope(),
            linkIoDispatcher = instance<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER),
            reconnectPolicy = instance(),
            deviceSetups = instance(),
            // The log screen keeps a copy of each recorded link (settingsLogsModule).
            streamRecorder = instance<RecordedStreams>(),
        )
    }
    // The controller starts when the graph is made: it collects the sessions while the process
    // lives, and starts or stops the connection service.
    bindEagerSingleton {
        KeepAliveController(manager = instance<ConnectionManager>(), keepAlive = instance<ConnectionKeepAlive>(), scope = instance(tag = APP_SCOPE))
    }
}

/**
 * The links that the user wants: each remembered machine that is not paused and that has an
 * adapter address, in the order of the tabs. The device ID is the ID of the machine
 * profile.
 */
fun tabStoreWantedLinks(tabs: TabStore, machines: MachineRepository): WantedLinkSource = object : WantedLinkSource {
    override val wantedLinks: Flow<List<WantedLink>> = wantedMachines(tabs, machines).map { list ->
        list.map { machine -> WantedLink(DeviceId(machine.profileId), machine.name, machine.address, machine.twoWayCommunication) }
    }
}

/**
 * The stored setup of each device: the settings of its machine profile and the adapter profile of
 * the tab. A change in a settings screen gives a new value at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun repositorySetups(machines: MachineRepository, adapters: AdapterRepository): DeviceSetupSource = DeviceSetupSource { id ->
    machines.profile(id.value).flatMapLatest { profile ->
        val adapterId = profile?.adapterProfileId
        when {
            profile == null -> flowOf(null)
            adapterId == null -> flowOf(DeviceSetup(profile))
            else -> adapters.adapterProfile(adapterId).map { adapter -> DeviceSetup(profile, adapter) }
        }
    }
}
