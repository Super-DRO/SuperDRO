package org.superdro.platform.android.service

import android.os.Process
import com.toxicbakery.logging.Arbor
import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable

/**
 * The coroutine dispatcher of the link reads and writes. The connection
 * manager gets it with the DI tag `"linkIo"`.
 *
 * - The threads have a high priority: the target is `THREAD_PRIORITY_URGENT_DISPLAY` (−8). When
 *   Android refuses the target, the thread uses `THREAD_PRIORITY_DISPLAY` (−4). A device test reads
 *   the final value.
 * - The pool makes a new thread when all threads are busy, and stops a thread after
 *   [keepAliveSeconds] with no work. Each open SPP link blocks one thread with its read, and each USB
 *   link holds one thread for its read loop. Thus a small fixed pool is not correct.
 *
 * @param setPriority Sets the priority of the calling thread. A JVM test gives a fake function.
 */
class LinkIoDispatcher(
    private val setPriority: (Int) -> Unit = { priority -> Process.setThreadPriority(priority) },
    keepAliveSeconds: Long = KEEP_ALIVE_SECONDS,
) : CoroutineDispatcher(), Closeable {

    private val threadNumber = AtomicInteger()

    @Volatile
    private var lastPriority: Int? = null

    private val executor = ThreadPoolExecutor(
        0,
        Int.MAX_VALUE,
        keepAliveSeconds,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { task ->
        Thread(
            {
                lastPriority = LinkThreadPriority.apply(setPriority)
                task.run()
            },
            "superdro-link-${threadNumber.incrementAndGet()}",
        ).apply { isDaemon = true }
    }

    /** The priority that the last new thread got, or null when no thread started or Android refused both values. */
    val appliedPriority: Int? get() = lastPriority

    /** The number of threads that the pool has now. */
    val threadCount: Int get() = executor.poolSize

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Only after close(): the work goes to the IO dispatcher, so that a coroutine does not stop.
            Dispatchers.IO.dispatch(context, block)
        }
    }

    /** Stops the threads after their work. Only the tests call this function. */
    override fun close() {
        executor.shutdown()
    }

    override fun toString(): String = "LinkIoDispatcher"

    companion object {
        /** `THREAD_PRIORITY_URGENT_DISPLAY`: the target priority of a link thread. */
        const val TARGET_PRIORITY: Int = -8

        /** `THREAD_PRIORITY_DISPLAY`: the lowest priority that a link thread can have. */
        const val MINIMUM_PRIORITY: Int = -4

        /** The time that a thread with no work stays in the pool. */
        const val KEEP_ALIVE_SECONDS: Long = 60L

        /** The DI tag of the dispatcher. */
        const val DI_TAG: String = "linkIo"
    }
}

/** The priority rule of a link thread with no Android type, so that a JVM unit test can examine it. */
object LinkThreadPriority {

    private val log = Arbor.tag("LinkIoDispatcher")

    /**
     * Sets [LinkIoDispatcher.TARGET_PRIORITY] with [setPriority]. When that fails, sets
     * [LinkIoDispatcher.MINIMUM_PRIORITY]. Gives the priority that the thread got, or null when
     * both calls failed.
     */
    fun apply(setPriority: (Int) -> Unit): Int? {
        for (priority in listOf(LinkIoDispatcher.TARGET_PRIORITY, LinkIoDispatcher.MINIMUM_PRIORITY)) {
            try {
                setPriority(priority)
                return priority
            } catch (error: RuntimeException) {
                log.w(error) { "The link thread did not get the priority $priority" }
            }
        }
        return null
    }
}
