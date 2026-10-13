package org.superdro.core.util

/**
 * Keeps named secrets in an encrypted store (a platform contract): the MQTT credentials and the
 * pre-shared key of the data output. The secrets are not in the database and not in a JSON file. The Android implementation (a key of the
 * Android Keystore that encrypts each value) comes in `platform:android:store`.
 *
 * The functions block for a short time. Call them on the store dispatcher, not on the main thread.
 */
interface SecretStore {

    /** Gives the secret with the name [name], or null when the store has no such secret. */
    fun get(name: String): ByteArray?

    /** Keeps [secret] with the name [name]. A secret with the same name is replaced. */
    fun put(name: String, secret: ByteArray)

    /** Removes the secret with the name [name]. A name that the store does not have causes no error. */
    fun delete(name: String)
}

/**
 * A [SecretStore] in memory, for the tests and for a platform with no encrypted store. It keeps a
 * copy of each value, so a change of the array of the caller does not change the stored secret.
 */
class InMemorySecretStore : SecretStore {
    private val secrets = mutableMapOf<String, ByteArray>()

    override fun get(name: String): ByteArray? = secrets[name]?.copyOf()

    override fun put(name: String, secret: ByteArray) {
        secrets[name] = secret.copyOf()
    }

    override fun delete(name: String) {
        secrets.remove(name)
    }
}
