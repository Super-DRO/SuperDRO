package org.superdro.core.store

import app.cash.sqldelight.db.QueryResult
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.store.db.SuperDroDatabase

/**
 * The JVM SQLite is 3.53.4 (xerial `sqlite-jdbc` 3.53.4.0 through SQLDelight `sqlite-driver`
 * 2.4.1). The same version is in the APK (`sqlite-android` 0.14.0).
 */
class SqliteVersionJvmTest {

    private val driver = inMemoryDriver()
    private val database = createSuperDroDatabase(driver)

    @AfterTest
    fun closeDriver() {
        driver.close()
    }

    @Test
    fun theJvmSqliteIs3_53_4() {
        val version = database.sqliteVersion()
        assertEquals("3.53.4", version)
        assertEquals(BUNDLED_SQLITE_VERSION, version)
        assertTrue(isSupportedSqliteVersion(version))
        assertEquals("3.53.4", checkSqliteVersion(database))
    }

    @Test
    fun theSchemaHasAllTablesAndTheForeignKeysAreOn() {
        val tables = SuperDroDatabase.allTableNames().toSet()
        val expected = setOf(
            "machine_profile", "workspace", "axis_state", "sub_datum", "sub_datum_tag", "guide", "machine_datum",
            "thread_spec", "tool", "adapter_profile", "encoder_profile", "encoder_map_point", "probe_profile",
            "drawing", "drawing_layer", "drawing_shape", "measurement_log", "camera_mode_result", "camera_selection",
        )
        assertTrue(tables.containsAll(expected), (expected - tables).toString())
        val foreignKeys = driver.executeQuery(null, "PRAGMA foreign_keys", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        }, 0).value
        assertEquals(1L, foreignKeys)
    }

    /**
     * WARNING: the JVM SQLite has the SQL math functions and
     * `percentile`, and the Android SQLite does not. A query that uses one passes on the JVM and
     * fails on the tablet. This test finds such a name in a `.sq` file.
     */
    @Test
    fun noSqFileUsesAMathFunction() {
        val forbidden = listOf(
            "acos", "acosh", "asin", "asinh", "atan", "atan2", "atanh", "ceil", "ceiling", "cos", "cosh", "degrees",
            "exp", "floor", "ln", "log", "log10", "log2", "mod", "pi", "pow", "power", "radians", "sin", "sinh",
            "sqrt", "tan", "tanh", "trunc", "percentile", "percentile_cont", "percentile_disc", "median",
        )
        val files = File("src/commonMain/sqldelight").walkTopDown().filter { file -> file.extension in setOf("sq", "sqm") }.toList()
        assertTrue(files.size >= 10, "The test finds the .sq files: ${files.size}")
        val pattern = Regex("""\b(${forbidden.joinToString("|")})\s*\(""", RegexOption.IGNORE_CASE)
        files.forEach { file ->
            val code = file.readLines().filterNot { line -> line.trimStart().startsWith("--") }.joinToString("\n")
            assertEquals(null, pattern.find(code)?.value, file.name)
        }
    }
}
