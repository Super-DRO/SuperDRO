package org.superdro.app.adapterprobe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.LogCatSeedling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.superdro.app.adapterprobe.ui.AppPresenter
import org.superdro.app.adapterprobe.ui.ConsolePresenter
import org.superdro.app.adapterprobe.ui.LinkPresenter
import org.superdro.app.adapterprobe.ui.Presenters
import org.superdro.app.adapterprobe.ui.ReportPresenter
import org.superdro.app.adapterprobe.ui.TestPresenter
import org.superdro.app.adapterprobe.ui.TestsPresenter
import org.superdro.core.resources.Res
import org.superdro.core.resources.probe_notification_channel

/** The application of the probe app. It makes the [ProbeGraph] and the notification channel of the probe. */
class ProbeApplication : Application() {

    /** The objects of the probe app. */
    val graph: ProbeGraph by lazy { ProbeGraph(this) }

    /**
     * The presenters of the screens: plain classes with a scope and a StateFlow. They live with the
     * process, so a rotation keeps the options.
     */
    val presenters: Presenters by lazy {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val app = AppPresenter()
        Presenters(
            app = app,
            link = LinkPresenter(graph, scope),
            tests = TestsPresenter(graph, scope),
            test = TestPresenter(graph, app, scope),
            console = ConsolePresenter(graph),
            report = ReportPresenter(graph, scope),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Arbor.forest.isEmpty()) Arbor.sow(LogCatSeedling())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = runBlocking { getString(Res.string.probe_notification_channel) }
            val channel = NotificationChannel(ProbeService.CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
