package org.superdro.platform.desktop.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.toxicbakery.logging.Arbor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteException
import org.superdro.core.store.checkSqliteVersion
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase

/**
 * Makes the desktop `SqlDriver` of SuperDRO: a `JdbcSqliteDriver` on [file] with the SQLite 3.53.4
 * of `org.xerial:sqlite-jdbc`. The Android app bundles the same SQLite version.
 *
 * - The foreign keys are on. Without them a delete does not remove the
 *   owned rows.
 * - The driver makes the schema in a new file, and runs the migrations of an older file with
 *   `PRAGMA user_version`.
 * - After the open, the factory reads `SELECT sqlite_version()` and stops when the value is lower
 *   than 3.38.0.
 * - When SQLite cannot read the file at the open (`SQLITE_CORRUPT` or `SQLITE_NOTADB`), the file
 *   and its `-journal`, `-wal`, and `-shm` files move to the folder `corrupt` beside the database.
 *   Then the factory makes a new database. The Android factory does the same in `onCorruption`.
 *   The desktop factory finds a damaged file only at the open.
 *
 * @param file The database file.
 * @param nowMs Gives the wall clock time for the names of the copies.
 */
class DesktopSqlDriverFactory(
    private val file: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val url: String get() = "jdbc:sqlite:${file.absolutePath}"

    /** Makes the driver. Make one driver for the process. */
    fun create(): SqlDriver {
        file.absoluteFile.parentFile?.mkdirs()
        if (isDamaged()) {
            log.e { "The database ${file.absolutePath} is damaged. It moves to the folder $CORRUPT_FOLDER." }
            moveDamagedFile()
        }
        val driver = JdbcSqliteDriver(url, properties(), SuperDroDatabase.Schema)
        try {
            val version = checkSqliteVersion(createSuperDroDatabase(driver))
            log.i { "SQLite $version, ${file.absolutePath}" }
        } catch (error: Exception) {
            driver.close()
            throw error
        }
        return driver
    }

    /** True when the file is there and SQLite reports it as damaged or as not a database. */
    internal fun isDamaged(): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            // A read-only connection does not change the side files of a damaged database.
            DriverManager.getConnection(url, SQLiteConfig().apply { setReadOnly(true) }.toProperties()).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM sqlite_schema").use { result -> result.next() }
                }
            }
            false
        } catch (error: SQLException) {
            val damaged = isCorruption(error)
            if (!damaged) log.w(error) { "The check of ${file.absolutePath} failed" }
            damaged
        }
    }

    /**
     * Moves [file] and its side files to `<folder>/corrupt/<name>-<ms><suffix>`.
     *
     * @return The files in the folder `corrupt`.
     */
    internal fun moveDamagedFile(): List<File> {
        val folder = File(file.absoluteFile.parentFile, CORRUPT_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) {
            log.e { "Cannot make ${folder.absolutePath}" }
            return emptyList()
        }
        val stamp = nowMs()
        return SIDE_SUFFIXES.mapNotNull { suffix ->
            val source = File(file.absolutePath + suffix)
            if (!source.isFile) return@mapNotNull null
            val target = File(folder, "${file.name}-$stamp$suffix")
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (error: Exception) {
                log.e(error) { "Cannot move ${source.absolutePath}" }
                null
            }
        }
    }

    companion object {
        /** The folder beside the database for the damaged files. The Android app uses the same name. */
        const val CORRUPT_FOLDER: String = "corrupt"

        /** The primary result codes `SQLITE_CORRUPT` and `SQLITE_NOTADB`. */
        private val CORRUPTION_CODES = setOf(11, 26)

        private val SIDE_SUFFIXES = listOf("", "-journal", "-wal", "-shm")

        private val log = Arbor.tag("DesktopSqlDriverFactory")

        /** The JDBC properties: the foreign keys are on. */
        fun properties(): Properties = Properties().apply { setProperty("foreign_keys", "true") }

        /** True when [error] or one of its causes is `SQLITE_CORRUPT` or `SQLITE_NOTADB`. */
        internal fun isCorruption(error: Throwable): Boolean =
            generateSequence(error) { cause -> cause.cause }.any { cause ->
                (cause as? SQLiteException)?.resultCode?.code?.let { code -> (code and 0xFF) in CORRUPTION_CODES } == true ||
                    (cause as? SQLException)?.errorCode?.let { code -> (code and 0xFF) in CORRUPTION_CODES } == true
            }
    }
}
