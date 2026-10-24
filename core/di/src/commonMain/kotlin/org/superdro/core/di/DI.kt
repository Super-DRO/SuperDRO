package org.superdro.core.di

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.superdro.core.model.SuperDroJson
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/** The tag of the [kotlinx.coroutines.CoroutineDispatcher] of the link reads and writes. The platform module binds it. */
const val LINK_IO_DISPATCHER: String = "linkIo"

/** The tag of the [CoroutineScope] of the app: the work that lives as long as the process. */
const val APP_SCOPE: String = "appScope"

/**
 * The root module of the common bindings. The `Application` of each app
 * imports it with the platform module and builds the graph with `DI.lazy`.
 *
 * The common graph binds the stores, the repositories, [org.superdro.core.store.StoreWriter], the
 * connection manager with its [org.superdro.core.connection.DeviceSetupSource], the
 * [org.superdro.core.connection.KeepAliveController], the
 * [org.superdro.core.connection.WantedLinkSource] over `TabStore`, the presenters, and the
 * [org.superdro.core.ui.sound.SoundController].
 *
 * The platform module and the app module must bind these values. `core:di` has no default for
 * them, except [noOpPlatformModule] of the JVM target for the tests:
 *
 * | Type | Tag | Use |
 * |---|---|---|
 * | `Map<TransportKind, TransportFactory>` | — | The transport factories of the link types. The debug adapter adds `Fake`. |
 * | `CapabilitySource` | — | The radio, permission, USB, and network state. |
 * | `ConnectionKeepAlive` | — | The start and the stop of the connection service. |
 * | `UiSounds` | — | The UI sounds. |
 * | `FilePicker` | — | The document picker and the share sheet (`core:util`). |
 * | `SecretStore` | — | The secrets of the data output. |
 * | `NetworkInterfaces` | — | The interfaces of the tablet. |
 * | `CoroutineDispatcher` | [LINK_IO_DISPATCHER] | The link reads and writes. |
 * | `StreamRecorder` | — | The factory of the `RecordSink` of the recorded byte streams. |
 * | `UvcCameraBackend` | — | The USB camera. |
 * | `List<AdapterDiscovery>` | — | The adapters of the add-device sheet: Bluetooth and USB. |
 * | `SqlDriver` | — | The driver of the database. |
 * | `StoreIOProvider` | — | The app files. The JVM module binds `StoreIOProviderJvm`. |
 * | `DebugAdapter` | — | The demo adapter seam: the fake adapter in a debug build, `NoDebugAdapter` in a release build. |
 * | `DisconnectAllCommand` | — | "Disconnect all" of the overflow menu: the command of the connection service host, the same as the action of the notification. |
 * | `BatteryOptimization` | — | The battery optimization state and the link to the system setting of the app settings. |
 */
val coreDiModule = DI.Module("coreDiModule") {
    import(storeIOProviderModule)
    import(sqlDriverModule)
    import(databaseModule)
    import(jsonStoreModule)
    import(connectionModule)
    import(uiModule)
    import(settingsUiModule)
    import(graphicsUiModule)
    import(adapterUiModule)
    import(dataOutputDiModule)
    import(mcpDiModule)
    import(cameraUiModule)
    import(functionsUiModule)

    // The one Json object of the app. Its configuration is with the models that it
    // serializes: org.superdro.core.model.SuperDroJson.
    bindSingleton<Json> { SuperDroJson }

    // The one monotonic clock of the sessions, the presenters, and the link tones.
    bindSingleton<Clock> { MonotonicClock() }

    bindSingleton<CoroutineScope>(tag = APP_SCOPE) {
        val log = Arbor.tag("App")
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("app") +
                CoroutineExceptionHandler { _, error -> log.e(error) { "An app coroutine failed" } },
        )
    }
}
