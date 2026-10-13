package org.superdro.core.connection.tcp

import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * The values of the Wi-Fi TCP link. [TcpTransport]
 * uses `aSocket(selector).tcp().connect(host, port) { noDelay = true }` with [CONNECT_TIMEOUT_MS],
 * then reads into a buffer of [READ_BUFFER_BYTES] and writes one command for each write, with a
 * limit of [WRITE_TIMEOUT_MS].
 */
object TcpLinkDefaults {

    /** The link type of this module. */
    val kind: TransportKind = TransportKind.Tcp

    /** The default port of an adapter. */
    const val PORT: Int = AdapterAddress.Tcp.DEFAULT_PORT

    /** The lowest port of the range 1 to 65535. */
    const val MIN_PORT: Int = 1

    /** The highest port of the range 1 to 65535. */
    const val MAX_PORT: Int = 65535

    /** The time limit of one connect attempt. A timeout is `LinkFailure.Refused`. */
    const val CONNECT_TIMEOUT_MS: Long = 5000L

    /** The time limit of one write. A timeout is `LinkFailure.Lost`. */
    const val WRITE_TIMEOUT_MS: Long = 1000L

    /** The size of the read buffer. */
    const val READ_BUFFER_BYTES: Int = 1024
}
