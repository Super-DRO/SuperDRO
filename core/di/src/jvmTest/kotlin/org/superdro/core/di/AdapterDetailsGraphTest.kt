package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.toDeviceRef
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.fakeadapter.FakeAdapter
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.fakeadapter.FakeScenarios
import org.superdro.core.fakeadapter.FakeTransportFactory
import org.superdro.core.protocol.InputChannel
import org.superdro.core.protocol.InputMode
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.adapter.AdapterUiDeps
import org.superdro.core.ui.adapter.details.AdapterDetailsPresenter
import org.superdro.core.ui.navigation.EntryRegistry

/**
 * The Adapter Details screen with the full common graph, the real connection manager, and the fake
 * adapter: the bytes on the wire, in real time.
 */
class AdapterDetailsGraphTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-adapter").toFile()

    private val di = DI {
        import(coreDiModule)
        import(noOpPlatformModule)
        bindSingleton<SqlDriver> {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
                .also { driver -> SuperDroDatabase.Schema.create(driver) }
        }
        bindProvider<StoreIOProvider>(overrides = true) { StoreIOProviderJvm(directory) }
        bindSingleton<DebugAdapter>(overrides = true) { FakeDebugAdapter() }
    }

    @AfterTest
    fun close() {
        di.closeGraph()
        directory.deleteRecursively()
    }

    private val mill = DeviceId(FakeDebugAdapter.DEMO_MILL_ID)

    private fun fake(): FakeAdapter {
        val debug = di.direct.instance<DebugAdapter>() as FakeDebugAdapter
        return (debug.transportFactory as FakeTransportFactory).adapter(FakeScenarios.DemoMill.name)!!
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 15_000L, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) {
                while (!condition()) delay(25L)
            }
        } catch (error: Exception) {
            throw AssertionError("No $what in $timeoutMs ms. The adapter received ${fake().received}.", error)
        }
    }

    @Test
    fun theGraphRegistersTheTabEffectOfTheAdapterArea() {
        val registry = di.direct.instance<EntryRegistry>()
        di.direct.instance<Boolean>(tag = ADAPTER_TAB_EFFECTS)
        assertEquals(1, registry.tabEffectContents.size, "The identity prompt of each tab.")
    }

    @Test
    fun theDetailsQueriesGoOutInTheOrderOfD22AndSaveWritesTheMode() = runBlocking {
        val manager = di.direct.instance<ConnectionManager>()
        val machines = di.direct.instance<MachineRepository>()
        val link = di.direct.instance<DebugAdapter>().demoLinks.first { link -> link.deviceId == mill }
        val session = manager.connect(link.toDeviceRef())
        waitFor("Connected session and stored demo profile") {
            session.state.value is SessionState.Connected && machines.loadProfile(mill.value) != null
        }
        waitFor("start queries") { "#get_mode:v" in fake().received }
        machines.updateProfile(mill.value) { profile -> profile.copy(adapterConfigurationCommands = true) }

        val presenter = AdapterDetailsPresenter(mill, di.direct.instance<AdapterUiDeps>())
        try {
            waitFor("details queries") { fake().received.count { text -> text == "#get_mode:v" } >= 2 }
            val received = fake().received
            val start = received.indexOf("#get:name")
            assertTrue(start >= 0, "The screen asks for the name: $received")
            assertEquals(
                listOf("#get:name", "#get:version", "#get:build", "#get:date") + InputChannel.entries.map { channel -> "#get_mode:${channel.letter}" },
                received.subList(start, start + 10),
                "The order x y z w u v after the secondary group.",
            )
            waitFor("information on the screen") { presenter.uiState.value.model != "???" && presenter.uiState.value.rows.first().modes.isNotEmpty() }
            val state = presenter.uiState.value
            assertEquals(FakeAdapter.MODEL, state.model)
            assertTrue(state.bluetoothId.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")), state.bluetoothId)
            assertTrue(state.commandsAllowed)

            waitFor("the command switch in the session") { presenter.uiState.value.rows.first { it.channel == InputChannel.X }.showClear }
            presenter.onSelectMode(InputChannel.X, InputMode.Tachometer)
            waitFor("Save") { presenter.uiState.value.rows.first { it.channel == InputChannel.X }.showSave }
            presenter.onSave(InputChannel.X)
            waitFor("the mode write and the read-back") {
                val all = fake().received
                val write = all.indexOf("#set_mode:x,4")
                write >= 0 && all.drop(write + 1).contains("#get_mode:x")
            }
            waitFor("restart needed") { presenter.uiState.value.restartNeeded && presenter.uiState.value.showRestart }
            assertEquals(InputMode.Tachometer, fake().mode(InputChannel.X))
        } finally {
            presenter.close()
        }
    }
}
