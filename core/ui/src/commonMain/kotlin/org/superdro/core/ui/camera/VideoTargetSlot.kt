package org.superdro.core.ui.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.camera.VideoTarget

/**
 * One video target of a view, with the buffer size that the view set on it.
 *
 * @property width The width that the view gave to the surface buffer (`setFixedSize`), or 0.
 * @property height The height that the view gave to the surface buffer, or 0.
 */
data class SizedTarget(val target: VideoTarget, val width: Int, val height: Int)

/**
 * The video target of the views of one presenter. The video view
 * gives its target when the surface exists and takes it back when the surface goes away. A start
 * waits for a target with the buffer size of the mode, because the camera process draws into a
 * buffer of that size.
 */
class VideoTargetSlot {
    private val _current = MutableStateFlow<SizedTarget?>(null)

    /** The target of the view, or null when the view has no surface. */
    val current: StateFlow<SizedTarget?> = _current.asStateFlow()

    /** The view gives [target] with the buffer size [width] × [height], or null when the surface goes away. */
    fun set(target: VideoTarget?, width: Int, height: Int) {
        _current.value = target?.let { value -> SizedTarget(value, width, height) }
    }

    /** The view [target] goes away. A different target that came later stays. */
    fun remove(target: VideoTarget) {
        if (_current.value?.target === target) _current.value = null
    }

    /**
     * Waits for a target with the buffer size [width] × [height]. After [SIZE_WAIT_MS] it takes the
     * target with each size.
     */
    suspend fun await(width: Int, height: Int): VideoTarget {
        val sized = withTimeoutOrNull(SIZE_WAIT_MS) {
            current.first { slot -> slot != null && slot.width == width && slot.height == height }
        }
        return (sized ?: current.filterNotNull().first()).target
    }

    companion object {
        /** The wait for the buffer size of the mode, in ms. */
        const val SIZE_WAIT_MS: Long = 500L
    }
}
