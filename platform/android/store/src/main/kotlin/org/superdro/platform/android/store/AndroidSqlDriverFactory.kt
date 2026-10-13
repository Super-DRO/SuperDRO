package org.superdro.platform.android.store

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.osmerion.android.database.sqlite.OsmerionSQLiteOpenHelperFactory
import com.toxicbakery.logging.Arbor
import java.io.File
import org.superdro.core.store.checkSqliteVersion
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.db.SuperDroDatabase

/**
 * Makes the Android `SqlDriver` of SuperDRO.
 *
 * The driver uses the bundled SQLite 3.53.4 of `com.osmerion.sqlite.android:sqlite-android`
 * 0.14.0, not the SQLite of the device. API 24 has SQLite 3.9, and the dialect of
 * `core:store` is `sqlite-3-38-dialect`.
 *
 * - The callback turns on the foreign keys in `onOpen`, after the migrations and outside their
 *   transaction. Without them a delete does not remove the owned rows.
 * - `onCorruption` copies the damaged file (and its `-journal`, `-wal`, and `-shm` files) to
 *   `<files>/corrupt/` before the default deletes it.
 * - A debuggable app reads `SELECT sqlite_version()` after the open and stops when the value is
 *   lower than 3.38.0. [checkVersion] can force the check.
 *
 * @param name The file name of the database. Null gives a database in memory (tests).
 * @param checkVersion True runs the version check. The default is true for a debuggable app.
 */
class AndroidSqlDriverFactory(
    private val context: Context,
    private val name: String? = DATABASE_NAME,
    private val checkVersion: Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
) {
    /** Makes the driver. Make one driver for the process. */
    fun create(): SqlDriver {
        val driver = AndroidSqliteDriver(
            schema = SuperDroDatabase.Schema,
            context = context,
            name = name,
            factory = OsmerionSQLiteOpenHelperFactory(),
            callback = object : AndroidSqliteDriver.Callback(SuperDroDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    log.e { "The database ${db.path} is damaged. A copy goes to the folder corrupt." }
                    db.path?.let { path -> saveDamagedFile(File(path)) }
                    super.onCorruption(db)
                }
            },
        )
        if (checkVersion) {
            val version = checkSqliteVersion(createSuperDroDatabase(driver))
            log.i { "SQLite $version" }
        }
        return driver
    }

    /**
     * Copies [database] and its side files to `<files>/corrupt/<name>-<ms>`. A failure goes to
     * the log, because the default delete must still occur.
     *
     * @return The copies that the function made.
     */
    internal fun saveDamagedFile(database: File, nowMs: Long = System.currentTimeMillis()): List<File> {
        val folder = File(context.filesDir, CORRUPT_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) {
            log.e { "Cannot make ${folder.absolutePath}" }
            return emptyList()
        }
        return listOf("", "-journal", "-wal", "-shm").mapNotNull { suffix ->
            val source = File(database.path + suffix)
            if (!source.isFile) return@mapNotNull null
            try {
                source.copyTo(File(folder, "${database.name}-$nowMs$suffix"), overwrite = true)
            } catch (error: Exception) {
                log.e(error) { "Cannot copy ${source.absolutePath}" }
                null
            }
        }
    }

    companion object {
        const val DATABASE_NAME: String = "superdro.db"

        /** The folder in the app files for the copies of a damaged database. */
        const val CORRUPT_FOLDER: String = "corrupt"

        private val log = Arbor.tag("AndroidSqlDriverFactory")
    }
}
