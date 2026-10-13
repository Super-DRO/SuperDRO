package org.superdro.platform.android.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.json.SealedSecret
import org.superdro.core.store.json.SecretCipher
import org.superdro.core.store.json.SecretFileStore
import org.superdro.core.util.SecretStore

/**
 * The Android [SecretStore]: the MQTT credentials and the PSK of the data output.
 *
 * One AES-256 key in the Android Keystore encrypts each secret with AES-GCM. The key never
 * leaves the Keystore. The encrypted values are in `secrets.json` in the app files, not in the
 * database. The name of each secret is the associated data of its value, so a value cannot move
 * to a different name.
 *
 * The functions block for a short time. Call them on the store dispatcher, not on the main
 * thread. The values do not survive a reinstall or a backup to a different device, because the
 * key stays in the Keystore of this device.
 */
class AndroidKeystoreSecretStore(
    storeIO: StoreIOProvider,
    keyAlias: String = KEY_ALIAS,
    fileName: String = SecretFileStore.FILE_NAME,
) : SecretFileStore(storeIO, AndroidKeystoreSecretCipher(keyAlias), fileName) {

    /** The store in the files directory of [context]. */
    constructor(context: Context) : this(StoreIOProviderAndroid(context))

    companion object {
        /** The alias of the AES key in the Android Keystore. */
        const val KEY_ALIAS: String = "superdro_secrets"
    }
}

/**
 * AES-256-GCM with a key of the Android Keystore. The Keystore makes a new random IV of 12 bytes
 * for each value. The tag has 128 bits.
 */
class AndroidKeystoreSecretCipher(private val keyAlias: String) : SecretCipher {
    override val scheme: String = SCHEME

    override fun seal(name: String, plain: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(plain)
        return SealedSecret(iv = cipher.iv, data = data)
    }

    override fun open(name: String, sealed: SealedSecret): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed.data)
    }

    /** The key of [keyAlias]. The first call makes it. */
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** The scheme name in `secrets.json`. */
        const val SCHEME: String = "android-keystore-aes-256-gcm"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
