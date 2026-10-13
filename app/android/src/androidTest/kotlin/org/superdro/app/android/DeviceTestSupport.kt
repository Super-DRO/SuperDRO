package org.superdro.app.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.kodein.di.direct
import org.kodein.di.instance
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.model.TabState
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore
import org.superdro.platform.android.service.ConnectionServiceRules
import org.superdro.platform.android.service.ConnectionServiceState
import org.superdro.platform.android.service.ServicePhase

/** The helpers of the device tests. The tests run in the app process. */
internal object DeviceTestSupport {

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val application: SuperDroApplication get() = context.applicationContext as SuperDroApplication

    inline fun <reified T : Any> get(): T = application.di.direct.instance()

    inline fun <reified T : Any> get(tag: Any): T = application.di.direct.instance(tag = tag)

    /** Runs [command] in the shell of the emulator and gives its output. */
    fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream -> stream.readBytes().decodeToString() }
    }

    fun grant(permission: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, permission)
    }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Waits until [condition] is true, or fails after [timeoutMs]. */
    fun waitUntil(message: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50L)
        }
        if (!condition()) fail("Timeout after $timeoutMs ms: $message")
    }

    /**
     * Removes all sessions, waits until the service stops, and clears the remembered machines. The
     * app does not ask for the battery exemption, so that no system dialog covers the tests.
     */
    fun reset() {
        get<ConnectionManager>().close()
        val state = get<ConnectionServiceState>()
        waitUntil("The service stops", timeoutMs = 10_000L) { state.phase.value == ServicePhase.Stopped }
        runBlocking {
            val tabs = get<TabStore>()
            tabs.awaitLoaded()
            tabs.update { TabState() }
            tabs.awaitWrites()
            val settings = get<AppSettingsStore>()
            settings.awaitLoaded()
            settings.update { value -> value.copy(batteryExemptionAsked = true, fullScreen = true) }
            settings.awaitWrites()
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ConnectionServiceRules.BOOT_FALLBACK_NOTIFICATION_ID)
    }

    /** The lines of the wake locks that the system holds now: the section "Wake Locks: size=N" of `dumpsys power`. */
    fun heldWakeLocks(): List<String> {
        val lines = shell("dumpsys power").lines()
        val start = lines.indexOfFirst { line -> line.startsWith("Wake Locks: size=") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { line -> line.startsWith("  ") }
    }

    /** The notification of the connection service, or null. */
    fun serviceNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { status -> status.id == ConnectionServiceRules.NOTIFICATION_ID }?.notification

    /** The notification with [id], or null. */
    fun notification(id: Int): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { status -> status.id == id }?.notification
}
