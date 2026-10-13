package org.superdro.platform.android.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The link dispatcher: a pool of threads with a high
 * priority that grows with the number of blocked links.
 */
class LinkIoDispatcherTest {

    @Test
    fun thePrioritiesAreUrgentDisplayAndDisplay() {
        assertEquals(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY, LinkIoDispatcher.TARGET_PRIORITY)
        assertEquals(android.os.Process.THREAD_PRIORITY_DISPLAY, LinkIoDispatcher.MINIMUM_PRIORITY)
        assertEquals(-8, LinkIoDispatcher.TARGET_PRIORITY)
        assertEquals(-4, LinkIoDispatcher.MINIMUM_PRIORITY)
        assertEquals("linkIo", LinkIoDispatcher.DI_TAG)
    }

    @Test
    fun aThreadGetsTheTargetElseTheMinimumElseNothing() {
        val asked = mutableListOf<Int>()
        assertEquals(-8, LinkThreadPriority.apply { priority -> asked += priority })
        assertEquals(listOf(-8), asked)

        asked.clear()
        val refusesTarget: (Int) -> Unit = { priority ->
            asked += priority
            if (priority == -8) throw SecurityException("not permitted")
        }
        assertEquals(-4, LinkThreadPriority.apply(refusesTarget))
        assertEquals(listOf(-8, -4), asked)

        assertNull(LinkThreadPriority.apply { throw IllegalArgumentException("no") })
    }

    @Test
    fun eachBlockedLinkGetsItsOwnThread() = runBlocking {
        val priorities = mutableListOf<Int>()
        val dispatcher = LinkIoDispatcher(setPriority = { priority -> synchronized(priorities) { priorities += priority } })
        val links = 6
        val allBlocked = CountDownLatch(links)
        val release = CountDownLatch(1)
        try {
            val scope = CoroutineScope(dispatcher)
            val jobs: List<Job> = List(links) {
                scope.launch {
                    // A blocking read of an SPP socket.
                    allBlocked.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(allBlocked.await(5, TimeUnit.SECONDS), "Each link must run at the same time as the others.")
            assertTrue(dispatcher.threadCount >= links)
            release.countDown()
            jobs.joinAll()
            assertEquals(List(dispatcher.threadCount) { -8 }, synchronized(priorities) { priorities.toList() })
            assertEquals(-8, dispatcher.appliedPriority)
            val name = withContext(dispatcher) { Thread.currentThread().name }
            assertTrue(name.startsWith("superdro-link-"), name)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun aClosedDispatcherStillRunsTheWork() = runBlocking {
        val dispatcher = LinkIoDispatcher(setPriority = { })
        dispatcher.close()
        assertEquals(42, withContext(dispatcher) { 42 })
    }
}
