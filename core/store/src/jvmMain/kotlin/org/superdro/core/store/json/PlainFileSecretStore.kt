package org.superdro.core.store.json

import kotlin.time.Clock
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.util.SecretStore

/**
 * A [SecretStore] for the JVM tests and a later desktop app: [SecretFileStore] with no cipher.
 *
 * WARNING: This store does not encrypt. Each secret is in `secrets.json` as plain bytes (Base64
 * text), and each user or program that can read the file can read the secrets. Do not use it on
 * Android: the Android app uses `AndroidKeystoreSecretStore` of `platform:android:store`.
 * A desktop app must change to an encrypted store before it keeps real credentials.
 */
class PlainFileSecretStore(
    storeIO: StoreIOProvider,
    fileName: String = FILE_NAME,
    clock: Clock = Clock.System,
) : SecretFileStore(storeIO, PlainSecretCipher, fileName, clock = clock)

/** The cipher of [PlainFileSecretStore]: it does not change the bytes. */
internal object PlainSecretCipher : SecretCipher {
    override val scheme: String = "none"

    override fun seal(name: String, plain: ByteArray): SealedSecret = SealedSecret(ByteArray(0), plain.copyOf())

    override fun open(name: String, sealed: SealedSecret): ByteArray = sealed.data.copyOf()
}
