package org.superdro.platform.desktop.store

import com.toxicbakery.logging.Arbor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.json.SealedSecret
import org.superdro.core.store.json.SecretCipher
import org.superdro.core.store.json.SecretFileStore
import org.superdro.core.util.SecretStore

/**
 * The desktop [SecretStore]: the MQTT credentials and the PSK of the data output.
 *
 * WARNING: This store is a file store. It is not as safe as the Android Keystore. One AES-256 key
 * in the file [keyFile] encrypts each secret with AES-GCM, and the encrypted values are in
 * `secrets.json`. The two files are in the application data directory. On Linux and macOS the key
 * file has the permissions `rw-------` and the directory has `rwx------`, so a different user of
 * the computer cannot read them. On Windows the permissions of the user profile apply. A program
 * that runs as the same user can read the key and thus the secrets. A copy of the two files also
 * opens the secrets on a different computer. SuperDRO does not use the key store of the operating
 * system on the desktop.
 *
 * The name of each secret is the associated data of its value, so a value cannot move to a
 * different name. The functions block for a short time. Call them on the store dispatcher, not on
 * the main thread.
 */
class DesktopSecretStore(
    storeIO: StoreIOProvider,
    keyFile: File,
    fileName: String = SecretFileStore.FILE_NAME,
) : SecretFileStore(storeIO, FileKeySecretCipher(keyFile), fileName) {

    companion object {
        /** The file name of the key in the application data directory. */
        const val KEY_FILE_NAME: String = "secrets.key"

        /** The warning that the app writes to the log at each start. */
        const val WARNING: String =
            "The desktop secret store is a file store: a key file in the application data directory encrypts the " +
                "data output secrets. A program that runs as the same user can read them."
    }
}

/**
 * AES-256-GCM with a key in a file. The cipher makes a new random IV of 12 bytes for each value.
 * The tag has 128 bits. The first seal makes the key file with [DesktopFiles.writeOwnerOnly].
 *
 * A key file with a wrong size moves to `<name>.damaged-<ms>`, and a new key replaces it. The
 * values of the old key then do not open: [SecretFileStore.get] gives null for them.
 */
class FileKeySecretCipher(
    private val keyFile: File,
    private val random: SecureRandom = SecureRandom(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SecretCipher {

    override val scheme: String = SCHEME

    private var cached: SecretKey? = null

    override fun seal(name: String, plain: ByteArray): SealedSecret {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return SealedSecret(iv = iv, data = cipher.doFinal(plain))
    }

    override fun open(name: String, sealed: SealedSecret): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed.data)
    }

    /** The key of [keyFile]. The first call reads the file, or makes it. */
    @Synchronized
    private fun key(): SecretKey {
        cached?.let { key -> return key }
        val stored = if (keyFile.isFile) keyFile.readBytes() else null
        val bytes = if (stored != null && stored.size == KEY_BYTES) {
            stored
        } else {
            if (stored != null) {
                val aside = File(keyFile.absolutePath + ".damaged-${nowMs()}")
                log.e { "The key file ${keyFile.absolutePath} has ${stored.size} bytes, not $KEY_BYTES. It moves to ${aside.name}." }
                Files.move(keyFile.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            ByteArray(KEY_BYTES).also(random::nextBytes).also { key -> DesktopFiles.writeOwnerOnly(keyFile, key) }
        }
        return SecretKeySpec(bytes, ALGORITHM).also { key -> cached = key }
    }

    companion object {
        /** The scheme name in `secrets.json`. */
        const val SCHEME: String = "desktop-file-key-aes-256-gcm"

        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        private val log = Arbor.tag("FileKeySecretCipher")
    }
}
