package org.superdro.core.connection.tcp

import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.model.AdapterAddress

// The test reads the virtual time of the test scheduler.
@OptIn(ExperimentalCoroutinesApi::class)
class TcpConnectLimitJvmTest {

    @Test
    fun theLimitOf5000MsHoldsWhenTheNameLookupBlocksAThread() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val transport = TcpTransport(
            AdapterAddress.Tcp("dro.local"),
            TcpConnector { _, _ ->
                // A name lookup that blocks the thread and does not see the cancel.
                Thread.sleep(2000)
                error("The name dro.local is not known.")
            },
            executor.asCoroutineDispatcher(),
        )
        val realStart = System.nanoTime()
        val failure = assertFailsWith<LinkFailureException> { transport.open() }
        val realMs = (System.nanoTime() - realStart) / 1_000_000
        assertEquals(LinkFailure.Refused, failure.failure)
        assertEquals(5000L, testScheduler.currentTime)
        assertTrue(realMs < 1500, "The open waited $realMs ms for the blocked thread.")
        transport.close()
        executor.shutdownNow()
    }
}
