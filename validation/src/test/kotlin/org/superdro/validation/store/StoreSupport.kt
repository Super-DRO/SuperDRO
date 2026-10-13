package org.superdro.validation.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.TimeZone
import org.superdro.core.readout.expression.ExpressionEvaluator
import org.superdro.core.readout.expression.ExpressionResult
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.repository.AdapterRepository
import org.superdro.core.store.repository.CameraRepository
import org.superdro.core.store.repository.DrawingRepository
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.repository.MeasurementLogRepository
import org.superdro.core.store.repository.ProbeRepository
import org.superdro.core.store.repository.ToolRepository
import org.superdro.validation.Traceability

/**
 * The test parts of the store validation: an in-memory SQLite database (`JdbcSqliteDriver.IN_MEMORY`)
 * with the foreign keys on, the repositories on one writer dispatcher, and a store folder in a
 * temporary directory.
 */
internal class StoreHarness(dispatcher: CoroutineDispatcher) {
    val driver: SqlDriver = JdbcSqliteDriver(
        JdbcSqliteDriver.IN_MEMORY,
        Properties().apply { setProperty("foreign_keys", "true") },
    ).also { driver -> SuperDroDatabase.Schema.create(driver) }
    val database: SuperDroDatabase = createSuperDroDatabase(driver)
    val folder: File = Files.createTempDirectory("superdro-store-validation").toFile().also { it.deleteOnExit() }
    val io = StoreIOProviderJvm(folder)
    val writer = StoreWriter(database, dispatcher, dispatcher)
    val machines = MachineRepository(writer, io)
    val tools = ToolRepository(writer)
    val probes = ProbeRepository(writer)
    val cameras = CameraRepository(writer)
    val adapters = AdapterRepository(writer, io, fileDispatcher = dispatcher)
    val drawings = DrawingRepository(writer)
    val logs = MeasurementLogRepository(writer)

    /** Gives the text values of one column of a query, through the driver (no generated query). */
    fun texts(sql: String): List<String?> = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val values = mutableListOf<String?>()
            while (cursor.next().value) values += cursor.getString(0)
            QueryResult.Value(values)
        },
        parameters = 0,
    ).value

    /** Gives the rows of `PRAGMA table_info(<table>)` as pairs of the column name and the declared type. */
    fun columns(table: String): List<Pair<String, String>> = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            val values = mutableListOf<Pair<String, String>>()
            while (cursor.next().value) values += cursor.getString(1)!! to (cursor.getString(2) ?: "")
            QueryResult.Value(values)
        },
        parameters = 0,
    ).value

    fun close() {
        driver.close()
        folder.deleteRecursively()
    }
}

/** The fixtures and the common values of the store validation. */
internal object StoreFixtures {

    /** Reads a fixture file of `src/test/resources/store`. */
    fun text(name: String): String =
        checkNotNull(StoreFixtures::class.java.getResource("/store/$name")) { "No fixture $name." }.readText()

    /** The expression evaluator, as the number reader of a CSV import. */
    val f9: (String) -> Double? = { text -> (ExpressionEvaluator.evaluate(text) as? ExpressionResult.Value)?.value }

    /** 2026-10-11 14:30:05 UTC, the export time of the examples. */
    val exportTime: Instant = Instant.parse("2026-10-11T14:30:05Z")

    val exportContext = ExportContext(appVersion = "1.0.0", exportedAt = exportTime, timeZone = TimeZone.UTC)

    /** The form of a UUID text. */
    val UUID_FORM = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")

    /** The root of the repository. */
    val root: File get() = Traceability.rootDir
}
