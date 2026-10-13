package org.superdro.core.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.store.db.SuperDroDatabase

/**
 * Schema version 1 is the first schema. `1.db` holds version 1,
 * and each schema change adds one `.sqm` migration. `verifySqlDelightMigration` and this test
 * check that `1.db` with the migrations gives the current schema.
 */
class SchemaFileTest {

    private val directory: File = Files.createTempDirectory("superdro-schema").toFile()

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    private fun SqlDriver.objects(): Set<String> = executeQuery(
        null,
        "SELECT type || ':' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY 1",
        { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) names += cursor.getString(0)!!
            QueryResult.Value(names)
        },
        0,
    ).value

    private fun SqlDriver.foreignKeyProblems(): Int = executeQuery(null, "PRAGMA foreign_key_check", { cursor ->
        var count = 0
        while (cursor.next().value) count++
        QueryResult.Value(count)
    }, 0).value

    @Test
    fun theSchemaFile1WithTheMigrationsGivesTheCurrentSchema() {
        // 1.sqm adds the comments of the error map.
        assertEquals(2L, SuperDroDatabase.Schema.version)
        val copy = File(directory, "1.db")
        File("src/commonMain/sqldelight/databases/1.db").copyTo(copy)
        val fromFile = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}", jdbcProperties())
        val fresh = inMemoryDriver()
        try {
            // The migration from 1 to the current version runs each step, and the data stays valid.
            SuperDroDatabase.Schema.migrate(fromFile, 1, SuperDroDatabase.Schema.version)
            assertEquals(0, fromFile.foreignKeyProblems())
            val expected = fresh.objects()
            assertTrue(expected.size > 40, expected.toString())
            assertEquals(expected, fromFile.objects())
        } finally {
            fromFile.close()
            fresh.close()
        }
    }

    @Test
    fun anEncoderRowOfVersion1GetsAnEmptyCommentListAtTheMigration() {
        val copy = File(directory, "1.db")
        File("src/commonMain/sqldelight/databases/1.db").copyTo(copy)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}", jdbcProperties())
        try {
            driver.execute(
                null,
                "INSERT INTO adapter_profile (id, alias, legacy, declared_inputs_json, probe_contact_type, probe_debounce_ms, created_on) " +
                    "VALUES ('a1', 'Mill adapter', 0, '[]', 'Auto', 200, '2026-10-12T08:00:00Z')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO encoder_profile (adapter_profile_id, input, type, kind, resolution, reversed, spike_filter, backlash_mode, " +
                    "backlash_counts, error_map_cyclic, calibration_json) VALUES ('a1', 'x', 'Scale', 'Linear', 25400.0, 0, 0, 'None', 0, 1, '{}')",
                0,
            )
            SuperDroDatabase.Schema.migrate(driver, 1, SuperDroDatabase.Schema.version)
            val comments = driver.executeQuery(
                null,
                "SELECT error_map_comments_json FROM encoder_profile WHERE adapter_profile_id = 'a1'",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                0,
            ).value
            assertEquals("[]", comments)
        } finally {
            driver.close()
        }
    }
}
