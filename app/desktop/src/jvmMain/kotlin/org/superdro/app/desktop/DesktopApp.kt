package org.superdro.app.desktop

import app.cash.sqldelight.db.SqlDriver
import com.toxicbakery.logging.Arbor
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.toDeviceRef
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.di.APP_SCOPE
import org.superdro.core.di.LINK_IO_DISPATCHER
import org.superdro.platform.desktop.di.DESKTOP_PLATFORM_SCOPE
import org.superdro.platform.desktop.di.DesktopRequestHandler
import org.superdro.platform.desktop.store.DesktopSecretStore

/**
 * The desktop application object: the part of the Android `Application` and `MainActivity` that
 * is not the window.
 *
 * [start]:
 * - The desktop has no notification permission, no battery optimization, and no local
 *   network permission. The app marks these first-connect items done, so the add-device
 *   sheet sends no request and shows no battery dialog.
 * - It collects the request bus with [DesktopRequestHandler].
 * - It connects each remembered machine that is not paused. With `--demo`, it turns on the
 *   demo mode and connects the two fake machines.
 *
 * The links stay up while the process lives. [close] stops the links and closes the
 * database. The window calls it at the close.
 */
class DesktopApp(
    val options: DesktopOptions,
    override val di: DI = desktopDI(options),
) : DIAware, Closeable {

    /** The window of the app, for the file dialogs. */
    val windowHolder: WindowHolder by instance()

    private val appScope: CoroutineScope by instance(tag = APP_SCOPE)
    private val platformScope: CoroutineScope by instance(tag = DESKTOP_PLATFORM_SCOPE)
    private val linkIo: CoroutineDispatcher by instance(tag = LINK_IO_DISPATCHER)
    private val manager: ConnectionManager by instance()
    private val requests: ConnectionRequestManager by instance()
    private val requestHandler: DesktopRequestHandler by instance()
    private val debugAdapter: DebugAdapter by instance()
    private val appSettings: AppSettingsStore by instance()
    private val driver: SqlDriver by instance()

    private var requestJob: Job? = null

    /** Starts the request collector and the links. Call it one time, before the window. */
    fun start() {
        // The desktop has no notification permission, no battery optimization, and no local network
        // permission. Mark the requests of the first connect as asked, so that no screen asks for them.
        // The update does not wait for the load of the file: the value changes at once, and the
        // load applies the update again on top of the value of the file.
        appSettings.update { current ->
            current.copy(
                notificationPermissionAsked = true,
                batteryExemptionAsked = true,
                localNetworkPermissionAsked = true,
            )
        }
        log.w { DesktopSecretStore.WARNING }
        requestJob = appScope.launch {
            requests.bus.collect { request ->
                // A failure of one request must not stop the collector. A cancellation must stop it.
                runCatching { requestHandler.handle(request) }
                    .onSuccess { action -> log.d { "Request $request: $action" } }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.e(error) { "The request $request failed" }
                    }
            }
        }
        manager.restore()
        if (options.demo) {
            debugAdapter.applySwitch(true)
            debugAdapter.demoLinks.forEach { link -> manager.connect(link.toDeviceRef()) }
        }
        log.i { "SuperDRO desktop started${if (options.demo) " in the demo mode" else ""}" }
    }

    /** Stops the links, the monitors, and the database. A second call does nothing. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        requestJob?.cancel()
        manager.close()
        platformScope.cancel()
        appScope.cancel()
        (linkIo as? Closeable)?.close()
        driver.close()
        log.i { "SuperDRO desktop stopped" }
    }

    private var closed = false

    private companion object {
        val log = Arbor.tag("DesktopApp")
    }
}
