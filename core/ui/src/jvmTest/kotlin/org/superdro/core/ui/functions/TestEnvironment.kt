package org.superdro.core.ui.functions

import androidx.compose.ui.ImageComposeScene
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * The resource environment of the system language, for the tests outside of a composition. The
 * function takes it from a small composition, as the entries of the area do.
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
