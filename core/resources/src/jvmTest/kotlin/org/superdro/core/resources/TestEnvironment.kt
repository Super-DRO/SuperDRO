package org.superdro.core.resources

import androidx.compose.ui.ImageComposeScene
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * The resource environment of the system language, for the tests outside of a composition.
 *
 * `getString()` with no environment reads the screen resolution through AWT. On a computer with
 * no display, AWT throws `HeadlessException`. This environment comes from a small composition
 * that draws in memory, so it needs no display.
 */
val TestEnvironment: ResourceEnvironment by lazy {
    var environment: ResourceEnvironment? = null
    val scene = ImageComposeScene(width = 8, height = 8) { environment = rememberResourceEnvironment() }
    try {
        scene.render(0L)
    } finally {
        scene.close()
    }
    checkNotNull(environment) { "No resource environment." }
}
