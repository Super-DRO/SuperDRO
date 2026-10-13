package org.superdro.core.store.json

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.store.FakeStoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm
import org.superdro.core.store.TestWallClock

/** The JVM SecretStore for the tests and the desktop. It does not encrypt. */
class PlainFileSecretStoreTest {

    private val directory: File = Files.createTempDirectory("superdro-secrets").toFile()

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun aSecretGoesInAndOutAndStaysAfterARestart() {
        val io = StoreIOProviderJvm(directory)
        val store = PlainFileSecretStore(io)
        assertNull(store.get("mqtt/password"))
        store.put("mqtt/password", "hunter2".encodeToByteArray())
        store.put("output/psk", ByteArray(32) { it.toByte() })
        assertContentEquals("hunter2".encodeToByteArray(), store.get("mqtt/password"))

        // A new store object reads the file: the values stay after a restart.
        val again = PlainFileSecretStore(io)
        assertContentEquals(ByteArray(32) { it.toByte() }, again.get("output/psk"))
        again.delete("output/psk")
        again.delete("missing")
        assertNull(PlainFileSecretStore(io).get("output/psk"))
        assertTrue(File(directory, "secrets.json").readText().startsWith("""{"schemaVersion":1,"scheme":"none","secrets":"""))
        assertFalse(File(directory, "secrets.json.tmp").exists())
    }

    @Test
    fun aCopyOfTheCallerArrayDoesNotChangeTheSecret() {
        val store = PlainFileSecretStore(FakeStoreIOProvider())
        val bytes = byteArrayOf(1, 2, 3)
        store.put("a", bytes)
        bytes[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("a"))
        store.get("a")!![1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("a"))
    }

    @Test
    fun aFileThatDoesNotDecodeMovesAside() {
        val io = FakeStoreIOProvider()
        io.putText("secrets.json", "{broken")
        val store = PlainFileSecretStore(io, clock = TestWallClock())
        assertNull(store.get("a"))
        assertEquals("{broken", io.text("secrets.json.corrupt-1791792000000"))
        store.put("a", byteArrayOf(5))
        assertContentEquals(byteArrayOf(5), PlainFileSecretStore(io).get("a"))
    }
}
