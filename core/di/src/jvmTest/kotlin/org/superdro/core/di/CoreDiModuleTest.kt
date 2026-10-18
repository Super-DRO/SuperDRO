package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.WantedLink
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.sqliteVersion
import org.superdro.core.store.valueOrNull
import com.toxicbakery.logging.Arbor
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.navigation.TabReadoutPresenter
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.session.Connections
import org.superdro.core.ui.settings.logs.SessionLogSink
import org.superdro.core.ui.sound.SoundController
import org.superdro.core.ui.tabs.DeviceTabsPresenter
import org.superdro.core.ui.tabs.EditMachinePresenter
import org.superdro.core.ui.tabs.adddevice.AddDevicePresenter
import org.superdro.core.ui.tabs.remembered.RememberedMachinesPresenter

/**
 * Stops the presenters, the sessions, and the app scope of a test graph, waits for the
 * database writer, and closes the database driver.
 */
internal fun DI.closeGraph() {
    Arbor.harvest(direct.instance<SessionLogSink>())
    val holder = direct.instance<NavigationHolder>()
    holder.retainTabs(emptyList())
    holder.clear()
    holder.presenters.keys.forEach(holder.presenters::close)
    direct.instance<DeviceTabsPresenter>().close()
    direct.instance<ConnectionManager>().close()
    direct.instance<CoroutineScope>(tag = APP_SCOPE).cancel()
    runBlocking { direct.instance<StoreWriter>().awaitIdle() }
    direct.instance<SqlDriver>().close()
}

/** The common graph builds with the platform bindings of the table of [coreDiModule], as an app gives them. */
class CoreDiModuleTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-di").toFile()

    private val di = DI {
        import(coreDiModule)
        import(noOpPlatformModule)
        bindSingleton<SqlDriver> {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { setProperty("foreign_keys", "true") })
                .also { driver -> SuperDroDatabase.Schema.create(driver) }
        }
        bindProvider<StoreIOProvider>(overrides = true) { StoreIOProviderJvm(directory) }
    }

    @AfterTest
    fun close() {
        di.closeGraph()
        directory.deleteRecursively()
    }

    @Test
    fun theGraphGivesTheDatabaseTheJsonAndTheStoreProvider() {
        val direct = di.direct
        assertEquals("3.53.4", direct.instance<SuperDroDatabase>().sqliteVersion())
        assertSame(SuperDroJson, direct.instance<Json>())
        assertEquals(StoreIOProviderJvm::class, direct.instance<StoreIOProvider>()::class)
        assertSame(direct.instance<ConnectionRequestManager>(), direct.instance<ConnectionRequestManager>())
    }

    @Test
    fun theSingletonsOfTheAppScopeAreOneObject() {
        val direct = di.direct
        assertSame(direct.instance<StoreWriter>(), direct.instance<StoreWriter>())
        assertSame(direct.instance<ConnectionManager>(), direct.instance<ConnectionManager>())
        assertSame(direct.instance<NavigationHolder>(), direct.instance<NavigationHolder>())
        assertSame(direct.instance<SoundController>(), direct.instance<SoundController>())
        assertSame(direct.instance<KeepAliveController>(), direct.instance<KeepAliveController>())
        direct.instance<EntryRegistry>()
        direct.instance<PresenterContext>()
        assertSame(direct.instance<ConnectionManager>().sessions, direct.instance<Connections>().sessions)
        direct.instance<AppSettingsStore>()
        assertSame(direct.instance<DeviceTabsPresenter>(), direct.instance<DeviceTabsPresenter>())
    }

    @Test
    fun theEntryPresentersAreNewObjects() {
        val direct = di.direct
        val first = direct.instance<AddDevicePresenter>()
        val second = direct.instance<AddDevicePresenter>()
        assertNotSame(first, second)
        first.close()
        second.close()
        direct.instance<RememberedMachinesPresenter>().close()
        direct.instance<String, EditMachinePresenter>(arg = "mill").close()
        val mill = direct.instance<DeviceId, TabReadoutPresenter>(arg = DeviceId("mill"))
        val secondMill = direct.instance<DeviceId, TabReadoutPresenter>(arg = DeviceId("mill"))
        val lathe = direct.instance<DeviceId, TabReadoutPresenter>(arg = DeviceId("lathe"))
        assertEquals(DeviceId("lathe"), lathe.deviceId)
        assertNotSame(mill, secondMill)
        mill.close()
        secondMill.close()
        lathe.close()
    }

    @Test
    fun theWantedLinksAreTheRememberedMachinesThatAreNotPaused() = runBlocking {
        val direct = di.direct
        val machines = direct.instance<MachineRepository>()
        val tabs = direct.instance<TabStore>()
        val mill = machines.createProfile(MachineProfile.create(name = "Mill").copy(adapter = AdapterAddress.Tcp("dro.local", 8080)))
            .valueOrNull()!!
        val lathe = machines.createProfile(MachineProfile.create(name = "Lathe").copy(adapter = AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3")))
            .valueOrNull()!!
        tabs.remember(mill.id)
        tabs.remember(lathe.id)
        tabs.setPaused(lathe.id, true)
        val expected = listOf(WantedLink(DeviceId(mill.id), "Mill", AdapterAddress.Tcp("dro.local", 8080)))
        val links = withTimeout(5_000) { direct.instance<WantedLinkSource>().wantedLinks.first { list -> list == expected } }
        assertEquals(expected, links)
    }

    @Test
    fun theDeviceSetupHasTheProfileAndTheAdapterProfileOfTheTab() = runBlocking {
        val direct = di.direct
        val adapter = direct.instance<AdapterRepository>().saveAdapterProfile(AdapterProfile(alias = "Mill adapter")).valueOrNull()!!
        val mill = direct.instance<MachineRepository>()
            .createProfile(MachineProfile.create(name = "Mill").copy(adapterProfileId = adapter.id))
            .valueOrNull()!!
        val setups = direct.instance<DeviceSetupSource>()
        val setup = withTimeout(5_000) { setups.setup(DeviceId(mill.id)).first() }!!
        assertEquals(mill.id, setup.machine.id)
        assertEquals(adapter.id, setup.adapter?.id)
        assertNull(withTimeout(5_000) { setups.setup(DeviceId("no-such-machine")).first() })
    }
}
