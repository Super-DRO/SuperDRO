package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.MeasurementLogRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository

/**
 * Binds the `SqlDriver` of the target, when common code can make it. The JVM module and the
 * Android module are empty. Each app gives the `SqlDriver` from its platform module
 * (`platformModule` on Android). The JVM tests give a `JdbcSqliteDriver`.
 */
expect val sqlDriverModule: DI.Module

/**
 * Binds [SuperDroDatabase] on the one `SqlDriver` of the graph,
 * the one [StoreWriter] of the process, and the repositories on
 * that writer.
 */
val databaseModule = DI.Module("databaseModule") {
    bindSingleton<SuperDroDatabase> { createSuperDroDatabase(driver = instance()) }
    bindSingleton { StoreWriter(database = instance()) }
    bindSingleton { MachineRepository(writer = instance(), storeIO = instance(), json = instance()) }
    bindSingleton { AdapterRepository(writer = instance(), storeIO = instance(), json = instance()) }
    bindSingleton { ToolRepository(writer = instance(), json = instance()) }
    bindSingleton { ProbeRepository(writer = instance(), json = instance()) }
    bindSingleton { DrawingRepository(writer = instance()) }
    bindSingleton { MeasurementLogRepository(writer = instance(), json = instance()) }
    bindSingleton { CameraRepository(writer = instance()) }
}
