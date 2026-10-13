package org.superdro.core.dataoutput

/** The record channel to one client, after the handshake of its [StreamSecurity]. */
interface RecordLink {

    /** Sends one record. The bytes in the stream are the same in each security mode. */
    suspend fun send(record: ByteArray)

    /**
     * Reads and discards the inbound bytes (the output receives no commands). Returns at
     * the end of the stream, and throws at a read error.
     */
    suspend fun drainInbound()

    /** Closes the connection at once. */
    fun close()
}

/** The security mode of the TCP stream. */
fun interface StreamSecurity {

    /**
     * Makes the record link of a new client [link]. The PSK mode does the TLS 1.3 handshake
     * first. A client with no key or a wrong key fails the handshake and gets no record.
     *
     * @throws HandshakeException when the client does not complete the handshake.
     */
    suspend fun open(link: ByteLink): RecordLink
}

/** The client did not complete the handshake of the security mode. */
class HandshakeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The plain text mode "Plain text (TouchDRO compatible)": the records with no
 * encryption and no access control. The output uses it only after the user confirmed the warning
 * "Use only on trusted networks".
 */
object PlainTextSecurity : StreamSecurity {
    override suspend fun open(link: ByteLink): RecordLink = PlainRecordLink(link)
}

private class PlainRecordLink(private val link: ByteLink) : RecordLink {
    private val buffer = ByteArray(DRAIN_BUFFER_SIZE)

    override suspend fun send(record: ByteArray) {
        link.write(record)
    }

    override suspend fun drainInbound() {
        while (link.read(buffer) >= 0) {
            // The output discards the inbound bytes.
        }
    }

    override fun close() {
        link.close()
    }
}

/** The size of the read buffer for the inbound bytes. */
internal const val DRAIN_BUFFER_SIZE: Int = 256

/**
 * Gives the encrypted mode "Encrypted (PSK)" with [key]: TLS 1.3 with an external PSK
 * (RFC 8446) and the key exchange mode `psk_dhe_ke`, through BouncyCastle.
 */
expect fun pskStreamSecurity(key: PskKey): StreamSecurity
