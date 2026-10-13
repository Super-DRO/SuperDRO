package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.toxicbakery.logging.Arbor
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.ui.settings.logs.LogFilter
import org.superdro.core.ui.settings.logs.LogsPresenter
import org.superdro.core.ui.settings.logs.RecordedStreams
import org.superdro.core.ui.settings.logs.SessionLogSink

/** The graph gives the log sink in the Arbor forest, the copy of the recorded streams, and the presenter of the log screen. */
class SettingsLogsGraphTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-logs").toFile()

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
    fun theSinkIsInTheForestAndGetsTheSessionLines() {
        val sink = di.direct.instance<SessionLogSink>()
        assertTrue(sink in Arbor.forest)
        Arbor.tag(SessionLogSink.SESSION_TAG).i { "graph-mill: link 1 open" }
        assertEquals(listOf("link 1 open"), sink.lines(LogFilter.Machine(DeviceId("graph-mill"))).map { it.text })
    }

    @Test
    fun theGraphGivesOneCopyOfTheRecordsAndANewPresenterForEachEntry() {
        val direct = di.direct
        assertSame(direct.instance<RecordedStreams>(), direct.instance<RecordedStreams>())
        val first = direct.instance<LogsPresenter>()
        val second = direct.instance<LogsPresenter>()
        assertTrue(first !== second)
        first.close()
        second.close()
    }
}
