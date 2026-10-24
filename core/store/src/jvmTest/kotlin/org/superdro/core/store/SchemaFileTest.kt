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
        // 1.sqm adds the comments of the error map. 2.sqm writes the adapter identities in lower
        // case. 3.sqm adds the audit log of the MCP server.
        assertEquals(4L, SuperDroDatabase.Schema.version)
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

    @Test
    fun theMigrationWritesEachAdapterIdentityInLowerCase() {
        val copy = File(directory, "1.db")
        File("src/commonMain/sqldelight/databases/1.db").copyTo(copy)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}", jdbcProperties())
        try {
            listOf(
                "'a1', 'Imported', ' AA:BB:CC:DD:EE:FF ', 'BtMac'",
                "'a2', 'Live', 'aa:bb:cc:dd:ee:ff', 'BtMac'",
                "'a3', 'Reported', '3F2B9C1E-7D4A-4C55-9A51-2F0D8E6B1A77', 'AdapterReported'",
                "'a4', 'Blank', '  ', 'BtMac'",
                "'a5', 'None', NULL, NULL",
            ).forEach { values ->
                driver.execute(
                    null,
                    "INSERT INTO adapter_profile (id, alias, identity, identity_source, legacy, declared_inputs_json, probe_contact_type, " +
                        "probe_debounce_ms, created_on) VALUES ($values, 0, '[]', 'Auto', 200, '2026-10-12T08:00:00Z')",
                    0,
                )
            }
            SuperDroDatabase.Schema.migrate(driver, 1, SuperDroDatabase.Schema.version)
            val identities = driver.executeQuery(
                null,
                "SELECT id, identity FROM adapter_profile ORDER BY id",
                { cursor ->
                    val rows = mutableListOf<Pair<String, String?>>()
                    while (cursor.next().value) rows += cursor.getString(0)!! to cursor.getString(1)
                    QueryResult.Value(rows)
                },
                0,
            ).value
            assertEquals(
                listOf(
                    "a1" to "aa:bb:cc:dd:ee:ff",
                    "a2" to "aa:bb:cc:dd:ee:ff",
                    "a3" to "3f2b9c1e-7d4a-4c55-9a51-2f0d8e6b1a77",
                    "a4" to null,
                    "a5" to null,
                ),
                identities,
            )
        } finally {
            driver.close()
        }
    }
}
