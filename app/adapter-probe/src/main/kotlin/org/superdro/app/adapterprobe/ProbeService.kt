package org.superdro.app.adapterprobe

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_app_name
import org.superdro.core.resources.probe_notification_text

/**
 * The foreground service of the type `connectedDevice`. It holds the process while a test
 * or the console has a link, so that a test continues while the user changes a radio in the quick
 * settings (T15). The links themselves are in [ProbeGraph]; the service has no link code.
 */
class ProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val texts = runBlocking { getString(Res.string.probe_app_name) to getString(Res.string.probe_notification_text) }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(texts.first)
            .setContentText(texts.second)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID: String = "probe-tests"
        private const val NOTIFICATION_ID = 1
    }
}
