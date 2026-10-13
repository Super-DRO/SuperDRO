package org.superdro.platform.android.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.WantedLinkSource

/**
 * The start at boot (`AutoStart`). The receiver of `BOOT_COMPLETED` starts [ConnectionService] with
 * the restore command, when one or more remembered machines want a link. The service then connects
 * to them with no tap.
 *
 * Android 14 and 15 limit the service types that an app can start from `BOOT_COMPLETED`.
 * `connectedDevice` is not in the list of the refused types. When Android refuses the start, the
 * receiver posts the notification "Open SuperDRO to connect your machines". A tap opens the app,
 * and the app starts the service. The user must open the app one time after the install, so that
 * the app is not in the stopped state.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val di = (context.applicationContext as DIAware).di
        val wanted: WantedLinkSource = di.direct.instance()
        val host: ConnectionServiceHost = di.direct.instance()
        val texts: ServiceTexts = di.direct.instance()
        // Null when a test calls onReceive directly, with no broadcast of the system.
        val pending: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // A read that does not end in time counts as "wanted": the service then decides.
                val anyWanted = withTimeoutOrNull(WANTED_TIMEOUT_MS) { wanted.wantedLinks.first().isNotEmpty() } ?: true
                if (anyWanted) {
                    val started = BootStart.start { ContextCompat.startForegroundService(context, ConnectionService.intent(context, ConnectionServiceRules.ACTION_RESTORE)) }
                    if (!started) BootStart.postFallback(context.applicationContext, host, texts)
                } else {
                    log.i { "Boot: no machine wants a link" }
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        val log = Arbor.tag("BootReceiver")

        /** A part of the 10 s that Android gives to a receiver with `goAsync()`. */
        const val WANTED_TIMEOUT_MS = 5000L
    }
}

/** The start rule of [BootReceiver] and its fallback notification. */
object BootStart {

    private val log = Arbor.tag("BootStart")

    /**
     * Calls [startService]. Gives false when Android refuses the start: an `IllegalStateException`
     * (`ForegroundServiceStartNotAllowedException` on API 31 and higher) or a `SecurityException`.
     */
    fun start(startService: () -> Unit): Boolean = try {
        startService()
        log.i { "Boot: the service starts" }
        true
    } catch (error: IllegalStateException) {
        log.w(error) { "Boot: Android refused the start of the service" }
        false
    } catch (error: SecurityException) {
        log.w(error) { "Boot: Android refused the service type" }
        false
    }

    /** Posts the notification "Open SuperDRO to connect your machines". */
    // Without POST_NOTIFICATIONS (API 33 and higher), Android does not show the notification.
    @SuppressLint("MissingPermission")
    suspend fun postFallback(context: Context, host: ConnectionServiceHost, texts: ServiceTexts) {
        val title = context.applicationInfo.loadLabel(context.packageManager).toString()
        val text = texts.bootOpenApp()
        ConnectionNotificationChannel.create(context, texts.channelName())
        try {
            NotificationManagerCompat.from(context).notify(
                ConnectionServiceRules.BOOT_FALLBACK_NOTIFICATION_ID,
                ConnectionNotifications.bootFallback(context, title, text, host),
            )
        } catch (error: SecurityException) {
            log.w(error) { "No permission for the notification" }
        }
    }
}
