package org.superdro.app.desktop

import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.app.desktop.files.AwtFilePicker
import org.superdro.app.desktop.sound.DesktopUiSounds
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.NetworkInterfaces
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.TransportFactory
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.di.LINK_IO_DISPATCHER
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.model.AxisId
import org.superdro.core.model.TransportKind
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.sqliteVersion
import org.superdro.core.ui.sound.UiSounds
import org.superdro.core.ui.tabs.DisconnectAllCommand
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.tabs.FirstConnectRequests
import org.superdro.core.util.FilePicker
import org.superdro.core.util.SecretStore
import org.superdro.platform.desktop.di.DesktopConnectionKeepAlive
import org.superdro.platform.desktop.di.DesktopLinkIoDispatcher
import org.superdro.platform.desktop.di.DesktopNoCameraBackend
import org.superdro.platform.desktop.serial.OpenSerialPort
import org.superdro.platform.desktop.serial.SerialLineSettings
import org.superdro.platform.desktop.serial.SerialPortInfo
import org.superdro.platform.desktop.serial.SerialPorts
import org.superdro.platform.desktop.store.DesktopSecretStore
import org.superdro.platform.desktop.store.DesktopStore

/**
 * The desktop app: the common code runs on the desktop JVM with only the
 * new platform shells. The test builds the DI graph of the desktop app, starts the app in
 * the demo mode, and runs the two sessions against the fake adapter.
 */
class DesktopGraphTest {

    private val root: File = Files.createTempDirectory("superdro-desktop-app").toFile()

    /** No serial port: the test must not list or open a port of this computer. */
    private val noPorts = object : SerialPorts {
        override fun list(): List<SerialPortInfo> = emptyList()
        override fun open(id: String, settings: SerialLineSettings): OpenSerialPort = error("No serial port in the test.")
    }

    private val apps = mutableListOf<DesktopApp>()

    private fun app(demo: Boolean): DesktopApp {
        val options = DesktopOptions(demo = demo, dataDirectory = File(root, "data"))
        return DesktopApp(options, desktopDI(options, DesktopStore(options.dataDirectory), serialPorts = { noPorts })).also(apps::add)
    }

    @AfterTest
    fun close() {
        apps.forEach(DesktopApp::close)
        root.deleteRecursively()
    }

    @Test
    fun theDemoModeConnectsTwoFakeMachinesAndTheReadoutMoves() = runBlocking {
        val app = app(demo = true)
        app.start()
        val manager = app.di.direct.instance<ConnectionManager>()
        val sessions = withTimeout(20_000) { manager.sessions.first { map -> map.size == 2 } }
        assertEquals(setOf(FakeDebugAdapter.DEMO_MILL_ID, FakeDebugAdapter.DEMO_LATHE_ID), sessions.keys.map { id -> id.value }.toSet())
        sessions.values.forEach { session -> withTimeout(20_000) { session.state.first { state -> state is SessionState.Connected } } }

        // The hole pattern of the demo mill moves X: the position in µm changes.
        val mill = sessions.values.single { session -> session.id.value == FakeDebugAdapter.DEMO_MILL_ID }
        val firstX = withTimeout(20_000) { mill.inputs.first { snapshot -> snapshot.axisValue(AxisId.X) != null } }.axisValue(AxisId.X)
        val laterX = withTimeout(20_000) {
            mill.inputs.first { snapshot -> snapshot.axisValue(AxisId.X).let { x -> x != null && x != firstX } }
        }.axisValue(AxisId.X)
        assertNotEquals(firstX, laterX)

        // The demo machines have their stored profiles in the database file of the data directory.
        val machines = app.di.direct.instance<MachineRepository>()
        withTimeout(20_000) { machines.profiles().first { profiles -> profiles.size == 2 } }
        assertEquals(listOf("Demo lathe", "Demo mill"), machines.profiles().first().map { profile -> profile.name }.sorted())
        assertTrue(File(root, "data/superdro.db").isFile)

        // The links want the process. The desktop keep-alive reports it and shows no warning.
        val keepAlive = app.di.direct.instance<ConnectionKeepAlive>()
        withTimeout(20_000) { keepAlive.status.first { status -> status == KeepAliveStatus.Running } }
        assertTrue(app.di.direct.instance<KeepAliveController>().summary.value != null)
    }

    @Test
    fun theDemoMachinesStayAfterARestartOfTheApp() = runBlocking {
        val first = app(demo = true)
        first.start()
        val machines = first.di.direct.instance<MachineRepository>()
        withTimeout(20_000) { machines.profiles().first { profiles -> profiles.size == 2 } }
        first.close()

        // A second start with no --demo opens the same database file and has no fake adapter.
        val second = app(demo = false)
        second.start()
        assertSame(NoDebugAdapter, second.di.direct.instance<DebugAdapter>())
        assertEquals(2, second.di.direct.instance<MachineRepository>().profiles().first().size)
        assertTrue(TransportKind.Fake !in second.di.direct.instance<Map<TransportKind, TransportFactory>>().keys)
    }

    /** Each binding of the table of coreDiModule comes from the desktop shells. */
    @Test
    fun theGraphHasEachPlatformBindingOfTheDesktop() {
        val app = app(demo = true)
        val direct = app.di.direct
        assertEquals(
            setOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp),
            direct.instance<Map<TransportKind, TransportFactory>>().keys,
        )
        assertEquals(
            listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Fake),
            direct.instance<List<AdapterDiscovery>>().map { discovery -> discovery.kind },
        )
        direct.instance<CapabilitySource>()
        assertIs<DesktopConnectionKeepAlive>(direct.instance<ConnectionKeepAlive>())
        assertIs<DesktopUiSounds>(direct.instance<UiSounds>())
        assertIs<AwtFilePicker>(direct.instance<FilePicker>())
        assertIs<DesktopSecretStore>(direct.instance<SecretStore>())
        direct.instance<NetworkInterfaces>()
        assertIs<DesktopLinkIoDispatcher>(direct.instance<CoroutineDispatcher>(tag = LINK_IO_DISPATCHER))
        direct.instance<StreamRecorder>()
        assertSame(DesktopNoCameraBackend, direct.instance<UvcCameraBackend>())
        assertIs<FakeDebugAdapter>(direct.instance<DebugAdapter>())
        direct.instance<DisconnectAllCommand>()
        assertEquals(File(root, "data/tabs.json").absolutePath, direct.instance<StoreIOProvider>().pathFor("tabs.json"))
        assertEquals("3.53.4", createSuperDroDatabase(direct.instance<SqlDriver>()).sqliteVersion())
    }

    @Test
    fun theDesktopMakesNoFirstConnectRequest() {
        val app = app(demo = false)
        app.start()
        val firstConnect = app.di.direct.instance<FirstConnectRequests>()
        // The start claimed each item, so the add-device sheet sends no permission request and
        // shows no battery optimization dialog.
        runBlocking { assertEquals(false, firstConnect.claimLocalNetwork()) }
        val settings = app.di.direct.instance<AppSettingsStore>().settings.value
        assertEquals(true, settings.notificationPermissionAsked)
        assertEquals(true, settings.batteryExemptionAsked)
    }
}
