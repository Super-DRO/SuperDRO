package org.superdro.core.ui.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tells if a "Camera setup" screen is open. While the setup is
 * open, the video of the camera screen and of the backdrop stops, so that only one view streams
 * (`VideoPlayRules.onSetupOpened`). The app has one object.
 */
class CameraSetupGate {
    private val count = MutableStateFlow(0)
    private val _open = MutableStateFlow(false)

    /** True while one or more setup screens are open. */
    val open: StateFlow<Boolean> = _open.asStateFlow()

    /** A setup screen opened. */
    fun opened() {
        count.update { value -> value + 1 }
        _open.value = count.value > 0
    }

    /** A setup screen closed. */
    fun closed() {
        count.update { value -> (value - 1).coerceAtLeast(0) }
        _open.value = count.value > 0
    }
}
