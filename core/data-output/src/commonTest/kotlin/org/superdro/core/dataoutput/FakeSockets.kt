package org.superdro.core.dataoutput

import kotlinx.coroutines.channels.Channel

/** Fake sockets for the server tests with virtual time. */
class FakeSocketFactory : ListenSocketFactory {

    /** Each socket that [bind] made, in order. */
    val sockets = mutableListOf<FakeListenSocket>()

    /** Gives the bind error of a host and a port, or null for a bind that succeeds. */
    var bindFailure: (String, Int) -> Exception? = { _, _ -> null }

    /** The sockets that are not closed. */
    val open: List<FakeListenSocket> get() = sockets.filter { socket -> !socket.closed }

    override suspend fun bind(host: String, port: Int): ListenSocket {
        bindFailure(host, port)?.let { exception -> throw exception }
        return FakeListenSocket(host, port).also { socket -> sockets += socket }
    }
}

class FakeListenSocket(override val host: String, override val port: Int) : ListenSocket {
    private val incoming = Channel<ByteLink>(Channel.UNLIMITED)
    var closed = false
        private set

    /** Connects a new client from [remoteHost]. */
    fun connect(remoteHost: String = "192.168.1.30"): FakeLink = FakeLink(remoteHost).also { link -> incoming.trySend(link) }

    /** Makes the next `accept()` fail with [exception]. */
    fun failAccept(exception: Exception) {
        incoming.close(exception)
    }

    override suspend fun accept(): ByteLink = incoming.receive()

    override fun close() {
        closed = true
        incoming.cancel()
    }
}

/** The server side of one fake connection, with the client side functions. */
class FakeLink(override val remoteHost: String) : ByteLink {
    private val toServer = Channel<ByteArray>(Channel.UNLIMITED)
    private val received = mutableListOf<ByteArray>()
    var closed = false
        private set

    /** Makes each write fail with [exception] when it is not null. */
    var writeFailure: Exception? = null

    /** The bytes that the client received. */
    val receivedText: String get() = received.fold(ByteArray(0)) { all, bytes -> all + bytes }.decodeToString()

    /** The writes that the client received. */
    val writes: List<ByteArray> get() = received.toList()

    /** The client sends [bytes] to the server. */
    fun send(bytes: ByteArray) {
        toServer.trySend(bytes)
    }

    /** The client closes its side: the server reads the end of the stream. */
    fun disconnect() {
        toServer.close()
    }

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = toServer.receiveCatching().getOrNull() ?: return -1
        chunk.copyInto(buffer, 0, 0, minOf(chunk.size, buffer.size))
        return minOf(chunk.size, buffer.size)
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed) throw IllegalStateException("The link is closed.")
        writeFailure?.let { exception -> throw exception }
        received += bytes.copyOf()
    }

    override fun close() {
        closed = true
        toServer.close()
    }
}
