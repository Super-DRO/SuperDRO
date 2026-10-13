package org.superdro.core.ui.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.ProvideDI
import org.superdro.core.ui.adaptive.LocalWindowLayout
import org.superdro.core.ui.adaptive.WindowLayout
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.GraphicsHarness
import org.superdro.core.ui.navigation.GlobalNavigator
import org.superdro.core.ui.navigation.GlobalRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.navigation.appEntryRegistry
import org.superdro.core.ui.presenter.LocalPresenterOwner
import org.superdro.core.ui.presenter.PresenterOwner
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.theme.AppTheme

/** A [TabNavigator] that keeps each push and each pop. */
class RecordingTabNavigator(override val deviceId: DeviceId) : TabNavigator {
    val pushed = mutableListOf<TabRoute>()
    val popped = mutableListOf<TabRoute>()
    override fun push(route: TabRoute) {
        pushed += route
    }
    override fun pop() = Unit
    override fun pop(route: TabRoute) {
        popped += route
    }
    override fun replaceTop(route: TabRoute) {
        pushed += route
    }
    override fun popToRoot() = Unit
}

/** A [GlobalNavigator] that keeps each push and each pop. */
class RecordingGlobalNavigator : GlobalNavigator {
    val pushed = mutableListOf<GlobalRoute>()
    val popped = mutableListOf<GlobalRoute>()
    override fun push(route: GlobalRoute) {
        pushed += route
    }
    override fun pop() = Unit
    override fun pop(route: GlobalRoute) {
        popped += route
    }
    override fun clear() = Unit
}

/**
 * The real entries of [appEntryRegistry] with the stores and the presenters of one machine, for
 * the UI tests of the links between the areas. The functions area and the graphics area get their
 * dependencies from one [GraphicsHarness], so they share the stores and the session.
 */
@OptIn(ExperimentalTestApi::class)
class IntegrationHarness(val scope: TestScope) {
    val graphics = GraphicsHarness(scope)
    val registry = appEntryRegistry()
    val owner = PresenterOwner("tab")
    val entryOwner = PresenterOwner("entry")
    val messages = mutableListOf<UiText>()
    lateinit var services: FunctionServices
    lateinit var navigator: RecordingTabNavigator
    val global = RecordingGlobalNavigator()
    lateinit var tab: TabEntryScope

    /** Stores [machine], starts its session and its readout, and makes the scope of its tab. */
    suspend fun start(machine: MachineProfile) {
        graphics.start(machine)
        val store = graphics.store
        services = FunctionServices(
            machines = store.machines,
            tools = store.tools,
            drawings = graphics.drawings,
            probes = graphics.probes,
            adapters = store.adapters,
            tabStore = store.tabs,
            appSettings = store.appSettings,
            connections = graphics.connections,
            filePicker = graphics.filePicker,
            sounds = graphics.soundController,
            context = graphics.context,
            wallClock = store.wallClock,
            timeZone = TimeZone.UTC,
            appVersion = "1.0.0",
        )
        navigator = RecordingTabNavigator(graphics.deviceId)
        tab = TabEntryScope(graphics.deviceId, navigator, global, owner, { graphics.readout }, { text -> messages += text })
    }

    /** The graph of the entries: the services of the functions area and the dependencies of the graphics area. */
    val di: DI by lazy {
        DI {
            bindSingleton { services }
            bindSingleton<GraphicsDependencies> { graphics.deps }
        }
    }

    /** Runs the work of the store and the presenters on the test scheduler. */
    fun run() = scope.testScheduler.advanceUntilIdle()

    /** Runs [block] on the test scheduler and gives its result. A blocking read would wait for ever. */
    fun <T> read(block: suspend () -> T): T {
        var result: Result<T>? = null
        scope.launch { result = runCatching { block() } }
        run()
        return (result ?: error("The block did not end.")).getOrThrow()
    }

    /** Gives frames to Compose and time to the presenters, a few times in turn. */
    fun settle(test: ComposeUiTest) {
        repeat(SETTLE_ROUNDS) {
            run()
            test.waitForIdle()
        }
    }

    /** The registered entry of [route] in the tab, in the light theme of a landscape tablet. */
    @Composable
    fun Entry(route: TabRoute) {
        val spec = registry.spec(route) ?: error("No entry for $route.")
        ProvideDI(di) {
            AppTheme(mode = ThemeMode.System, systemInDarkTheme = false) {
                CompositionLocalProvider(
                    LocalWindowLayout provides WindowLayout.of(1280.dp, 800.dp),
                    LocalPresenterOwner provides entryOwner,
                ) {
                    spec.content(tab, route)
                }
            }
        }
    }

    fun close() {
        entryOwner.close()
        owner.close()
        graphics.close()
    }

    private companion object {
        const val SETTLE_ROUNDS = 4
    }
}
