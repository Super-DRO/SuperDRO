package org.superdro.core.ui.sound

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.MutableStateFlow
import org.superdro.core.connection.DeviceId
import org.superdro.core.model.AppSettings
import org.superdro.core.util.MonotonicClock

/**
 * The sound controller of the composition. The app gives the controller of the DI graph. The
 * default plays nothing, for the previews and the screenshot tests.
 */
val LocalSoundController = staticCompositionLocalOf {
    SoundController(NoUiSounds, MutableStateFlow(AppSettings()), MutableStateFlow(emptySet()), MonotonicClock())
}

/**
 * The feedback of a key press: haptic feedback, and the short or the long sound
 * when the sounds of the machine are not muted.
 */
class KeyFeedback internal constructor(
    private val haptics: HapticFeedback,
    private val sounds: SoundController,
    private val device: DeviceId?,
) {
    /** A tap on a button or a key. */
    fun tap() {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        sounds.tap(device)
    }

    /** An accepted long press. */
    fun longPress() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        sounds.longPress(device)
    }

    /** A refused action. */
    fun error() {
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        sounds.error(device)
    }
}

/** Gives the key feedback of the machine [device], or of the app for null. */
@Composable
fun rememberKeyFeedback(device: DeviceId? = null): KeyFeedback {
    val haptics = LocalHapticFeedback.current
    val sounds = LocalSoundController.current
    return remember(haptics, sounds, device) { KeyFeedback(haptics, sounds, device) }
}
