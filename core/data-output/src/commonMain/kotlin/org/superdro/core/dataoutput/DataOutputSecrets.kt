package org.superdro.core.dataoutput

import org.superdro.core.util.SecretStore

/**
 * The MQTT credentials of an output. [password] is null when the broker needs no
 * password.
 */
class MqttCredentials(val username: String, password: ByteArray?) {
    private val passwordBytes: ByteArray? = password?.copyOf()

    /** A copy of the password bytes (UTF-8), or null. */
    val password: ByteArray? get() = passwordBytes?.copyOf()

    override fun toString(): String = "MqttCredentials(username=$username, password=${if (passwordBytes == null) "none" else "***"})"
}

/**
 * The secrets of the output of one machine profile in the `SecretStore`: the PSK and
 * the MQTT credentials. The configuration and
 * the database do not contain them.
 *
 * The names are `data_output.<profileId>.psk`, `data_output.<profileId>.mqtt_username`, and
 * `data_output.<profileId>.mqtt_password`. The functions block for a short time: call them on the
 * store dispatcher.
 */
class DataOutputSecrets(private val store: SecretStore, profileId: String) {

    /** The name of the PSK share text. */
    val pskName: String = "data_output.$profileId.psk"

    /** The name of the MQTT user name. */
    val mqttUsernameName: String = "data_output.$profileId.mqtt_username"

    /** The name of the MQTT password. */
    val mqttPasswordName: String = "data_output.$profileId.mqtt_password"

    /** Gives the stored PSK, or null when there is none or the stored text is not valid. */
    fun psk(): PskKey? {
        val text = store.get(pskName)?.decodeToString() ?: return null
        return runCatching { PskKey.parse(text) }.getOrNull()
    }

    /** Gives the stored PSK. When there is none, it makes a new key, keeps it, and gives it. */
    fun pskOrCreate(random: (Int) -> ByteArray = ::secureRandomBytes): PskKey =
        psk() ?: PskKey.generate(random = random).also(::setPsk)

    /** Keeps [key]. The old key stops: a client with the old key receives no records. */
    fun setPsk(key: PskKey) {
        store.put(pskName, key.shareText().encodeToByteArray())
    }

    /** Makes a new key with [identity], keeps it, and gives it. */
    fun replacePsk(identity: String = PskKey.DEFAULT_IDENTITY, random: (Int) -> ByteArray = ::secureRandomBytes): PskKey =
        PskKey.generate(identity, random).also(::setPsk)

    /** Gives the MQTT credentials, or null when no user name is stored. */
    fun mqttCredentials(): MqttCredentials? {
        val username = store.get(mqttUsernameName)?.decodeToString()?.takeIf { name -> name.isNotEmpty() } ?: return null
        return MqttCredentials(username, store.get(mqttPasswordName))
    }

    /**
     * Keeps the MQTT credentials. An empty [username] removes the user name and the password. An
     * empty or null [password] removes the password.
     */
    fun setMqttCredentials(username: String, password: ByteArray?) {
        if (username.isEmpty()) {
            store.delete(mqttUsernameName)
            store.delete(mqttPasswordName)
            return
        }
        store.put(mqttUsernameName, username.encodeToByteArray())
        if (password == null || password.isEmpty()) store.delete(mqttPasswordName) else store.put(mqttPasswordName, password)
    }

    /** Removes all secrets of the output, for example when the user removes the machine profile. */
    fun deleteAll() {
        store.delete(pskName)
        store.delete(mqttUsernameName)
        store.delete(mqttPasswordName)
    }
}
