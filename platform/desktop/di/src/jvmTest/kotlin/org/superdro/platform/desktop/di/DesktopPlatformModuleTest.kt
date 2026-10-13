package org.superdro.platform.desktop.di

import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.BatteryOptimization
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.FileStreamRecorder
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.tcp.TcpTransportFactory
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.di.LINK_IO_DISPATCHER
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.util.SecretStore
import org.superdro.platform.desktop.serial.OpenSerialPort
import org.superdro.platform.desktop.serial.SerialLineSettings
import org.superdro.platform.desktop.serial.SerialPortInfo
import org.superdro.platform.desktop.serial.SerialPorts
import org.superdro.platform.desktop.serial.SerialTransportFactory
import org.superdro.platform.desktop.store.DesktopSecretStore
import org.superdro.platform.desktop.store.DesktopStore

/** The desktop platform module gives each platform contract of the table of `coreDiModule`. */
class DesktopPlatformModuleTest {

    private val root: File = Files.createTempDirectory("superdro-desktop-di").toFile()
    private val usbPort = SerialPortInfo(id = "/dev/ttyUSB0", name = "ttyUSB0", description = "CP2102", vendorId = 0x10C4, productId = 0xEA60)
    private val ports = object : SerialPorts {
        override fun list(): List<SerialPortInfo> = listOf(usbPort)
        override fun open(id: String, settings: SerialLineSettings): OpenSerialPort = error("No open in this test.")
    }
    private val store = DesktopStore(File(root, "data"))

    private val di = DI {
        // The binding of the JVM module of core:di that the desktop module replaces.
        bindProvider<StoreIOProvider> { StoreIOProviderJvm(File(root, "default")) }
        import(desktopPlatformModule(store, serialPorts = { ports }), allowOverride = true)
        bindSingleton<DebugAdapter> { NoDebugAdapter }
    }

    @AfterTest
    fun close() {
        di.direct.instance<CoroutineScope>(tag = DESKTOP_PLATFORM_SCOPE).cancel()
        di.direct.instance<SqlDriver>().close()
        (di.direct.instance<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER) as DesktopLinkIoDispatcher).close()
        root.deleteRecursively()
    }

    @Test
    fun theFilesAreInTheDataDirectoryOfTheStore() {
        val direct = di.direct
        assertSame(store.storeIO, direct.instance<StoreIOProvider>())
        assertEquals(File(root, "data/settings.json").absolutePath, direct.instance<StoreIOProvider>().pathFor("settings.json"))
        direct.instance<SqlDriver>()
        assertTrue(File(root, "data/superdro.db").isFile)
        assertIs<DesktopSecretStore>(direct.instance<SecretStore>())
        assertIs<FileStreamRecorder>(direct.instance<StreamRecorder>())
    }

    @Test
    fun theFactoriesAreSppUsbAndTcpWithNoBle() {
        val factories = di.direct.instance<Map<TransportKind, TransportFactory>>()
        assertEquals(setOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp), factories.keys)
        assertEquals(TransportKind.BluetoothSpp, (factories.getValue(TransportKind.BluetoothSpp) as SerialTransportFactory).kind)
        assertEquals(TransportKind.UsbSerial, (factories.getValue(TransportKind.UsbSerial) as SerialTransportFactory).kind)
        assertIs<TcpTransportFactory>(factories.getValue(TransportKind.Tcp))
        assertIs<DesktopLinkIoDispatcher>(di.direct.instance<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER))
    }

    @Test
    fun theDiscoveriesAndTheCapabilitiesComeFromTheSerialPorts() = runBlocking {
        val discoveries = di.direct.instance<List<AdapterDiscovery>>()
        assertEquals(listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial), discoveries.map { discovery -> discovery.kind })
        val usb = discoveries.single { discovery -> discovery.kind == TransportKind.UsbSerial }
        val rows = withTimeout(5_000) { usb.adapters.first { list -> list.isNotEmpty() } }
        assertEquals(listOf(AdapterAddress.UsbSerial(0x10C4, 0xEA60)), rows.map { row -> row.address })

        val capabilities = di.direct.instance<CapabilitySource>()
        withTimeout(5_000) { capabilities.state.first { state -> state.attachedUsb.isNotEmpty() } }
        assertEquals(LinkCapability.Available, capabilities.state.value.capabilityOf(AdapterAddress.UsbSerial(0x10C4, 0xEA60)))
    }

    @Test
    fun theOtherContractsHaveTheirDesktopBinding() {
        val direct = di.direct
        assertIs<DesktopConnectionKeepAlive>(direct.instance<ConnectionKeepAlive>())
        assertEquals(true, direct.instance<BatteryOptimization>().exempt.value)
        assertIs<DesktopNetworkInterfaces>(direct.instance<NetworkInterfaces>())
        assertSame(DesktopNoCameraBackend, direct.instance<UvcCameraBackend>())
        direct.instance<DesktopRequestHandler>()
    }
}
