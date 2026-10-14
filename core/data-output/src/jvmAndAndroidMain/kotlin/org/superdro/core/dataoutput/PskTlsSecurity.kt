package org.superdro.core.dataoutput

import java.security.SecureRandom
import java.util.Vector
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.bouncycastle.tls.AbstractTlsServer
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.AlertLevel
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.PskIdentity
import org.bouncycastle.tls.PskKeyExchangeMode
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { bytes -> secureRandom.nextBytes(bytes) }

private val secureRandom = SecureRandom()

actual fun pskStreamSecurity(key: PskKey): StreamSecurity = PskTlsSecurity(key)

/**
 * The encrypted mode "Encrypted (PSK)":
 * TLS 1.3 (RFC 8446) with an external PSK, through the BouncyCastle TLS API in its non-blocking
 * mode on the TCP sockets of the system.
 *
 * - Only TLS 1.3. The cipher suites are `TLS_AES_128_GCM_SHA256` and
 *   `TLS_CHACHA20_POLY1305_SHA256`, the suites with the hash of the PSK (SHA-256).
 * - The key exchange mode is `psk_dhe_ke` only: each connection also does an (EC)DHE exchange, so
 *   that a later loss of the PSK does not show the records of an earlier connection (RFC 9257,
 *   section 4).
 * - The server has no certificate. A client with no PSK, an unknown identity, or a wrong key
 *   fails the handshake and receives no record.
 * - The records in the stream are the same bytes as in the plain text mode.
 *
 * @param handshakeTimeoutMs The time that a client has for the handshake.
 */
internal class PskTlsSecurity(
    private val key: PskKey,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : StreamSecurity {

    override suspend fun open(link: ByteLink): RecordLink {
        val crypto = BcTlsCrypto(secureRandom)
        val server = PskServer(crypto, key)
        val protocol = TlsServerProtocol()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            protocol.accept(server)
            withTimeout(handshakeTimeoutMs) {
                while (!server.handshakeComplete) {
                    flushOutput(protocol, link)
                    val count = link.read(buffer)
                    if (count < 0) throw HandshakeException("The client closed the connection in the handshake.")
                    protocol.offerInput(buffer, 0, count)
                }
                flushOutput(protocol, link)
            }
        } catch (exception: TimeoutCancellationException) {
            throw HandshakeException("The client did not complete the handshake in $handshakeTimeoutMs ms.", exception)
        } catch (exception: HandshakeException) {
            throw exception
        } catch (exception: Exception) {
            // Send the alert of the failure (for example decrypt_error for a wrong key).
            runCatching { flushOutput(protocol, link) }
            throw HandshakeException(server.failure ?: exception.message ?: "The TLS handshake failed.", exception)
        }
        return PskRecordLink(protocol, link, buffer)
    }

    companion object {
        /** The default time for the handshake of one client. */
        const val HANDSHAKE_TIMEOUT_MS: Long = 10_000L

        private const val READ_BUFFER_SIZE: Int = 16 * 1024 + 512
    }
}

private suspend fun flushOutput(protocol: TlsServerProtocol, link: ByteLink) {
    val available = protocol.availableOutputBytes
    if (available <= 0) return
    val bytes = ByteArray(available)
    val count = protocol.readOutput(bytes, 0, available)
    link.write(if (count == available) bytes else bytes.copyOf(count))
}

/** The record link after the handshake. One [Mutex] serializes the calls of the TLS protocol. */
private class PskRecordLink(
    private val protocol: TlsServerProtocol,
    private val link: ByteLink,
    private val buffer: ByteArray,
) : RecordLink {
    private val mutex = Mutex()

    override suspend fun send(record: ByteArray) {
        mutex.withLock {
            protocol.writeApplicationData(record, 0, record.size)
            flushOutput(protocol, link)
        }
    }

    override suspend fun drainInbound() {
        val discard = ByteArray(DRAIN_BUFFER_SIZE)
        while (true) {
            val count = link.read(buffer)
            if (count < 0) return
            mutex.withLock {
                protocol.offerInput(buffer, 0, count)
                // The output discards the application data of the client.
                while (protocol.availableInputBytes > 0) {
                    protocol.readInput(discard, 0, minOf(discard.size, protocol.availableInputBytes))
                }
                flushOutput(protocol, link)
            }
            if (protocol.isClosed) return
        }
    }

    override fun close() {
        link.close()
    }
}

/**
 * The TLS 1.3 server with one external PSK. It selects the PSK only for the identity of [key].
 */
private class PskServer(crypto: BcTlsCrypto, private val key: PskKey) : AbstractTlsServer(crypto) {

    /** True after the handshake. */
    @Volatile
    var handshakeComplete: Boolean = false
        private set

    /** The text of the alert that stopped the handshake, or null. */
    @Volatile
    var failure: String? = null
        private set

    private val identity = key.identityBytes

    override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

    override fun getSupportedCipherSuites(): IntArray = TlsUtils.getSupportedCipherSuites(
        crypto,
        intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_CHACHA20_POLY1305_SHA256),
    )

    override fun getPskKeyExchangeModes(): ShortArray = shortArrayOf(PskKeyExchangeMode.psk_dhe_ke)

    override fun getExternalPSK(identities: Vector<*>): TlsPSKExternal? {
        // An external PSK has the obfuscated ticket age 0, and the server ignores it (RFC 8446, 4.2.11).
        val match = identities.any { item -> item is PskIdentity && item.identity.contentEquals(identity) }
        if (!match) return null
        return BasicTlsPSKExternal(identity, crypto.createSecret(key.secret), PRFAlgorithm.tls13_hkdf_sha256)
    }

    /** The server has no certificate. Without the PSK, the handshake fails. */
    override fun getCredentials(): TlsCredentials? = null

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        handshakeComplete = true
    }

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
        if (alertLevel == AlertLevel.fatal) {
            failure = "TLS alert ${AlertDescription.getText(alertDescription)}" + (message?.let { text -> ": $text" } ?: "")
        }
    }

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
        if (alertLevel == AlertLevel.fatal) {
            failure = "The client sent the TLS alert ${AlertDescription.getText(alertDescription)}"
        }
    }
}
