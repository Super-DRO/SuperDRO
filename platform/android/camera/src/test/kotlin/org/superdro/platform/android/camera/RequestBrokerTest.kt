package org.superdro.platform.android.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.superdro.core.camera.CameraTimeouts

/** The tokens and the time limits of the Binder requests. */
class RequestBrokerTest {

    @Test
    fun theTokensStartAtOne() {
        val broker = RequestBroker()
        assertEquals(1, broker.newToken())
        assertEquals(2, broker.newToken())
        assertEquals(3, broker.newToken())
    }

    @Test
    fun aReplyWithTheSameTokenWakesTheRequest() = runTest {
        val broker = RequestBroker()
        val token = broker.newToken()
        val sent = mutableListOf<Int>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            broker.request(token, CameraTimeouts.START_MS) { value -> sent += value }
        }
        assertEquals(listOf(token), sent)
        assertEquals(1, broker.waiting)
        assertFalse(broker.complete(token + 1, CameraReply.StartRefused("other")))
        assertTrue(broker.complete(token, CameraReply.StartAccepted(333_333L)))
        assertEquals(CameraReply.StartAccepted(333_333L), request.await())
        assertEquals(0, broker.waiting)
    }

    @Test
    fun aReplyInsideTheSendDoesNotGoAway() = runTest {
        val broker = RequestBroker()
        val token = broker.newToken()
        val reply = broker.request(token, CameraTimeouts.LIST_MODES_MS) { value ->
            broker.complete(value, CameraReply.Modes(intArrayOf(0)))
        }
        assertTrue(reply is CameraReply.Modes)
    }

    @Test
    fun noReplyInTheTimeLimitGivesNull() = runTest {
        val broker = RequestBroker()
        val token = broker.newToken()
        val start = testScheduler.currentTime
        assertNull(broker.request(token, CameraTimeouts.LIST_MODES_MS) { })
        assertEquals(8_000L, testScheduler.currentTime - start)
        assertFalse(broker.complete(token, CameraReply.Modes(intArrayOf(0))))
    }

    @Test
    fun aDeadProcessReleasesEachRequest() = runTest {
        val broker = RequestBroker()
        val first = async(start = CoroutineStart.UNDISPATCHED) { broker.request(broker.newToken(), 20_000L) { } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broker.request(broker.newToken(), 8_000L) { } }
        assertEquals(2, broker.failAll())
        assertEquals(CameraReply.ProcessDied, first.await())
        assertEquals(CameraReply.ProcessDied, second.await())
        assertEquals(0, broker.failAll())
    }
}
