package org.superdro.app.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The start of [MainActivity] after the DI graph is ready.
 *
 * The first read of the graph makes the graph: the connection manager, the repositories, the
 * database driver, the transport factories, and the monitors. The activity makes the graph on a
 * background thread, and then calls [start] on the main thread. Until then, the content of the
 * activity is empty, and each call of [whenReady] waits in a queue.
 *
 * The class has no Android type, so that a JVM unit test can examine it. Call all functions on the
 * main thread.
 */
class StartGate {

    private val mutableReady = MutableStateFlow(false)

    /** False until [start] ran. The activity shows its content only when the value is true. */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    private val waiting = ArrayDeque<() -> Unit>()

    /**
     * Runs [startActivity], then each action of [whenReady] in the order of the calls. A second
     * call does nothing.
     */
    fun start(startActivity: () -> Unit) {
        if (ready.value) return
        startActivity()
        mutableReady.value = true
        while (waiting.isNotEmpty()) waiting.removeFirst().invoke()
    }

    /** Runs [action] at once after [start]. Before [start], [action] waits in the queue. */
    fun whenReady(action: () -> Unit) {
        if (ready.value) action() else waiting.addLast(action)
    }
}
