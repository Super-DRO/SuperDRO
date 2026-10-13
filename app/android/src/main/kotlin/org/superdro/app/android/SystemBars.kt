package org.superdro.app.android

import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The full screen mode of the app: an app setting, on by
 * default. The app is edge-to-edge in each mode.
 */
object SystemBars {

    /**
     * Hides the status bar and the navigation bar when [fullScreen] is true, else shows them. A swipe
     * from the edge shows the bars for a short time (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`).
     */
    fun apply(window: Window, fullScreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
