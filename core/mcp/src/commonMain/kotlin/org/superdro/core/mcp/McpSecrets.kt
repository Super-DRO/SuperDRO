package org.superdro.core.mcp

import org.superdro.core.dataoutput.secureRandomBytes
import org.superdro.core.util.SecretStore

/**
 * The bearer token of the MCP server in the `SecretStore`: 32 random bytes as 43 base64url
 * characters. The configuration and the database do not contain it. The functions block for a
 * short time: call them on the store dispatcher.
 */
class McpSecrets(private val store: SecretStore, private val random: (Int) -> ByteArray = ::secureRandomBytes) {

    /** The name of the token in the store. */
    val tokenName: String = "mcp.token"

    /** Gives the stored token, or null when there is none or the stored text is not valid. */
    fun token(): String? = store.get(tokenName)?.decodeToString()?.takeIf(::isValidToken)

    /** Gives the stored token. When there is none, it makes a new token, keeps it, and gives it. */
    fun tokenOrCreate(): String = token() ?: replaceToken()

    /** Makes a new token, keeps it, and gives it. Each client with the old token gets 401. */
    fun replaceToken(): String {
        val token = Base64Text.encodeUrlSafe(random(TOKEN_BYTES))
        store.put(tokenName, token.encodeToByteArray())
        return token
    }

    /** Removes the token. */
    fun delete() {
        store.delete(tokenName)
    }

    companion object {
        /** The number of random bytes: 256 bits. */
        const val TOKEN_BYTES: Int = 32

        /** The number of base64url characters of a token. */
        const val TOKEN_LENGTH: Int = 43

        /** Gives true when [token] has 43 base64url characters. */
        fun isValidToken(token: String): Boolean =
            token.length == TOKEN_LENGTH && token.all { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' }
    }
}
