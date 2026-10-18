package org.superdro.platform.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.KeepAliveController
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.KeepAliveSummary

/**
 * The foreground service that keeps the links to the machines up in the background and with the
 * screen off. It is a shell: it holds no connection logic and no copy of the state. The service
 * and the presenters share the `ConnectionManager` singleton of the DI graph.
 *
 * - `foregroundServiceType="connectedDevice"`, `stopWithTask="false"`, no `android:process`.
 * - `onStartCommand` calls `startForeground` at once, also for a null summary, and returns
 *   `START_STICKY`. A sticky restart (a null intent) and the start at boot restore the links.
 * - `onTaskRemoved()` keeps the links: only "Disconnect all" or a
 *   command in the app stops the service.
 * - The notification has one line for each online machine, the number of the offline remembered
 *   machines, the tap that opens `MainActivity` on the tab, and the action "Disconnect all".
 * - The service holds the partial wake lock while one or more links are open.
 * - The UI does not bind to the service: [onBind] gives null.
 *
 * The application object of the app must implement [DIAware]. The graph gives
 * [ConnectionManager], [KeepAliveController], [ConnectionServiceState], [ConnectionServiceHost],
 * [ServiceTexts], and [WakeLockSetting].
 */
class ConnectionService : Service() {

    private val di: DI by lazy { (application as DIAware).di }
    private val manager: ConnectionManager by lazy { di.direct.instance() }
    private val keepAlive: KeepAliveController by lazy { di.direct.instance() }
    private val serviceState: ConnectionServiceState by lazy { di.direct.instance() }
    private val host: ConnectionServiceHost by lazy { di.direct.instance() }
    private val texts: ServiceTexts by lazy { di.direct.instance() }
    private val wakeLockSetting: WakeLockSetting by lazy { di.direct.instance() }

    private lateinit var scope: CoroutineScope
    private lateinit var wakeLock: WakeLockController
    private var token = 0
    private var content: NotificationContent? = null
    private var fallbackTitle = ""
    private var disconnectAllText = ""
    private var sawSummary = false
    private var restoring = false
    private var restoreJob: Job? = null
    private var stopped = false
    private var inForeground = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        token = serviceState.onCreated()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        wakeLock = WakeLockController(PowerManagerWakeLock(this))
        fallbackTitle = applicationInfo.loadLabel(packageManager).toString()
        // The first notification must go out at once in onStartCommand. Thus the channel and the
        // first notification use the label of the app, and the texts load in the background.
        ConnectionNotificationChannel.createIfMissing(this, fallbackTitle)
        loadTexts()
        collectSummary()
        collectWakeLock()
        log.i { "Service created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = ConnectionServiceRules.commandOf(hasIntent = intent != null, action = intent?.action)
        log.i { "Start command $command (flags $flags)" }
        lastStartId = startId
        stopped = false
        if (!startInForeground()) {
            // A refused restore (the boot or a sticky restart) asks the
            // user to open the app.
            // The scope of the service stops in onDestroy, so the post has its own scope.
            if (command == ServiceCommand.Restore) {
                val context = applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { BootStart.postFallback(context, host, texts) }
            }
            stopNow()
            return START_NOT_STICKY
        }
        when {
            ConnectionServiceRules.stopsAtOnce(command, keepAlive.summary.value) -> {
                if (command == ServiceCommand.DisconnectAll) {
                    log.i { "Disconnect all from the notification" }
                    host.disconnectAll()
                }
                stopNow()
            }
            command == ServiceCommand.Restore -> restore()
        }
        return START_STICKY
    }

    /** A swipe-away from the recent apps list keeps all links. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        log.i { "The task was removed. The links stay up." }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock.release()
        scope.cancel()
        serviceState.onDestroyed(token)
        log.i { "Service destroyed" }
        super.onDestroy()
    }

    /**
     * Loads the texts of `core:resources` on `Dispatchers.IO`: a read of the assets. Then the
     * channel gets its name, and the notification gets the action text. A failure leaves the
     * label of the app.
     */
    private fun loadTexts() {
        scope.launch {
            val loaded = NotificationTexts.load(texts, Dispatchers.IO)
            if (loaded == null) {
                log.w { "The texts of the notification did not load" }
                return@launch
            }
            ConnectionNotificationChannel.create(this@ConnectionService, loaded.channelName)
            disconnectAllText = loaded.disconnectAll
            if (ConnectionServiceRules.repostsAfterTexts(inForeground, stopped)) notifySafely(notification())
        }
    }

    private fun collectSummary() {
        scope.launch {
            keepAlive.summary.collect { summary ->
                if (summary != null) {
                    sawSummary = true
                    showSummary(summary)
                } else if (!restoring && ConnectionServiceRules.stopsForSummary(summary, sawSummary)) {
                    log.i { "No session wants a link" }
                    stopNow()
                }
            }
        }
    }

    private fun collectWakeLock() {
        scope.launch {
            combine(
                keepAlive.summary.map { summary -> summary?.onlineCount ?: 0 },
                wakeLockSetting.enabled(),
            ) { openLinks, settingOn -> openLinks to settingOn }
                .distinctUntilChanged()
                .collect { (openLinks, settingOn) -> wakeLock.update(openLinks, settingOn) }
        }
    }

    /** The sticky restart and the start at boot: connect again to each remembered machine. */
    private fun restore() {
        if (restoreJob?.isActive == true) return
        restoring = true
        restoreJob = scope.launch {
            try {
                manager.restore().join()
                val summary = withTimeoutOrNull(ConnectionServiceRules.RESTORE_SETTLE_MS) {
                    keepAlive.summary.first { value -> value != null }
                }
                if (summary == null) {
                    log.i { "The restore gave no session" }
                    stopNow()
                }
            } finally {
                restoring = false
            }
        }
    }

    private suspend fun showSummary(summary: KeepAliveSummary) {
        content = withContext(Dispatchers.IO) { NotificationContent.of(summary, texts) }
        if (stopped) return
        notifySafely(notification())
    }

    private fun notification(): Notification =
        ConnectionNotifications.service(this, content, fallbackTitle, disconnectAllText.ifEmpty { fallbackTitle }, host)

    /**
     * Calls `startForeground` with the type `connectedDevice`. Gives false when Android refuses it.
     * The type constant is from API 29. `ServiceCompat` does not give the type to API 24 to 28.
     */
    @SuppressLint("InlinedApi")
    private fun startInForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            ConnectionServiceRules.NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        inForeground = true
        serviceState.onForeground(token)
        true
    } catch (error: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31 and higher) is an IllegalStateException.
        log.w(error) { "Android did not let the service go to the foreground" }
        serviceState.onStartRefused(KeepAliveStatus.StartNotAllowed)
        false
    } catch (error: SecurityException) {
        // API 34 and higher: the prerequisite of connectedDevice is not met.
        log.w(error) { "Android refused the type connectedDevice" }
        serviceState.onStartRefused(KeepAliveStatus.PrerequisiteMissing)
        false
    }

    /**
     * Leaves the foreground and stops. `stopSelf(startId)` with the last start ID: when a newer
     * start came already, Android does not stop the service, and that start shows the notification
     * again.
     */
    private fun stopNow() {
        if (stopped) return
        stopped = true
        inForeground = false
        serviceState.onStopping(token)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    // POST_NOTIFICATIONS can be missing (API 33 and higher). The service then still runs, and
    // Android shows it only in the Task Manager.
    @SuppressLint("MissingPermission")
    private fun notifySafely(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(ConnectionServiceRules.NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            log.w(error) { "No permission for the notification" }
        }
    }

    companion object {
        private val log = Arbor.tag("ConnectionService")

        /** Gives an explicit intent for this service with [action]. */
        fun intent(context: Context, action: String): Intent =
            Intent(context, ConnectionService::class.java).setAction(action)
    }
}
