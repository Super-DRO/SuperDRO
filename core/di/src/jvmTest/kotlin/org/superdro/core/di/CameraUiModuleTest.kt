package org.superdro.core.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.camera.FakeUvcCameraBackend
import org.superdro.core.camera.UvcCameraBackend
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.ui.camera.CameraSetupGate
import org.superdro.core.ui.camera.CameraUi

/** The common graph binds the camera UI on the camera back end of the platform. */
class CameraUiModuleTest {

    private val directory = kotlin.io.path.createTempDirectory("superdro-camera-di").toFile()

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
    fun theGraphGivesOneCameraUiWithTheBackEndOfThePlatform() {
        val ui = di.direct.instance<CameraUi>()
        assertSame(ui, di.direct.instance<CameraUi>())
        assertSame(di.direct.instance<UvcCameraBackend>(), ui.backend)
        assertEquals(FakeUvcCameraBackend::class, ui.backend::class)
        assertSame(di.direct.instance<CameraSetupGate>(), ui.gate)
        ui.video("mill").close()
        ui.setup("mill").close()
    }
}
