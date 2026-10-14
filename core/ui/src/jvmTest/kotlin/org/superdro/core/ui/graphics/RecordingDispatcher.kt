package org.superdro.core.ui.graphics

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * A dispatcher that counts its dispatches and gives each block to [delegate]. A test gives it as the
 * file dispatcher and reads [dispatches] to see that the file IO went to it.
 */
class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {

    /** The number of blocks that went to this dispatcher. */
    var dispatches: Int = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        delegate.dispatch(context, block)
    }
}
