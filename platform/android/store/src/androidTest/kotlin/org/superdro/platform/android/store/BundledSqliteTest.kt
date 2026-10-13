package org.superdro.platform.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.superdro.core.store.BUNDLED_SQLITE_VERSION
import org.superdro.core.store.checkSqliteVersion
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.isSupportedSqliteVersion
import org.superdro.core.store.sqliteVersion

/**
 * The driver of [AndroidSqlDriverFactory] uses the bundled SQLite 3.53.4, also on API 24, where
 * the SQLite of the device is 3.9.
 */
@RunWith(AndroidJUnit4::class)
class BundledSqliteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theDriverReadsTheBundledSqliteVersion() {
        val driver = AndroidSqlDriverFactory(context, name = null, checkVersion = true).create()
        try {
            val database = createSuperDroDatabase(driver)
            val version = database.sqliteVersion()
            assertEquals("3.53.4", version)
            assertEquals(BUNDLED_SQLITE_VERSION, version)
            assertTrue(isSupportedSqliteVersion(version))
            assertEquals("3.53.4", checkSqliteVersion(database))
        } finally {
            driver.close()
        }
    }

    @Test
    fun theSchemaWorksInAFileWithTheForeignKeysOn() {
        val name = "bundled-sqlite-test.db"
        context.deleteDatabase(name)
        val driver = AndroidSqlDriverFactory(context, name = name).create()
        try {
            val database = createSuperDroDatabase(driver)
            assertEquals(6L, database.probeQueries.selectStandardTips().executeAsList().size.toLong())
            val foreignKeys = driver.executeQuery(null, "PRAGMA foreign_keys", { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0))
            }, 0).value
            assertEquals(1L, foreignKeys)
        } finally {
            driver.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * A damaged file: the library calls `onCorruption`, the factory copies the file to
     * `<files>/corrupt/`, and the default then deletes it and makes a new database.
     */
    @Test
    fun aDamagedFileGoesToTheCorruptFolder() {
        val name = "damaged-test.db"
        context.deleteDatabase(name)
        val corrupt = File(context.filesDir, AndroidSqlDriverFactory.CORRUPT_FOLDER)
        corrupt.deleteRecursively()
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(8192) { index -> (index * 31).toByte() })

        val driver = AndroidSqlDriverFactory(context, name = name).create()
        try {
            val database = createSuperDroDatabase(driver)
            assertEquals(6, database.probeQueries.selectStandardTips().executeAsList().size)
            val copies = corrupt.listFiles().orEmpty().map { it.name }
            assertTrue(copies.any { it.startsWith("$name-") }, copies.toString())
        } finally {
            driver.close()
            context.deleteDatabase(name)
            corrupt.deleteRecursively()
        }
    }
}
