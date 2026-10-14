package org.superdro.core.ui.adapter.encoder.wizard

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.superdro.core.functions.errormap.ErrorMapBuild
import org.superdro.core.functions.errormap.ErrorMapBuilder
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.RecordedPass
import org.superdro.core.functions.errormap.SessionPass

/**
 * The map builds of the review screens (the report and the diagnostics).
 *
 * - The build runs on [buildContext] (`Dispatchers.Default` in the app), in the coroutine of
 *   the caller. When the caller stops (for example, the screen closes), the build stops.
 * - A build for a different session stops the build that runs.
 * - A second request for the same session waits for the build that runs.
 * - The last result stays in memory. A request for the same session gives it and does not
 *   build again. A fingerprint of the session (64 bits) identifies the session, so the cache
 *   does not keep the pairs.
 *
 * @param builder The map builder. Tests can replace it.
 */
internal class ReviewBuilds(
    private val buildContext: CoroutineContext,
    private val builder: (List<RecordedPass>, RecordedPass?, () -> Unit) -> ErrorMapBuild = ErrorMapBuilder::build,
) {

    /** The identity of a session: the adapter, the input, the number of pairs, and a hash of all values. */
    private data class Key(val adapterId: String, val input: String, val pairs: Int, val hash: Long)

    /** A build that runs. [done] completes when the build stops, with or without a result. */
    private class Running(val key: Key, val owner: Job, val done: CompletableJob)

    private val lock = Mutex()
    private var cachedKey: Key? = null
    private var cachedBuild: ErrorMapBuild? = null
    private var running: Running? = null

    /** Gives the build of [passes] and [verification], the passes of [session]. */
    suspend fun build(session: ErrorMapWizardSession, passes: List<RecordedPass>, verification: RecordedPass?): ErrorMapBuild {
        val key = keyOf(session)
        val owner = currentCoroutineContext().job
        var mine: Running? = null
        while (mine == null) {
            var wait: Job? = null
            lock.withLock {
                val cached = cachedBuild
                if (cached != null && cachedKey == key) return cached
                val other = running
                if (other != null && other.key == key && other.done.isActive) {
                    wait = other.done
                } else {
                    if (other != null && other.done.isActive) other.owner.cancel()
                    mine = Running(key, owner, Job()).also { running = it }
                }
            }
            wait?.join()
        }
        val current = checkNotNull(mine)
        try {
            val result = withContext(buildContext) {
                val context = currentCoroutineContext()
                builder(passes, verification) { context.ensureActive() }
            }
            lock.withLock {
                cachedKey = key
                cachedBuild = result
            }
            return result
        } finally {
            current.done.complete()
            withContext(NonCancellable) {
                lock.withLock { if (running === current) running = null }
            }
        }
    }

    private fun keyOf(session: ErrorMapWizardSession): Key {
        var hash = 0x6A09E667F3BCC909L
        fun mix(value: Long) {
            hash = (hash + value) * -0x61c8864680b583ebL
            hash = hash xor (hash ushr 31)
        }
        fun mix(pass: SessionPass?) {
            if (pass == null) return mix(-1L)
            mix(pass.targets.size.toLong())
            pass.targets.forEach { mix(it) }
            pass.references.forEach { mix(it) }
            pass.timesMs.forEach { mix(it) }
            mix(if (pass.truncated) 1L else 0L)
            mix(pass.glitchesDropped.toLong())
        }
        mix(session.reference?.hashCode()?.toLong() ?: -1L)
        mix(session.passes.size.toLong())
        session.passes.forEach { mix(it) }
        mix(session.verification)
        val pairs = session.passes.sumOf { it.targets.size } + (session.verification?.targets?.size ?: 0)
        return Key(session.adapterId, session.input, pairs, hash)
    }
}
