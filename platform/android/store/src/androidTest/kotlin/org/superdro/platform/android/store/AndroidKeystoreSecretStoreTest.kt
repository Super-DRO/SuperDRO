package org.superdro.platform.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The Android Keystore SecretStore. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeIO = StoreIOProviderAndroid(context)
    private val fileName = "secrets-test.json"
    private val alias = "superdro_secrets_test"

    @After
    fun deleteFileAndKey() {
        storeIO.delete(fileName)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aSecretIsEncryptedInTheFileAndComesBack() {
        val store = AndroidKeystoreSecretStore(storeIO, keyAlias = alias, fileName = fileName)
        val password = "mqtt-password-123".encodeToByteArray()
        store.put("mqtt/password", password)
        store.put("output/psk", ByteArray(32) { it.toByte() })

        val text = File(context.filesDir, fileName).readText()
        assertTrue(text.contains(AndroidKeystoreSecretCipher.SCHEME), text)
        assertFalse(text.contains("mqtt-password-123"), text)
        assertFalse(text.contains("bXF0dC1wYXNzd29yZC0xMjM"), text)

        // A new store object reads the file: the values stay after a restart.
        val again = AndroidKeystoreSecretStore(storeIO, keyAlias = alias, fileName = fileName)
        assertContentEquals(password, again.get("mqtt/password"))
        assertContentEquals(ByteArray(32) { it.toByte() }, again.get("output/psk"))
        again.delete("mqtt/password")
        assertNull(AndroidKeystoreSecretStore(storeIO, keyAlias = alias, fileName = fileName).get("mqtt/password"))
    }

    @Test
    fun aValueDoesNotOpenUnderADifferentName() {
        val cipher = AndroidKeystoreSecretCipher(alias)
        val sealed = cipher.seal("a", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), cipher.open("a", sealed))
        val failure = runCatching { cipher.open("b", sealed) }
        assertTrue(failure.isFailure)
    }
}
