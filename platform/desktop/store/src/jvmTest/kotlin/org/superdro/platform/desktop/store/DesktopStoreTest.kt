package org.superdro.platform.desktop.store

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.MachineProfile
import org.superdro.core.store.StoreWriter
import org.superdro.core.store.createSuperDroDatabase
import org.superdro.core.store.json.SealedSecret
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.store.sqliteVersion
import org.superdro.core.store.valueOrNull

/** The files of the desktop app: the data directory, the database file, and the secret store. */
class DesktopStoreTest {

    private val root: File = Files.createTempDirectory("superdro-desktop-store").toFile()
    private val dataDirectory = File(root, "data")

    @AfterTest
    fun deleteRoot() {
        root.deleteRecursively()
    }

    @Test
    fun theStoreMakesItsDirectoryForTheOwnerOnly() {
        val store = DesktopStore(dataDirectory)
        assertEquals(dataDirectory.absoluteFile, store.directory)
        assertTrue(store.directory.isDirectory)
        assertEquals(File(dataDirectory, "superdro.db").absoluteFile, store.databaseFile)
        assertEquals(File(dataDirectory, "settings.json").absolutePath, store.storeIO.pathFor("settings.json"))
        if (DesktopFiles.posix) {
            assertEquals(DesktopFiles.OWNER_ONLY_DIRECTORY, Files.getPosixFilePermissions(store.directory.toPath()))
        }
    }

    @Test
    fun aDirectoryThatIsThereKeepsItsPermissions() {
        dataDirectory.mkdirs()
        val before = if (DesktopFiles.posix) Files.getPosixFilePermissions(dataDirectory.toPath()) else null
        DesktopStore(dataDirectory)
        if (before != null) assertEquals(before, Files.getPosixFilePermissions(dataDirectory.toPath()))
    }

    @Test
    fun theDatabaseFileKeepsTheOffsetsAfterARestart() = runBlocking {
        val store = DesktopStore(dataDirectory)
        val first = store.openDriver()
        val firstDatabase = createSuperDroDatabase(first)
        assertEquals("3.53.4", firstDatabase.sqliteVersion())
        val machines = MachineRepository(StoreWriter(firstDatabase), store.storeIO)
        val profile = machines.createProfile(MachineProfile.create(name = "Mill")).valueOrNull()!!
        machines.setAxisState(profile.activeWorkspaceId, AxisId.X, AxisState(absoluteOffset = -25400.0, metric = true))
        first.close()
        assertTrue(store.databaseFile.isFile)

        // A second start of the app opens the same file.
        val second = DesktopStore(dataDirectory).openDriver()
        val again = MachineRepository(StoreWriter(createSuperDroDatabase(second)), store.storeIO)
        assertEquals("Mill", again.loadProfile(profile.id)?.name)
        assertEquals(AxisState(absoluteOffset = -25400.0, metric = true), again.loadWorkspace(profile.activeWorkspaceId)!!.axisStates.getValue(AxisId.X))
        second.close()
    }

    @Test
    fun aDamagedDatabaseMovesToTheCorruptFolderAndANewOneOpens() {
        dataDirectory.mkdirs()
        val file = File(dataDirectory, "superdro.db")
        file.writeText("This is not a SQLite file. ".repeat(200))
        File(dataDirectory, "superdro.db-wal").writeText("wal")
        val factory = DesktopSqlDriverFactory(file, nowMs = { 1_760_000_000_000L })
        assertTrue(factory.isDamaged())

        val driver = factory.create()
        assertEquals("3.53.4", createSuperDroDatabase(driver).sqliteVersion())
        driver.close()
        val corrupt = File(dataDirectory, "corrupt")
        // The check opens the file read-only. SQLite can then add a -shm file, which also moves.
        val moved = corrupt.list()!!.toSet()
        assertTrue(moved.containsAll(setOf("superdro.db-1760000000000", "superdro.db-1760000000000-wal")), moved.toString())
        assertTrue(moved.all { name -> name.startsWith("superdro.db-1760000000000") }, moved.toString())
        assertEquals("wal", File(corrupt, "superdro.db-1760000000000-wal").readText())
        assertTrue(File(corrupt, "superdro.db-1760000000000").readText().startsWith("This is not a SQLite file."))
        assertFalse(DesktopSqlDriverFactory(file).isDamaged())
    }

    @Test
    fun aNewOrAGoodFileIsNotDamaged() {
        val file = File(dataDirectory, "superdro.db")
        assertFalse(DesktopSqlDriverFactory(file).isDamaged())
        DesktopSqlDriverFactory(file).create().close()
        assertFalse(DesktopSqlDriverFactory(file).isDamaged())
        assertFalse(File(dataDirectory, "corrupt").exists())
    }

    @Test
    fun theSecretStoreEncryptsEachValueWithAKeyFileForTheOwnerOnly() {
        val store = DesktopStore(dataDirectory)
        val secrets = store.secretStore()
        val psk = ByteArray(32) { index -> index.toByte() }
        secrets.put("data_output_psk", psk)
        assertContentEquals(psk, secrets.get("data_output_psk"))

        val keyFile = File(store.directory, DesktopSecretStore.KEY_FILE_NAME)
        assertEquals(32L, keyFile.length())
        if (DesktopFiles.posix) assertEquals(DesktopFiles.OWNER_ONLY_FILE, Files.getPosixFilePermissions(keyFile.toPath()))
        // The file has the scheme and no plain value.
        val text = File(store.directory, "secrets.json").readText()
        assertTrue(FileKeySecretCipher.SCHEME in text, text)
        assertFalse(java.util.Base64.getEncoder().encodeToString(psk) in text)

        // A new start of the app reads the secret with the same key file.
        val again = DesktopStore(dataDirectory).secretStore()
        assertContentEquals(psk, again.get("data_output_psk"))
        again.delete("data_output_psk")
        assertNull(DesktopStore(dataDirectory).secretStore().get("data_output_psk"))
    }

    @Test
    fun aValueOpensOnlyWithItsNameAndItsKey() {
        val cipher = FileKeySecretCipher(File(dataDirectory, "secrets.key"))
        val sealed = cipher.seal("mqtt_password", "secret".encodeToByteArray())
        assertEquals(12, sealed.iv.size)
        assertContentEquals("secret".encodeToByteArray(), cipher.open("mqtt_password", sealed))
        assertTrue(runCatching { cipher.open("data_output_psk", sealed) }.isFailure)
        val second = cipher.seal("mqtt_password", "secret".encodeToByteArray())
        assertNotEquals(sealed.iv.toList(), second.iv.toList())

        // A different key file does not open the value.
        val other = FileKeySecretCipher(File(dataDirectory, "other.key"))
        assertTrue(runCatching { other.open("mqtt_password", SealedSecret(sealed.iv, sealed.data)) }.isFailure)
    }

    @Test
    fun aDamagedKeyFileMovesAsideAndTheOldValuesDoNotOpen() {
        val store = DesktopStore(dataDirectory)
        store.secretStore().put("mqtt_password", "secret".encodeToByteArray())
        val keyFile = File(store.directory, DesktopSecretStore.KEY_FILE_NAME)
        keyFile.writeBytes(ByteArray(5))

        val after = DesktopStore(dataDirectory).secretStore()
        assertNull(after.get("mqtt_password"))
        after.put("mqtt_password", "new".encodeToByteArray())
        assertContentEquals("new".encodeToByteArray(), after.get("mqtt_password"))
        assertEquals(32L, keyFile.length())
        assertTrue(store.directory.list()!!.any { name -> name.startsWith("secrets.key.damaged-") })
    }
}
