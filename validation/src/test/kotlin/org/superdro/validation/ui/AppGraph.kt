package org.superdro.validation.ui

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.Properties
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter
import org.superdro.core.di.APP_SCOPE
import org.superdro.core.di.coreDiModule
import org.superdro.core.di.noOpPlatformModule
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.Calibration
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.TachometerMode
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.navigation.NavigationHolder
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.tabs.DeviceTabsPresenter

/**
 * The full DI graph of `core:di` for `App()` on the JVM: the
 * no-op platform module, a SQLite file in a temporary folder, the app files in the same folder, and
 * the fake adapter of `core:fake-adapter` over the [DebugAdapter] seam ([NoDebugAdapter] for a
 * release build when [debug] is false). The sessions are the real
 * sessions of the connection manager on the fake transport, in real time.
 */
class AppGraph(debug: Boolean = true) : AutoCloseable {
    val folder: File = Files.createTempDirectory("superdro-ui-app").toFile()

    @Volatile
    private var closed = false
    private val presenterErrors = Collections.synchronizedList(ArrayList<Throwable>())

    /**
     * Receives the uncaught errors of the presenter scopes. Before [close], it keeps each error, and
     * [close] fails with them. After [close], it ignores them: a presenter can do a database read
     * after the test removed the database, and this error must not go to the next test.
     */
    private val presenterErrorHandler = CoroutineExceptionHandler { _, error -> if (!closed) presenterErrors += error }
    private val driver: SqlDriver = JdbcSqliteDriver(
        "jdbc:sqlite:${File(folder, "superdro.db").absolutePath}",
        Properties().apply { setProperty("foreign_keys", "true") },
    ).also { driver -> SuperDroDatabase.Schema.create(driver) }

    val di: DI = DI {
        import(coreDiModule, allowOverride = true)
        import(noOpPlatformModule, allowOverride = true)
        bindSingleton<SqlDriver> { driver }
        bindProvider<StoreIOProvider>(overrides = true) { StoreIOProviderJvm(File(folder, "files")) }
        bindSingleton<DebugAdapter>(overrides = true) { if (debug) FakeDebugAdapter() else NoDebugAdapter }
        bindSingleton<PresenterContext>(overrides = true) {
            PresenterContext(dispatcher = Dispatchers.Default + presenterErrorHandler, clock = instance())
        }
    }

    val manager: ConnectionManager = di.direct.instance()
    val machines: MachineRepository = di.direct.instance()
    val adapters: AdapterRepository = di.direct.instance()
    val tabStore: TabStore = di.direct.instance()
    val appSettings: AppSettingsStore = di.direct.instance()
    val holder: NavigationHolder = di.direct.instance()

    init {
        runBlocking {
            appSettings.awaitLoaded()
            // The JVM has no notification permission and no battery optimization.
            appSettings.update { settings ->
                settings.copy(notificationPermissionAsked = true, batteryExemptionAsked = true, localNetworkPermissionAsked = true)
            }
        }
    }

    private val scenarios = HashSet<String>()

    /**
     * Stores a machine with an adapter profile whose encoder profiles the user set (no setup
     * reminder), remembers it, and connects it to the fake scenario [scenario]. The fake adapter
     * gives 25400 counts per inch (1 µm for each count).
     *
     * Each machine of one graph must use a different scenario. Each scenario reports its own adapter
     * identity. When two machines report the same identity, the identity rules can show the "new
     * adapter" prompt on one tab. The time of the identity checks decides this. The modal prompt
     * then stops the clicks of a test. For a second machine, use the scenario `commands`.
     */
    fun connect(
        id: String,
        name: String,
        type: MachineType = MachineType.VerticalMill,
        scenario: String = "still",
        configure: (MachineProfile) -> MachineProfile = { it },
    ) = runBlocking {
        require(scenarios.add(scenario)) { "The scenario $scenario has a machine already. Give each machine its own scenario." }
        val encoders = AdapterProfile().encoders.mapValues { (_, encoder) -> encoder.copy(calibration = Calibration(status = CalibrationStatus.UserSet)) }
        val adapter = adapters.saveAdapterProfile(AdapterProfile(alias = "$name adapter", model = "tda-fake", encoders = encoders)).valueOrNull()
        val address = AdapterAddress.Fake(scenario)
        val base = MachineProfile.create(type, name)
        val profile = base.copy(
            id = id,
            adapter = address,
            adapterProfileId = adapter?.id,
            metricDefault = true,
            tachometer = base.tachometer.copy(enabled = type.isLathe, mode = TachometerMode.Preset),
        )
        machines.createProfile(configure(profile)).valueOrNull() ?: error("The profile $id was not stored")
        tabStore.remember(id)
        manager.connect(DeviceRef(DeviceId(id), name, address))
    }

    /**
     * Stops the graph before it removes the temporary folder. First the presenters close: the
     * presenters of all back stacks and the tabs presenter. Then the sessions and the app scope
     * stop. A presenter error that occurred before the close makes the test fail.
     */
    override fun close() {
        closed = true
        holder.retainTabs(emptyList())
        holder.clear()
        holder.presenters.keys.forEach(holder.presenters::close)
        di.direct.instance<DeviceTabsPresenter>().close()
        manager.close()
        di.direct.instance<CoroutineScope>(tag = APP_SCOPE).cancel()
        runBlocking { di.direct.instance<org.superdro.core.store.StoreWriter>().awaitIdle() }
        runCatching { driver.close() }
        folder.deleteRecursively()
        presenterErrors.firstOrNull()?.let { error -> throw AssertionError("A presenter failed while the test ran.", error) }
    }
}
