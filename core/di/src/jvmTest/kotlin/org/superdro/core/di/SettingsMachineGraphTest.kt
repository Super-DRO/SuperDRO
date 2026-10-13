package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.toxicbakery.logging.Arbor
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.settings.about.AboutPresenter
import org.superdro.core.ui.settings.app.AppSettingsPresenter
import org.superdro.core.ui.settings.keys.KeyMappingPresenter
import org.superdro.core.ui.settings.logs.LogsPresenter
import org.superdro.core.ui.settings.logs.SessionLogSink
import org.superdro.core.ui.settings.machine.MachineSettingsArgs
import org.superdro.core.ui.settings.machine.MachineSettingsPresenter
import org.superdro.core.ui.settings.profiles.DeleteProfilePresenter
import org.superdro.core.ui.settings.profiles.MachineProfilesPresenter

/**
 * The machine settings in the full graph: a change of the presenter goes
 * into `MachineRepository`, and the `DeviceSetupSource` of the sessions gives the new input
 * configuration at once.
 */
class SettingsMachineGraphTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-settings").toFile()

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
        Arbor.harvest(di.direct.instance<SessionLogSink>())
        di.direct.instance<ConnectionManager>().close()
        di.direct.instance<SqlDriver>().close()
        directory.deleteRecursively()
    }

    @Test
    fun aChangeOfTheMachineSettingsReachesTheSetupOfTheSessionAtOnce() = runBlocking {
        val machines = di.direct.instance<MachineRepository>()
        val profile = machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        val setups = di.direct.instance<DeviceSetupSource>().setup(DeviceId(profile.id))
        assertEquals('z', setups.filterNotNull().first().machine.z.primary.channel)

        val presenter = di.direct.instance<MachineSettingsArgs, MachineSettingsPresenter>(arg = MachineSettingsArgs(profile.id) {})
        withTimeout(5_000) { presenter.uiState.first { state -> state.profile != null } }
        presenter.setPrimaryChannel(AxisId.Z, 'v')
        presenter.setInvert(AxisId.X, true)
        val changed = withTimeout(5_000) {
            setups.filterNotNull().first { setup -> setup.machine.z.primary.channel == 'v' && setup.machine.x.primary.sign == -1 }
        }
        assertEquals('v', changed.machine.z.primary.channel)
        assertEquals(-1, changed.machine.x.primary.sign)
        presenter.close()
    }

    @Test
    fun aChangeWithNoTabIsInTheSetupOfALaterSession() = runBlocking {
        val machines = di.direct.instance<MachineRepository>()
        val profile = machines.createProfile(MachineProfile.create(name = "Imported")).valueOrNull()!!
        val presenter = di.direct.instance<MachineSettingsArgs, MachineSettingsPresenter>(arg = MachineSettingsArgs(profile.id, tab = false) {})
        withTimeout(5_000) { presenter.uiState.first { state -> state.profile != null } }
        assertEquals(false, presenter.uiState.value.tab)
        presenter.setPrimaryChannel(AxisId.Z, 'v')
        withTimeout(5_000) { machines.profile(profile.id).first { stored -> stored?.z?.primary?.channel == 'v' } }
        presenter.close()

        // A session that starts after the change reads the stored profile.
        val setup = withTimeout(5_000) { di.direct.instance<DeviceSetupSource>().setup(DeviceId(profile.id)).filterNotNull().first() }
        assertEquals('v', setup.machine.z.primary.channel)
    }

    @Test
    fun theGraphGivesEachPresenterOfTheSettingsArea() {
        val direct = di.direct
        listOf(
            direct.instance<AppSettingsPresenter>(),
            direct.instance<MachineProfilesPresenter>(),
            direct.instance<String, DeleteProfilePresenter>(arg = "no-profile"),
            direct.instance<AboutPresenter>(),
            direct.instance<LogsPresenter>(),
            direct.instance<KeyMappingPresenter>(),
            direct.instance<MachineSettingsArgs, MachineSettingsPresenter>(arg = MachineSettingsArgs("no-profile") {}),
        ).forEach { presenter -> presenter.close() }
    }
}
