package org.superdro.platform.android.bluetooth

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.superdro.core.model.AdapterAddress

class SppThreadTest {

    @Test
    fun aConnectMakesNoThreadOutsideTheLinkDispatcher() = runTest(timeout = 60.seconds) {
        withContext(Dispatchers.Default) {
            val factory = object : ThreadFactory {
                val count = AtomicInteger()
                override fun newThread(runnable: Runnable): Thread = Thread(runnable, "spp-link-${count.incrementAndGet()}")
            }
            val executor = Executors.newCachedThreadPool(factory)
            val before = otherThreads()
            repeat(5) {
                val platform = FakeSppPlatform()
                val transport = SppTransport(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), platform, executor.asCoroutineDispatcher())
                transport.open()
                val reader = async { transport.incoming.toList() }
                transport.write("#get:mac;".encodeToByteArray())
                delay(50)
                transport.close()
                withTimeout(3000) { reader.await() }
            }
            executor.shutdown()
            assertEquals(before, otherThreads(), "Only the link dispatcher makes threads.")
        }
    }

    /** The live threads that are not threads of the link dispatcher or of the coroutine library. */
    private fun otherThreads(): Set<String> =
        Thread.getAllStackTraces().keys
            .filter { thread -> thread.isAlive }
            .map { thread -> thread.name }
            .filterNot { name -> name.startsWith("spp-link-") || name.startsWith("DefaultDispatcher") || name.startsWith("kotlinx.coroutines") }
            .toSet()
}
