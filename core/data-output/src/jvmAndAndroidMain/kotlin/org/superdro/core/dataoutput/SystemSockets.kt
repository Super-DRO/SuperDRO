package org.superdro.core.dataoutput

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

actual fun systemListenSockets(dispatcher: CoroutineDispatcher): ListenSocketFactory = SystemListenSocketFactory(dispatcher)

/**
 * The TCP sockets of the system: `java.nio` channels in the blocking mode, with no selector.
 *
 * A selector keeps a closed server channel open until its next select (JDK 11 and later). The
 * port then stays in use, and it accepts connections, for an unknown time after the close. A
 * blocking channel has no selector: its close releases the port when its accept call stops.
 *
 * Each accept call and each read call holds one thread of the dispatcher until it returns.
 * A cancel of the call closes its channel.
 */
private class SystemListenSocketFactory(private val dispatcher: CoroutineDispatcher) : ListenSocketFactory {

    override suspend fun bind(host: String, port: Int): ListenSocket = withContext(dispatcher) {
        val channel = ServerSocketChannel.open()
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            channel.bind(InetSocketAddress(InetAddress.getByName(host), port))
            val boundPort = (channel.localAddress as? InetSocketAddress)?.port ?: port
            SystemListenSocket(channel, host, boundPort, dispatcher)
        } catch (exception: Throwable) {
            channel.close()
            throw exception
        }
    }
}

private class SystemListenSocket(
    private val channel: ServerSocketChannel,
    override val host: String,
    override val port: Int,
    private val dispatcher: CoroutineDispatcher,
) : ListenSocket {

    /** The lock of the accept call. [close] gets it to know that the accept call stopped. */
    private val acceptLock = ReentrantLock()

    override suspend fun accept(): ByteLink {
        val client = runInterruptible(dispatcher) { acceptLock.withLock { channel.accept() } }
        return SystemByteLink(client, dispatcher)
    }

    override fun close() {
        // The close stops an accept call on a different thread. The kernel keeps the port until
        // that call returns. Thus wait for the end of the accept call.
        channel.close()
        if (acceptLock.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) acceptLock.unlock()
    }

    companion object {
        /** The maximum time that [close] waits for the end of an accept call. */
        const val CLOSE_WAIT_MS: Long = 1000L
    }
}

private class SystemByteLink(private val channel: SocketChannel, private val dispatcher: CoroutineDispatcher) : ByteLink {

    init {
        // Send each record at once, as the record is the full message. A client that closed
        // already gives an error here. Then the next read gives the error again.
        runCatching { channel.setOption(StandardSocketOptions.TCP_NODELAY, true) }
    }

    override val remoteHost: String =
        runCatching { channel.remoteAddress as? InetSocketAddress }.getOrNull()?.address?.address?.let(IpText::format) ?: "?"

    override suspend fun read(buffer: ByteArray): Int = runInterruptible(dispatcher) { channel.read(ByteBuffer.wrap(buffer)) }

    override suspend fun write(bytes: ByteArray) {
        runInterruptible(dispatcher) {
            val data = ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) channel.write(data)
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }
}
