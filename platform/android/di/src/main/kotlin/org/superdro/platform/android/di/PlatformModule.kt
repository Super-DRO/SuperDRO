package org.superdro.platform.android.di

import android.app.Application
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.android.AndroidEngine
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.provider
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.FileStreamRecorder
import org.superdro.core.connection.LocalNetworkPermission
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.PermissionKind
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.ble.BleAdapterDiscovery
import org.superdro.core.connection.ble.BleTransportFactory
import org.superdro.core.connection.tcp.TcpTransportFactory
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.withFakeTransport
import org.superdro.core.model.TransportKind
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.util.SecretStore
import org.superdro.platform.android.bluetooth.BluetoothCapabilityMonitor
import org.superdro.platform.android.bluetooth.SppAdapterDiscovery
import org.superdro.platform.android.bluetooth.SppTransportFactory
import org.superdro.platform.android.camera.AndroidUvcCameraBackend
import org.superdro.platform.android.camera.CameraPermissionBridge
import org.superdro.platform.android.network.AndroidNetworkInterfaces
import org.superdro.platform.android.network.NetworkCapabilityMonitor
import org.superdro.platform.android.network.NetworkRules
import org.superdro.platform.android.service.AndroidBatteryOptimization
import org.superdro.platform.android.service.AndroidConnectionKeepAlive
import org.superdro.platform.android.service.ComposeServiceTexts
import org.superdro.platform.android.service.ConnectionServiceState
import org.superdro.platform.android.service.LinkIoDispatcher
import org.superdro.platform.android.service.ServiceTexts
import org.superdro.platform.android.service.WakeLockSetting
import org.superdro.platform.android.store.AndroidKeystoreSecretStore
import org.superdro.platform.android.store.AndroidSqlDriverFactory
import org.superdro.platform.android.usb.UsbDeviceMonitor
import org.superdro.platform.android.usb.UsbPermissionRequester
import org.superdro.platform.android.usb.UsbSerialDiscovery
import org.superdro.platform.android.usb.UsbSerialTransportFactory

/** The DI tag of the scope of the platform monitors. They run while the process runs. */
const val PLATFORM_SCOPE_TAG: String = "platformScope"

/**
 * The Android bindings of the platform contracts. The app binds its [Application] and imports this
 * module with `coreDiModule`.
 *
 * The bindings that the common graph of `core:di` takes:
 * - `Map<TransportKind, TransportFactory>`: SPP, USB, TCP, BLE, and the fake link type of a
 *   debug build (`DebugAdapter.withFakeTransport`).
 * - `List<AdapterDiscovery>`: SPP, USB, BLE, and the fake adapters of a debug build.
 * - [CapabilitySource], [ConnectionKeepAlive] (`AndroidConnectionKeepAlive`).
 * - [LocalNetworkPermission], only on API 37 and higher: the MCP server waits for the grant.
 * - [CoroutineDispatcher] with the tag `"linkIo"` ([LinkIoDispatcher]).
 * - [StreamRecorder]: one file for each recorded link in `<files>/streams`.
 * - [NetworkInterfaces], [SecretStore], [UvcCameraBackend], [SqlDriver].
 * - [BatteryOptimization].
 *
 * The bindings that this module takes from the common graph: `DebugAdapter` (app),
 * `ConnectionRequestManager`, and `AppSettingsStore` (`core:di`). `app:android` binds `UiSounds`,
 * `FilePicker`, and `ConnectionServiceHost`.
 */
val platformModule = DI.Module("platformModule") {
    bindSingleton<SqlDriver> { AndroidSqlDriverFactory(context = instance<Application>()).create() }

    bindSingleton<CoroutineScope>(tag = PLATFORM_SCOPE_TAG) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("platform"))
    }

    // The high priority threads of the link reads and writes.
    bindSingleton<CoroutineDispatcher>(tag = LinkIoDispatcher.DI_TAG) { LinkIoDispatcher() }

    // The capabilities of the links.
    bindSingleton { BluetoothCapabilityMonitor(instance<Application>(), instance(tag = PLATFORM_SCOPE_TAG)) }
    bindSingleton { NetworkCapabilityMonitor(instance<Application>(), instance(tag = PLATFORM_SCOPE_TAG)) }
    bindSingleton { UsbPermissionRequester(instance<Application>()) }
    bindSingleton { UsbDeviceMonitor(instance<Application>(), instance(tag = PLATFORM_SCOPE_TAG), instance<UsbPermissionRequester>()) }
    bindSingleton<CapabilitySource> {
        AndroidCapabilitySource(
            bluetooth = instance<BluetoothCapabilityMonitor>().capability,
            network = instance<NetworkCapabilityMonitor>().capability,
            usb = instance<UsbDeviceMonitor>().attached,
            scope = instance(tag = PLATFORM_SCOPE_TAG),
        )
    }
    // The local network permission of the MCP server. An older Android needs none: no binding,
    // and the runner gets null.
    if (NetworkRules.localNetworkPermissionNeeded(Build.VERSION.SDK_INT)) {
        bindSingleton { LocalNetworkPermission(instance<NetworkCapabilityMonitor>().localNetworkGranted) }
    }

    // The one BlueFalcon client. Only a BLE link or the BLE discovery makes it.
    bindSingleton<BlueFalcon> {
        BlueFalcon(
            AndroidEngine(
                instance<Application>(),
                logger = BlueFalconLogger(),
                autoDiscoverAllServicesAndCharacteristics = true,
            ),
        )
    }

    // One transport factory for each link type.
    bindSingleton<Map<TransportKind, TransportFactory>> {
        val application = instance<Application>()
        val linkIo = instance<CoroutineDispatcher>(tag = LinkIoDispatcher.DI_TAG)
        val blueFalcon = provider<BlueFalcon>()
        val factories = mapOf(
            TransportKind.BluetoothSpp to SppTransportFactory(application, linkIo),
            TransportKind.UsbSerial to UsbSerialTransportFactory(application, linkIo),
            // The Ktor selector holds one thread. The link dispatcher makes a new thread for each
            // blocked task, so the selector does not take a thread from a link.
            TransportKind.Tcp to TcpTransportFactory(linkIo),
            TransportKind.Ble to LazyTransportFactory { BleTransportFactory(blueFalcon()) },
        )
        instance<DebugAdapter>().withFakeTransport(factories)
    }

    // The adapter lists of the add-device sheet.
    bindSingleton { SppAdapterDiscovery(instance<Application>()) }
    bindSingleton { UsbSerialDiscovery(instance<Application>(), instance<UsbPermissionRequester>()) }
    bindSingleton<List<AdapterDiscovery>> {
        val blueFalcon = provider<BlueFalcon>()
        listOfNotNull(
            instance<SppAdapterDiscovery>(),
            instance<UsbSerialDiscovery>(),
            LazyAdapterDiscovery(TransportKind.Ble) { BleAdapterDiscovery(blueFalcon()) },
            instance<DebugAdapter>().discovery,
        )
    }

    // The connection service.
    bindSingleton { ConnectionServiceState() }
    bindSingleton { AndroidConnectionKeepAlive(instance<Application>(), instance()) }
    bindSingleton<ConnectionKeepAlive> { instance<AndroidConnectionKeepAlive>() }
    bindSingleton<ServiceTexts> { ComposeServiceTexts }
    bindSingleton<WakeLockSetting> {
        val settings = instance<AppSettingsStore>()
        WakeLockSetting { settings.settings.map { value -> value.wakeLockWhileLinked }.distinctUntilChanged() }
    }
    bindSingleton { AndroidBatteryOptimization(instance<Application>()) }
    bindSingleton<BatteryOptimization> { instance<AndroidBatteryOptimization>() }
    bindSingleton {
        PlatformRefresher(
            bluetooth = instance(),
            network = instance(),
            usb = instance(),
            sppDiscovery = instance(),
            usbDiscovery = instance(),
            battery = instance(),
            keepAlive = instance(),
        )
    }

    // The recorded byte streams of the debug option. getFilesDir reads the disk, so the recorder
    // finds the folder at the first record, not on the thread that makes the graph.
    bindSingleton<StreamRecorder> {
        val application = instance<Application>()
        FileStreamRecorder(directory = { File(application.filesDir, FileStreamRecorder.FOLDER) })
    }

    // The network interfaces, the secret store, and the camera back end.
    bindSingleton<NetworkInterfaces> { AndroidNetworkInterfaces(instance<Application>(), instance(tag = PLATFORM_SCOPE_TAG)) }
    bindSingleton<SecretStore> { AndroidKeystoreSecretStore(instance<Application>()) }
    bindSingleton {
        val requests = instance<ConnectionRequestManager>()
        CameraPermissionBridge { requests.send(ConnectionRequest.RequestPermissions(setOf(PermissionKind.Camera))) }
    }
    bindSingleton<UvcCameraBackend> { AndroidUvcCameraBackend(instance<Application>(), instance<CameraPermissionBridge>()) }
}
