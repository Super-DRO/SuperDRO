package org.superdro.core.di

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.toDeviceRef
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.fakeadapter.FakeDebugAdapter
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI

/**
 * [App] with the full common graph, the real connection manager, and the fake adapter of a debug
 * build. The demo entry connects the two fake machines:
 * each one gets a tab, and the readout of the selected tab shows live values.
 */
@OptIn(ExperimentalTestApi::class)
class AppGraphUiTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-app").toFile()

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
        di.direct.instance<ConnectionManager>().close()
        di.direct.instance<SqlDriver>().close()
        directory.deleteRecursively()
    }

    @Test
    fun theDemoMachinesShowLiveValuesInTwoTabs() = runComposeUiTest {
        setContent { ProvideDI(di) { App() } }
        onNodeWithText("No machine is online").assertExists()

        // The demo entry of MainActivity connects the demo links.
        val manager = di.direct.instance<ConnectionManager>()
        di.direct.instance<DebugAdapter>().demoLinks.forEach { link -> manager.connect(link.toDeviceRef()) }

        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithText("Demo mill").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithText("Demo lathe").fetchSemanticsNodes().isNotEmpty()
        }
        val first = waitForValue()
        // The hole pattern of the demo mill moves X and Y: the value changes.
        waitUntil(timeoutMillis = 10_000) { xValue() != null && xValue() != first }
        assertNotEquals(first, xValue())
    }

    /** Waits for the semantic label of the X value of the readout ("X axis, … millimeters"). */
    private fun androidx.compose.ui.test.ComposeUiTest.waitForValue(): String {
        waitUntil(timeoutMillis = 20_000) { xValue() != null }
        return xValue()!!
    }

    private fun androidx.compose.ui.test.ComposeUiTest.xValue(): String? =
        onAllNodes(androidx.compose.ui.test.hasContentDescription("X axis,", substring = true))
            .fetchSemanticsNodes()
            .firstNotNullOfOrNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            ?.takeIf { text -> text.any(Char::isDigit) }
            .also { text -> if (text != null) assertTrue(text.contains("millimeters") || text.contains("inches"), text) }
}
