package org.superdro.core.store.json

import com.toxicbakery.logging.Arbor
import kotlin.io.encoding.Base64
import kotlin.jvm.Synchronized
import kotlin.time.Clock
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.superdro.core.model.SuperDroJson
import org.superdro.core.store.CORRUPT_SUFFIX
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.readEntry
import org.superdro.core.store.writeAtomically
import org.superdro.core.util.SecretStore

/**
 * Seals and opens one secret value for [SecretFileStore]. The Android implementation uses a key
 * of the Android Keystore (`platform:android:store`).
 */
interface SecretCipher {
    /** The name of the method. The file keeps it, so that a different method does not read the values. */
    val scheme: String

    /** Seals [plain]. [name] is bound to the result, so a sealed value cannot move to a different name. */
    fun seal(name: String, plain: ByteArray): SealedSecret

    /** Opens a value of [seal]. Throws when the value is not valid for [name]. */
    fun open(name: String, sealed: SealedSecret): ByteArray
}

/** One sealed secret value. [iv] and [data] are bytes, and the file keeps them as Base64. */
class SealedSecret(val iv: ByteArray, val data: ByteArray)

/**
 * A [SecretStore] with all secrets in one JSON file of the app files, not in the database:
 * the MQTT credentials and the PSK of the data output. The [cipher] seals each value. The file is
 * `secrets.json` and has the field `schemaVersion`.
 *
 * The functions block for a short time (a file read or write). Call them on the store
 * dispatcher, not on the main thread. A write is atomic (temporary file, then move). A file that
 * does not decode moves to `secrets.json.corrupt-<ms>`. A value that does not open (for example
 * after the loss of the key) gives null and a log line.
 */
open class SecretFileStore(
    private val storeIO: StoreIOProvider,
    private val cipher: SecretCipher,
    private val fileName: String = FILE_NAME,
    private val json: Json = SuperDroJson,
    private val clock: Clock = Clock.System,
) : SecretStore {
    private var cache: Map<String, SealedSecret>? = null

    @Synchronized
    override fun get(name: String): ByteArray? {
        val sealed = entries()[name] ?: return null
        return try {
            cipher.open(name, sealed)
        } catch (error: Exception) {
            log.e(error) { "Cannot open the secret $name." }
            null
        }
    }

    @Synchronized
    override fun put(name: String, secret: ByteArray) {
        write(entries() + (name to cipher.seal(name, secret)))
    }

    @Synchronized
    override fun delete(name: String) {
        val current = entries()
        if (name in current) write(current - name)
    }

    private fun entries(): Map<String, SealedSecret> = cache ?: load().also { cache = it }

    private fun load(): Map<String, SealedSecret> {
        if (!storeIO.exists(fileName)) return emptyMap()
        return try {
            val file = json.decodeFromString(SecretFile.serializer(), storeIO.readEntry(fileName) { it.readString() })
            if (file.scheme != cipher.scheme) {
                log.e { "$fileName uses the method ${file.scheme}, not ${cipher.scheme}. The store cannot read it." }
                emptyMap()
            } else {
                file.secrets.mapValues { (_, value) -> SealedSecret(Base64.decode(value.iv), Base64.decode(value.data)) }
            }
        } catch (error: Exception) {
            val asideName = "$fileName$CORRUPT_SUFFIX${clock.now().toEpochMilliseconds()}"
            log.e(error) { "Cannot decode $fileName. It moves to $asideName." }
            storeIO.move(fileName, asideName)
            emptyMap()
        }
    }

    private fun write(entries: Map<String, SealedSecret>) {
        val file = SecretFile(
            scheme = cipher.scheme,
            secrets = entries.mapValues { (_, value) -> SecretFileEntry(Base64.encode(value.iv), Base64.encode(value.data)) },
        )
        val text = json.encodeToString(SecretFile.serializer(), file)
        storeIO.writeAtomically(fileName) { sink -> sink.writeString(text) }
        cache = entries
    }

    companion object {
        /** The store name of the file. */
        const val FILE_NAME: String = "secrets.json"

        /** The current version of the file. */
        const val SCHEMA_VERSION: Int = 1

        private val log = Arbor.tag("SecretFileStore")
    }
}

@Serializable
private data class SecretFile(
    val schemaVersion: Int = SecretFileStore.SCHEMA_VERSION,
    val scheme: String = "",
    val secrets: Map<String, SecretFileEntry> = emptyMap(),
)

@Serializable
private data class SecretFileEntry(
    val iv: String = "",
    val data: String = "",
)
