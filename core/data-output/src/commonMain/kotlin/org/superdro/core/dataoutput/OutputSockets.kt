package org.superdro.core.dataoutput

import kotlinx.coroutines.CoroutineDispatcher

/** One TCP connection to a client of the output server. */
interface ByteLink {

    /** The address of the client, for example `192.168.1.30`, or `?` when it is not known. */
    val remoteHost: String

    /** Reads bytes into [buffer]. Gives the number of bytes, or −1 at the end of the stream. */
    suspend fun read(buffer: ByteArray): Int

    /** Writes all of [bytes] and flushes them. */
    suspend fun write(bytes: ByteArray)

    /** Closes the connection at once. A second call has no effect. */
    fun close()
}

/** One server socket of the output server: it accepts the clients. */
interface ListenSocket {

    /** The bound address, as the bind request gave it. */
    val host: String

    /** The bound port. */
    val port: Int

    /** Waits for the next client. An error ends the accept loop of the server. */
    suspend fun accept(): ByteLink

    /**
     * Closes the socket at once. The port is free when the call returns: a new bind on the port
     * succeeds, and a new connection to the port fails. A second call has no effect.
     */
    fun close()
}

/** Opens the server sockets of the output server. A test gives a fake. */
fun interface ListenSocketFactory {

    /**
     * Binds a socket to [host] and [port] with `SO_REUSEADDR`.
     *
     * @throws Exception when the bind fails, for example because the port is in use.
     */
    suspend fun bind(host: String, port: Int): ListenSocket
}

/**
 * Gives the [ListenSocketFactory] of production: the TCP sockets of the system. The blocking
 * socket calls run on [dispatcher].
 *
 * [ListenSocket.close] releases the port before it returns. Thus a new bind on the same port
 * immediately after a close succeeds, and the port refuses new connections after the close.
 */
expect fun systemListenSockets(dispatcher: CoroutineDispatcher): ListenSocketFactory

/** The text form of an IP address. */
object IpText {

    /**
     * Gives the text of the address [bytes]: `a.b.c.d` for 4 bytes, or the IPv6 form with the
     * longest run of two or more zero groups as `::` for 16 bytes. Other sizes give `?`.
     */
    fun format(bytes: ByteArray): String = when (bytes.size) {
        4 -> bytes.joinToString(".") { byte -> (byte.toInt() and 0xFF).toString() }
        16 -> ipv6(bytes)
        else -> "?"
    }

    private fun ipv6(bytes: ByteArray): String {
        val groups = IntArray(8) { index -> ((bytes[2 * index].toInt() and 0xFF) shl 8) or (bytes[2 * index + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < 8) {
            if (groups[index] == 0) {
                var end = index
                while (end < 8 && groups[end] == 0) end++
                if (end - index > bestLength) {
                    bestStart = index
                    bestLength = end - index
                }
                index = end
            } else {
                index++
            }
        }
        if (bestLength < 2) return groups.joinToString(":") { group -> group.toString(16) }
        val head = groups.copyOfRange(0, bestStart).joinToString(":") { group -> group.toString(16) }
        val tail = groups.copyOfRange(bestStart + bestLength, 8).joinToString(":") { group -> group.toString(16) }
        return "$head::$tail"
    }
}
