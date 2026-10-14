package org.superdro.core.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DI
import org.kodein.di.bindFactory
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.appEntryRegistry
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.session.ManagerConnections
import org.superdro.core.ui.navigation.TabReadoutPresenter
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.tabs.AppRequestSender
import org.superdro.core.ui.tabs.DeviceTabsPresenter
import org.superdro.core.ui.tabs.EditMachinePresenter
import org.superdro.core.ui.tabs.FirstConnectRequests
import org.superdro.core.ui.tabs.RequestSender
import org.superdro.core.ui.tabs.adddevice.AddDevicePresenter
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPresenter

/** The tag of the set of the machines whose own mute switch is on. */
internal const val MUTED_MACHINES: String = "mutedMachines"

/**
 * The bindings of `core:ui`: the navigation holder of the app
 * scope, the entries, the presenter context, the sound controller, and the presenters. A
 * presenter that needs an argument (the device ID) has a Kodein factory.
 */
internal val uiModule = DI.Module("uiModule") {
    // The back stacks and their presenters live in the app scope, so a configuration change
    // does not close a presenter.
    bindSingleton { NavigationHolder() }
    bindSingleton<EntryRegistry> { appEntryRegistry() }
    bindSingleton { PresenterContext(dispatcher = Dispatchers.Default, clock = instance()) }
    bindSingleton<Connections> { ManagerConnections(instance<ConnectionManager>(), instance<CapabilitySource>(), instance<RequestSender>()) }

    bindSingleton<StateFlow<Set<DeviceId>>>(tag = MUTED_MACHINES) {
        val scope = instance<CoroutineScope>(tag = APP_SCOPE)
        instance<MachineRepository>().profiles()
            .map { profiles -> profiles.filter { profile -> profile.muteSounds }.mapTo(HashSet()) { profile -> DeviceId(profile.id) } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())
    }
    bindSingleton {
        SoundController(
            sounds = instance(),
            settings = instance<AppSettingsStore>().settings,
            mutedMachines = instance(tag = MUTED_MACHINES),
            clock = instance(),
        ).also { controller ->
            // The link tones follow each session while the process lives.
            controller.watch(instance(tag = APP_SCOPE), instance<ConnectionManager>().sessions)
        }
    }

    // The frame: the tabs presenter lives in the app scope; the entry presenters close with
    // their entries.
    bindSingleton<RequestSender> { AppRequestSender(instance(), instance(tag = APP_SCOPE)) }
    bindSingleton { FirstConnectRequests(settings = instance()) }
    bindSingleton {
        DeviceTabsPresenter(
            connections = instance(),
            tabStore = instance(),
            machines = instance(),
            context = instance(),
            disconnectAll = instance(),
            keepAliveStatus = instance<ConnectionKeepAlive>().status,
        )
    }
    // The profiles of the demo machines of a debug build. The graph makes the object in the
    // background: the first frame does not need it.
    bindSingleton {
        DemoMachines(debugAdapter = instance(), connections = instance(), machines = instance(), adapters = instance())
            .also { demo -> demo.start(instance(tag = APP_SCOPE)) }
    }
    bindBackgroundStart<DemoMachines>()
    bindProvider {
        AddDevicePresenter(
            connections = instance(),
            tabStore = instance(),
            machines = instance(),
            adapters = instance(),
            appSettings = instance<AppSettingsStore>().settings,
            discoveries = instance(),
            capabilities = instance(),
            debugAdapter = instance(),
            requests = instance(),
            firstConnect = instance(),
            context = instance(),
        )
    }
    bindProvider { RememberedMachinesPresenter(connections = instance(), tabStore = instance(), machines = instance(), context = instance()) }
    bindFactory<String, EditMachinePresenter> { profileId ->
        EditMachinePresenter(profileId = profileId, connections = instance(), machines = instance(), context = instance())
    }

    // The readout of each tab: the tab owns it and closes it when the tab goes away.
    bindFactory<DeviceId, TabReadoutPresenter> { id ->
        ReadoutPresenter(
            deviceId = id,
            connections = instance(),
            machines = instance(),
            adapters = instance(),
            tools = instance(),
            appSettings = instance(),
            sounds = instance(),
            context = instance(),
            probes = instance(),
        )
    }
}
