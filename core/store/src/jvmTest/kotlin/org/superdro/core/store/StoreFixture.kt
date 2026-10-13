package org.superdro.core.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.MeasurementLogRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository

/** The JDBC properties of the tests: the foreign keys are on. */
fun jdbcProperties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

/** A new database in memory with the current schema. */
fun inMemoryDriver(): JdbcSqliteDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, jdbcProperties()).also { driver -> SuperDroDatabase.Schema.create(driver) }

/**
 * The repositories on one database in memory. All work runs on one test dispatcher of [scope],
 * so the tests use virtual time and `advanceUntilIdle`.
 */
class StoreFixture(scope: TestScope, val driver: SqlDriver = inMemoryDriver()) {
    val dispatcher = StandardTestDispatcher(scope.testScheduler)
    val database: SuperDroDatabase = createSuperDroDatabase(driver)
    val writer = StoreWriter(database, dispatcher = dispatcher, readDispatcher = dispatcher)
    val storeIO = FakeStoreIOProvider()
    val clock = TestWallClock()
    val json = SuperDroJson

    val machines = MachineRepository(writer, storeIO, json, clock)
    val tools = ToolRepository(writer, json, clock)
    val adapters = AdapterRepository(writer, storeIO, json, clock, fileDispatcher = dispatcher)
    val probes = ProbeRepository(writer, json, clock)
    val drawings = DrawingRepository(writer)
    val logs = MeasurementLogRepository(writer, json, clock)
    val cameras = CameraRepository(writer)

    /** The number of rows of [table]. */
    fun count(table: String): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM $table",
        mapper = { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L) },
        parameters = 0,
    ).value ?: 0L

    fun close() = driver.close()
}
