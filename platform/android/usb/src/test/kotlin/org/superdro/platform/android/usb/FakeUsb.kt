package org.superdro.platform.android.usb

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.superdro.core.model.AdapterAddress

/** A USB serial port of the tests. A read waits for the timeout as a bulk read does. */
internal class FakeUsbPort : UsbPort {

    private sealed interface Item {
        class Data(val bytes: ByteArray) : Item
        class Failure(val failure: IOException) : Item
    }

    private val reads = LinkedBlockingQueue<Item>()
    val readTimeouts = mutableListOf<Int>()
    val readBufferSizes = mutableListOf<Int>()
    val writes = mutableListOf<Pair<ByteArray, Int>>()

    @Volatile
    var writeFailure: IOException? = null

    @Volatile
    var writeGate: CountDownLatch? = null

    @Volatile
    var closed = false
        private set

    fun send(bytes: ByteArray) = reads.put(Item.Data(bytes))

    fun failRead(failure: IOException) = reads.put(Item.Failure(failure))

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        synchronized(readTimeouts) {
            readTimeouts += timeoutMs
            readBufferSizes += buffer.size
        }
        if (closed) throw IOException("Connection closed")
        return when (val item = reads.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
            null -> if (closed) throw IOException("Connection closed") else 0
            is Item.Data -> {
                item.bytes.copyInto(buffer)
                item.bytes.size
            }
            is Item.Failure -> throw item.failure
        }
    }

    override fun write(bytes: ByteArray, timeoutMs: Int) {
        writeGate?.let { gate ->
            while (!gate.await(10, TimeUnit.MILLISECONDS)) {
                if (closed) throw IOException("Connection closed")
            }
        }
        writeFailure?.let { failure -> throw failure }
        synchronized(writes) { writes += bytes.copyOf() to timeoutMs }
    }

    override fun close() {
        closed = true
    }
}

/** The Android USB calls of the tests. */
internal class FakeUsbPlatform(var openFailure: Exception? = null) : UsbPlatform {
    val port = FakeUsbPort()
    val openedWith = mutableListOf<Pair<AdapterAddress.UsbSerial, UsbLineSettings>>()

    @Volatile
    var attached = true

    override fun openPort(address: AdapterAddress.UsbSerial, settings: UsbLineSettings): UsbPort {
        openedWith += address to settings
        openFailure?.let { failure -> throw failure }
        return port
    }

    override fun isAttached(address: AdapterAddress.UsbSerial): Boolean = attached
}
