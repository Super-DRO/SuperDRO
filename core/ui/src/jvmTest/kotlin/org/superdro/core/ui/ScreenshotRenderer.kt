package org.superdro.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Draws a composable to a PNG file on the JVM, with no emulator and no display. An agent can then
 * read the image to examine the UI.
 *
 * Use it in a `jvmTest` of `core:ui`:
 *
 * ```kotlin
 * val file = ScreenshotRenderer.render("readout-light", widthDp = 1280, heightDp = 800) {
 *     AppPreview(systemInDarkTheme = false)
 * }
 * ```
 *
 * The files go to `core/ui/build/screenshots/` (the system property `superdro.screenshotDir`,
 * which the build file sets). Run `./gradlew :core:ui:jvmTest`, then read the files.
 */
object ScreenshotRenderer {

    /** The directory of the PNG files. */
    val outputDir: File
        get() = File(System.getProperty("superdro.screenshotDir") ?: "build/screenshots").also { directory -> directory.mkdirs() }

    /**
     * Draws [content] in a window of [widthDp] × [heightDp] at [density] and writes
     * `<name>.png`. The function draws frames with a short wait between them, so that the fonts
     * and the strings of the Compose resources can load. It stops after [QUIET_FRAMES] frames in
     * a row with no invalidation, or after [frames] frames.
     *
     * @return The PNG file.
     */
    fun render(
        name: String,
        widthDp: Int = 1280,
        heightDp: Int = 800,
        density: Float = 1f,
        frames: Int = 20,
        content: @Composable () -> Unit,
    ): File {
        val image = renderImage(widthDp, heightDp, density, frames, content)
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("The PNG encode of $name failed.")
        val file = File(outputDir, "$name.png")
        file.writeBytes(data.bytes)
        return file
    }

    /**
     * Draws [content] and gives the last frame as an image. A resource load runs on a different
     * thread, and the scene has no invalidation while the load runs. Thus the function stops only
     * after [QUIET_FRAMES] frames in a row with no invalidation. [frames] is the upper limit.
     */
    fun renderImage(
        widthDp: Int = 1280,
        heightDp: Int = 800,
        density: Float = 1f,
        frames: Int = 20,
        content: @Composable () -> Unit,
    ): Image {
        val scene = ImageComposeScene(
            width = (widthDp * density).toInt(),
            height = (heightDp * density).toInt(),
            density = Density(density),
            content = content,
        )
        try {
            var image = scene.render(0L)
            var quietFrames = 0
            for (frame in 1..frames) {
                Thread.sleep(FRAME_WAIT_MS)
                quietFrames = if (scene.hasInvalidations()) 0 else quietFrames + 1
                image = scene.render(frame * FRAME_NANOS)
                if (quietFrames >= QUIET_FRAMES) break
            }
            return image
        } finally {
            scene.close()
        }
    }

    private const val FRAME_WAIT_MS = 25L
    private const val FRAME_NANOS = 16_000_000L

    /** The number of frames with no invalidation that ends a render. */
    private const val QUIET_FRAMES = 4
}
