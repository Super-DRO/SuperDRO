package org.superdro.app.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.SessionState
import org.superdro.platform.desktop.serial.OpenSerialPort
import org.superdro.platform.desktop.serial.SerialLineSettings
import org.superdro.platform.desktop.serial.SerialPortInfo
import org.superdro.platform.desktop.serial.SerialPorts
import org.superdro.platform.desktop.store.DesktopStore

/**
 * The content of the desktop window with the DI graph of the desktop app, with no display.
 * The render writes the PNG files to the folder `app/desktop/build/screenshots`,
 * with the pattern of `ScreenshotRenderer` of `core:ui`.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopWindowTest {

    private val root: File = Files.createTempDirectory("superdro-desktop-window").toFile()
    private val noPorts = object : SerialPorts {
        override fun list(): List<SerialPortInfo> = emptyList()
        override fun open(id: String, settings: SerialLineSettings): OpenSerialPort = error("No serial port in the test.")
    }
    private val apps = mutableListOf<DesktopApp>()

    private fun app(demo: Boolean): DesktopApp {
        val options = DesktopOptions(demo = demo, dataDirectory = File(root, "data-${apps.size}"))
        return DesktopApp(options, desktopDI(options, DesktopStore(options.dataDirectory), serialPorts = { noPorts })).also(apps::add)
    }

    @AfterTest
    fun close() {
        apps.forEach(DesktopApp::close)
        root.deleteRecursively()
    }

    @Test
    fun theWindowOfANewInstallShowsTheEmptyFrame() = runComposeUiTest {
        val app = app(demo = false)
        app.start()
        setContent { AppContent(app) }
        onNodeWithText("No machine is online").assertExists()
        render("desktop-window-empty") { AppContent(app) }
    }

    @Test
    fun theDemoWindowShowsTwoTabsWithLiveValues() = runComposeUiTest {
        val app = app(demo = true)
        app.start()
        setContent { AppContent(app) }
        waitUntil(timeoutMillis = 20_000) {
            onAllNodesWithText("Demo mill").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithText("Demo lathe").fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 20_000) { xValue() != null }
        val first = xValue()
        waitUntil(timeoutMillis = 10_000) { xValue() != null && xValue() != first }

        // The image of the window with the two connected demo machines.
        runBlocking {
            app.di.direct.instance<ConnectionManager>().sessions.value.values.forEach { session ->
                withTimeout(20_000) { session.state.first { state -> state is SessionState.Connected } }
            }
        }
        val file = render("desktop-window-demo", frames = 80) { AppContent(app) }
        assertTrue(file.length() > 0)
    }

    @Test
    fun theTitleNamesTheDemoMode() = runComposeUiTest {
        setContent {
            BasicText(windowTitle(demo = false))
            BasicText(windowTitle(demo = true))
        }
        onNodeWithText("SuperDRO").assertExists()
        onNodeWithText("SuperDRO – Demo").assertExists()
    }

    @Test
    fun theWindowIconIsOnTheClassPath() {
        assertNotNull(loadIcon())
    }

    /** Gives the semantic label of the X value of the readout ("X axis, … millimeters"), or null. */
    private fun androidx.compose.ui.test.ComposeUiTest.xValue(): String? =
        onAllNodes(hasContentDescription("X axis,", substring = true))
            .fetchSemanticsNodes()
            .firstNotNullOfOrNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
            ?.takeIf { text -> text.any(Char::isDigit) }

    /**
     * Draws [content] in a window of 1280 × 800 dp at density 1 and writes `<name>.png`. The function
     * draws [frames] frames with a short wait between them, so that the fonts, the strings, and the
     * values of the sessions can load.
     */
    private fun render(name: String, frames: Int = 30, content: @Composable () -> Unit): File {
        val scene = ImageComposeScene(width = WINDOW_WIDTH_DP, height = WINDOW_HEIGHT_DP, density = Density(1f), content = content)
        val image: Image = try {
            var last = scene.render(0L)
            for (frame in 1..frames) {
                Thread.sleep(FRAME_WAIT_MS)
                last = scene.render(frame * FRAME_NANOS)
            }
            last
        } finally {
            scene.close()
        }
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("The PNG encode of $name failed.")
        val directory = File(System.getProperty("superdro.screenshotDir") ?: "build/screenshots").also(File::mkdirs)
        return File(directory, "$name.png").also { file -> file.writeBytes(data.bytes) }
    }

    private companion object {
        const val FRAME_WAIT_MS = 25L
        const val FRAME_NANOS = 16_000_000L
    }
}
