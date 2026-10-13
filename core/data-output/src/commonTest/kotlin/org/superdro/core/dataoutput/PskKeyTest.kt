package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.util.InMemorySecretStore

/** The key format of the encrypted mode and the secrets in the SecretStore. */
class PskKeyTest {

    private val bytes = ByteArray(32) { index -> (index * 8 + 1).toByte() }

    @Test
    fun theShareTextIsTheIdentityAndTheHexKey() {
        val key = PskKey("superdro", bytes)
        assertEquals("superdro:0109111921293139414951596169717981899199a1a9b1b9c1c9d1d9e1e9f1f9", key.shareText())
        assertEquals(64, key.keyHex.length)
        assertEquals(key, PskKey.parse(key.shareText()))
        assertEquals(key, PskKey.parse("  superdro:0109111921293139414951596169717981899199A1A9B1B9C1C9D1D9E1E9F1F9\n"))
        assertFalse(key.toString().contains(key.keyHex))
    }

    @Test
    fun aShareTextThatIsNotValidIsRefused() {
        assertFailsWith<IllegalArgumentException> { PskKey.parse("superdro") }
        assertFailsWith<IllegalArgumentException> { PskKey.parse(":" + "00".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { PskKey.parse("superdro:" + "00".repeat(31)) }
        assertFailsWith<IllegalArgumentException> { PskKey.parse("superdro:" + "0g".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { PskKey.parse("super dro:" + "00".repeat(32)) }
        assertFailsWith<IllegalArgumentException> { PskKey("superdro", ByteArray(16)) }
        assertTrue(PskKey.isValidIdentity("mill-1.shop"))
        assertFalse(PskKey.isValidIdentity("a:b"))
        assertFalse(PskKey.isValidIdentity("x".repeat(129)))
    }

    @Test
    fun aNewKeyHas32RandomBytes() {
        val first = PskKey.generate()
        val second = PskKey.generate()
        assertEquals(PskKey.DEFAULT_IDENTITY, first.identity)
        assertEquals(32, first.secret.size)
        assertNotEquals(first, second)
    }

    @Test
    fun theSecretsAreInTheSecretStore() {
        val store = InMemorySecretStore()
        val secrets = DataOutputSecrets(store, "p1")
        assertNull(secrets.psk())
        val key = secrets.pskOrCreate { count -> ByteArray(count) { 7 } }
        assertEquals(key, secrets.psk())
        assertEquals(key.shareText(), store.get("data_output.p1.psk")?.decodeToString())
        // A second call keeps the key.
        assertEquals(key, secrets.pskOrCreate())
        val replaced = secrets.replacePsk("mill")
        assertNotEquals(key, replaced)
        assertEquals("mill", secrets.psk()?.identity)

        assertNull(secrets.mqttCredentials())
        secrets.setMqttCredentials("dro", "secret".encodeToByteArray())
        assertEquals("dro", secrets.mqttCredentials()?.username)
        assertEquals("secret", secrets.mqttCredentials()?.password?.decodeToString())
        assertEquals("dro", store.get("data_output.p1.mqtt_username")?.decodeToString())
        assertFalse(secrets.mqttCredentials().toString().contains("secret"))
        secrets.setMqttCredentials("dro", null)
        assertNull(secrets.mqttCredentials()?.password)
        secrets.setMqttCredentials("", null)
        assertNull(secrets.mqttCredentials())

        secrets.deleteAll()
        assertNull(secrets.psk())
    }
}
