package org.superdro.core.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.util.InMemorySecretStore

/** The bearer token in the secret store. */
class McpSecretsTest {

    private val store = InMemorySecretStore()
    private var seed = 0
    private val secrets = McpSecrets(store) { count -> ByteArray(count) { index -> (index + seed++).toByte() } }

    @Test
    fun thereIsNoTokenBeforeTheFirstCreate() {
        assertNull(secrets.token())
        assertNull(store.get("mcp.token"))
    }

    @Test
    fun theFirstCreateMakes43Base64UrlCharactersAndKeepsThem() {
        val token = secrets.tokenOrCreate()
        assertEquals(43, token.length)
        assertTrue(McpSecrets.isValidToken(token))
        assertEquals(token, secrets.token())
        assertEquals(token, secrets.tokenOrCreate())
        assertEquals(token, store.get("mcp.token")?.decodeToString())
        assertEquals("mcp.token", secrets.tokenName)
    }

    @Test
    fun aNewTokenReplacesTheOldOne() {
        val first = secrets.tokenOrCreate()
        val second = secrets.replaceToken()
        assertNotEquals(first, second)
        assertEquals(second, secrets.token())
    }

    @Test
    fun theDeleteRemovesTheToken() {
        secrets.tokenOrCreate()
        secrets.delete()
        assertNull(secrets.token())
    }

    @Test
    fun aStoredTextThatIsNotATokenGivesNull() {
        store.put("mcp.token", "short".encodeToByteArray())
        assertNull(secrets.token())
        store.put("mcp.token", "A".repeat(42).plus("=").encodeToByteArray())
        assertNull(secrets.token())
        assertTrue(McpSecrets.isValidToken("A".repeat(43)))
    }
}
