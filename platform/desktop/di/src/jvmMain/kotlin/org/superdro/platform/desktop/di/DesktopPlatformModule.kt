package org.superdro.platform.desktop.di

import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.FileStreamRecorder
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.NoBatteryOptimization
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.tcp.TcpTransportFactory
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.di.LINK_IO_DISPATCHER
import org.superdro.core.model.TransportKind
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.util.SecretStore
import org.superdro.platform.desktop.serial.JSerialCommPorts
import org.superdro.platform.desktop.serial.SerialAdapterDiscovery
import org.superdro.platform.desktop.serial.SerialPortMonitor
import org.superdro.platform.desktop.serial.SerialPorts
import org.superdro.platform.desktop.serial.SerialTransportFactory
import org.superdro.platform.desktop.store.DesktopStore

/** The DI tag of the scope of the desktop platform monitors. They run while the process runs. */
const val DESKTOP_PLATFORM_SCOPE: String = "desktopPlatformScope"

/**
 * The desktop bindings of the platform contracts. `app:desktop` imports this module after
 * `coreDiModule`, with `allowOverride = true`: the module replaces the `StoreIOProvider` of the JVM
 * module of `core:di` with the provider of [store], so that the argument `--data-dir` applies.
 *
 * | Contract | Desktop binding |
 * |---|---|
 * | `Map<TransportKind, TransportFactory>` | SPP and USB serial: [SerialTransportFactory] (jSerialComm). TCP: `TcpTransportFactory` (common). No BLE: see below. `core:di` adds `Fake` from the `DebugAdapter`. |
 * | `Transport` | `SerialPortTransport`, `TcpTransport` |
 * | `List<AdapterDiscovery>` | The RFCOMM ports and the USB serial ports ([SerialPortMonitor]), and the fake adapters of the demo mode |
 * | [CapabilitySource] | [DesktopCapabilitySource]: the network and the attached USB serial devices |
 * | [ConnectionKeepAlive] | [DesktopConnectionKeepAlive]: a no-op, the process lives while the window lives |
 * | Request collector | [DesktopRequestHandler]; `app:desktop` collects the bus |
 * | [StoreIOProvider] | `StoreIOProviderJvm` of [DesktopStore] |
 * | `storeIoDispatcher` | `Dispatchers.IO` (the JVM `actual` of `core:store`) |
 * | Keep the screen on | The common `Modifier.keepScreenOn()`. No desktop code. |
 * | `UiSounds` | `app:desktop` (`javax.sound.sampled`) |
 * | [DebugAdapter] | `app:desktop`: the fake adapter with `--demo`, else `NoDebugAdapter` |
 * | BlueFalcon engine | None. The BlueFalcon desktop engines need native code that is new to SuperDRO (a DLL on Windows, a dylib on macOS, JNA and D-Bus on Linux). The desktop app has no BLE. A BLE address gets no factory, and its session goes to `Failed`. |
 * | `FilePicker` | `app:desktop` (the AWT `FileDialog`) |
 * | [NetworkInterfaces] | [DesktopNetworkInterfaces] (`java.net.NetworkInterface`) |
 * | [UvcCameraBackend] | [DesktopNoCameraBackend]: no camera |
 * | [SecretStore] | `DesktopSecretStore`: a file store with a key file (read its warning) |
 * | `CoroutineDispatcher` `"linkIo"` | [DesktopLinkIoDispatcher] |
 * | `AutoStart` | None. The user starts the app. A start at login is a setting of the operating system. |
 * | [SqlDriver] | `JdbcSqliteDriver` of `DesktopSqlDriverFactory` on `superdro.db` |
 * | `WakeLockController` | None. The power settings of the operating system apply. |
 *
 * The module also binds [StreamRecorder] (files in `<data directory>/streams`) and
 * [BatteryOptimization] ([NoBatteryOptimization]). It takes [DebugAdapter] from the app module.
 *
 * @param store The files of the app.
 * @param serialPorts The serial ports of the system. A test gives a fake.
 */
fun desktopPlatformModule(
    store: DesktopStore,
    serialPorts: () -> SerialPorts = { JSerialCommPorts() },
): DI.Module = DI.Module("desktopPlatformModule") {
    // Replaces the StoreIOProviderJvm of the default directory (core:di, jvmMain).
    bindSingleton<StoreIOProvider>(overrides = true) { store.storeIO }
    bindSingleton { store }

    // The one driver of the process.
    bindSingleton<SqlDriver> { store.openDriver() }

    bindSingleton<CoroutineScope>(tag = DESKTOP_PLATFORM_SCOPE) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("platform"))
    }

    // The link dispatcher.
    bindSingleton<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER) { DesktopLinkIoDispatcher() }

    // The serial ports and the network.
    bindSingleton { serialPorts() }
    bindSingleton { SerialPortMonitor(instance(), instance(tag = DESKTOP_PLATFORM_SCOPE)) }
    bindSingleton { DesktopNetworkInterfaces(instance(tag = DESKTOP_PLATFORM_SCOPE)) }
    bindSingleton<NetworkInterfaces> { instance<DesktopNetworkInterfaces>() }
    bindSingleton<CapabilitySource> {
        DesktopCapabilitySource(
            network = instance<DesktopNetworkInterfaces>().capability,
            usb = instance<SerialPortMonitor>().attachedUsb,
            scope = instance(tag = DESKTOP_PLATFORM_SCOPE),
        )
    }

    // One transport factory for each link type of the desktop.
    bindSingleton<Map<TransportKind, TransportFactory>> {
        val linkIo = instance<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER)
        val ports = instance<SerialPorts>()
        mapOf(
            TransportKind.BluetoothSpp to SerialTransportFactory(TransportKind.BluetoothSpp, ports, linkIo),
            TransportKind.UsbSerial to SerialTransportFactory(TransportKind.UsbSerial, ports, linkIo),
            // The Ktor selector holds one thread. The link dispatcher makes a new thread for each
            // blocked task, so the selector does not take a thread from a link.
            TransportKind.Tcp to TcpTransportFactory(linkIo),
        )
    }

    // The adapter lists of the add-device sheet.
    bindSingleton<List<AdapterDiscovery>> {
        val monitor = instance<SerialPortMonitor>()
        listOfNotNull(
            SerialAdapterDiscovery(TransportKind.BluetoothSpp, monitor),
            SerialAdapterDiscovery(TransportKind.UsbSerial, monitor),
            instance<DebugAdapter>().discovery,
        )
    }

    // The keep-alive and the battery optimization.
    bindSingleton<ConnectionKeepAlive> { DesktopConnectionKeepAlive() }
    bindSingleton<BatteryOptimization> { NoBatteryOptimization }

    // The request collector.
    bindSingleton { DesktopRequestHandler(refreshPorts = instance<SerialPortMonitor>()::refresh) }

    // The recorded byte streams of the debug option.
    bindSingleton<StreamRecorder> { FileStreamRecorder(File(store.directory, FileStreamRecorder.FOLDER)) }

    // The secret store and the camera back end.
    bindSingleton<SecretStore> { store.secretStore() }
    bindSingleton<UvcCameraBackend> { DesktopNoCameraBackend }
}
