package org.superdro.platform.android.bluetooth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.superdro.core.connection.LinkFailure

/** An RFCOMM socket of the tests. Its streams block as the streams of a real socket do. */
internal class FakeSppSocket : SppSocket {

    private sealed interface Item {
        class Data(val bytes: ByteArray) : Item
        data object End : Item
        data object Closed : Item
        class Failure(val failure: Exception) : Item
    }

    private val reads = LinkedBlockingQueue<Item>()
    private val closedLatch = CountDownLatch(1)
    private val written = ByteArrayOutputStream()

    @Volatile
    var connectFailure: Exception? = null

    /** When true, `connect()` blocks until the socket closes, as a connect to a device that does not answer. */
    @Volatile
    var connectBlocks = false

    /** The exception of a `connect()` that blocks, after the socket closes. */
    @Volatile
    var blockedConnectFailure: IOException = IOException("read failed, socket might closed or timeout, read ret: -1")

    /** When not null, each write blocks until this latch opens or the socket closes. */
    @Volatile
    var writeGate: CountDownLatch? = null

    @Volatile
    var writeFailure: IOException? = null

    @Volatile
    var connectCount = 0
        private set

    @Volatile
    var flushCount = 0
        private set

    val isClosed: Boolean get() = closedLatch.count == 0L

    /** The bytes that the transport wrote. */
    fun writtenBytes(): ByteArray = synchronized(written) { written.toByteArray() }

    /** The adapter sends [bytes]. */
    fun send(bytes: ByteArray) = reads.put(Item.Data(bytes))

    /** The adapter ends the stream: `read()` gives -1. */
    fun sendEnd() = reads.put(Item.End)

    /** The next `read()` throws [failure]. */
    fun failRead(failure: Exception) = reads.put(Item.Failure(failure))

    override fun connect() {
        connectCount++
        connectFailure?.let { failure -> throw failure }
        if (connectBlocks) {
            closedLatch.await()
            throw blockedConnectFailure
        }
    }

    override val input: InputStream = object : InputStream() {
        private var pending: ByteArray = ByteArray(0)
        private var offset = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset >= pending.size) {
                when (val item = reads.take()) {
                    is Item.Data -> {
                        pending = item.bytes
                        offset = 0
                    }
                    Item.End -> return -1
                    Item.Closed -> {
                        reads.put(Item.Closed)
                        throw IOException("bt socket closed, read return: -1")
                    }
                    is Item.Failure -> throw item.failure
                }
            }
            val count = minOf(length, pending.size - offset)
            pending.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, start: Int, length: Int) {
            writeGate?.let { gate ->
                while (!gate.await(10, TimeUnit.MILLISECONDS)) {
                    if (isClosed) throw IOException("Broken pipe")
                }
            }
            writeFailure?.let { failure -> throw failure }
            if (isClosed) throw IOException("socket closed")
            synchronized(written) { written.write(bytes, start, length) }
        }

        override fun flush() {
            flushCount++
        }
    }

    override fun close() {
        if (closedLatch.count == 0L) return
        closedLatch.countDown()
        reads.put(Item.Closed)
    }
}

/** The Android Bluetooth calls of the tests. [calls] keeps the order of the calls. */
internal class FakeSppPlatform(
    var problem: LinkFailure? = null,
    var createFailure: Exception? = null,
) : SppPlatform {
    val socket = FakeSppSocket()
    val calls = mutableListOf<String>()

    override fun linkProblem(mac: String): LinkFailure? {
        calls += "linkProblem"
        return problem
    }

    override fun cancelDiscoveryIfPermitted() {
        calls += "cancelDiscovery"
    }

    override fun createSocket(mac: String): SppSocket {
        calls += "createSocket"
        createFailure?.let { failure -> throw failure }
        return socket
    }
}
