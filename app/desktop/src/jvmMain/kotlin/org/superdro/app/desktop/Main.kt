package org.superdro.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.Seedling
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import org.superdro.core.resources.Res
import org.superdro.core.resources.app_link_demo
import org.superdro.core.resources.app_name
import org.superdro.core.ui.App
import org.superdro.core.ui.ProvideDI

/** The size of the window at the start: a 10 inch tablet in landscape. */
const val WINDOW_WIDTH_DP: Int = 1280
const val WINDOW_HEIGHT_DP: Int = 800

/** The class path resource of the window icon: a render of the SuperDRO icon. */
const val ICON_RESOURCE: String = "/superdro_icon.png"

/**
 * The start of the desktop app. The Arbor logs go to the standard
 * output. The function builds the DI graph, starts the links, and shows one window with [App]. The
 * process ends when the window closes.
 */
fun main(args: Array<String>) {
    Arbor.sow(Seedling())
    val options = DesktopOptions.parse(args)
    if (options.help) {
        println(DesktopOptions.USAGE)
        return
    }
    options.unknown.forEach { arg -> log.w { "Unknown argument: $arg" } }
    val app = DesktopApp(options)
    // A stop of the process from outside (for example Ctrl+C in a terminal) also closes the links
    // and the database. A second close does nothing.
    Runtime.getRuntime().addShutdownHook(Thread(app::close, "superdro-shutdown"))
    app.start()
    application(exitProcessOnExit = false) {
        SuperDroWindow(app, onCloseRequest = ::exitApplication)
    }
    app.close()
    exitProcess(0)
}

private val log = Arbor.tag("Main")

/**
 * The window of the app: [App] of `core:ui` with the DI graph of [app]. The title is "SuperDRO",
 * and "SuperDRO – Demo" with `--demo`. With `--exit-after-ms`, the window closes after that time.
 */
@Composable
fun SuperDroWindow(app: DesktopApp, onCloseRequest: () -> Unit) {
    val state = rememberWindowState(size = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp))
    val icon = remember { loadIcon() }
    Window(onCloseRequest = onCloseRequest, state = state, title = windowTitle(app.options.demo), icon = icon) {
        DisposableEffect(window) {
            app.windowHolder.window = window
            onDispose { app.windowHolder.window = null }
        }
        AppContent(app)
    }
    val exitAfter = app.options.exitAfterMs
    if (exitAfter != null) {
        LaunchedEffect(exitAfter) {
            delay(exitAfter)
            log.i { "The window closes after $exitAfter ms (--exit-after-ms)" }
            onCloseRequest()
        }
    }
}

/** The content of the window: the common app frame with the DI graph of [app]. */
@Composable
fun AppContent(app: DesktopApp) {
    ProvideDI(app.di) { App() }
}

/** The window title: the app name, and the name of the demo link type in the demo mode. */
@Composable
fun windowTitle(demo: Boolean): String {
    val name = stringResource(Res.string.app_name)
    return if (demo) "$name – ${stringResource(Res.string.app_link_demo)}" else name
}

/** Loads the window icon from the class path, or gives null when it is not there. */
fun loadIcon(): Painter? =
    DesktopApp::class.java.getResourceAsStream(ICON_RESOURCE)?.use { stream ->
        BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
    }
