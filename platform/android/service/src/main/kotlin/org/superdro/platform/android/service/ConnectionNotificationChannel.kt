package org.superdro.platform.android.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The notification channel of the connection service: the
 * channel "Connections" with `IMPORTANCE_LOW`, so the notification has no sound and no
 * vibration.
 */
object ConnectionNotificationChannel {

    const val ID: String = "connections"

    /** Makes the channel. A second call changes only the name. [name] comes from `core:resources`. */
    fun create(context: Context, name: String) {
        val channel = NotificationChannelCompat.Builder(ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(name)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}

/** Makes the notifications of the connection service. */
object ConnectionNotifications {

    private const val REQUEST_OPEN_APP = 1
    private const val REQUEST_DISCONNECT_ALL = 2
    private const val REQUEST_BOOT_FALLBACK = 3

    /**
     * The one notification for all machines: `setOngoing(true)`, the title, one line for each
     * online machine and the line of the offline machines, the tap that opens the main activity on
     * the tab of [NotificationContent.tapDevice], and the action "Disconnect all".
     *
     * @param content The content, or null before the first summary. The title is then [fallbackTitle].
     */
    fun service(
        context: Context,
        content: NotificationContent?,
        fallbackTitle: String,
        disconnectAllText: String,
        host: ConnectionServiceHost,
    ): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            host.openAppIntent(context, content?.tapDevice),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectAll = PendingIntent.getService(
            context,
            REQUEST_DISCONNECT_ALL,
            ConnectionService.intent(context, ConnectionServiceRules.ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lines = content?.lines.orEmpty()
        val builder = NotificationCompat.Builder(context, ConnectionNotificationChannel.ID)
            .setSmallIcon(R.drawable.superdro_ic_connection)
            .setContentTitle(content?.title ?: fallbackTitle)
            .setContentText(content?.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, disconnectAllText, disconnectAll)
        if (lines.size > 1) {
            val style = NotificationCompat.InboxStyle()
            lines.forEach(style::addLine)
            builder.setStyle(style)
        }
        return builder.build()
    }

    /**
     * The notification "Open SuperDRO to connect your machines":
     * Android refused the start of the service at boot. A tap opens the app, and the app starts the
     * service.
     */
    fun bootFallback(context: Context, title: String, text: String, host: ConnectionServiceHost): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_BOOT_FALLBACK,
            host.openAppIntent(context, null),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, ConnectionNotificationChannel.ID)
            .setSmallIcon(R.drawable.superdro_ic_connection)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }
}
