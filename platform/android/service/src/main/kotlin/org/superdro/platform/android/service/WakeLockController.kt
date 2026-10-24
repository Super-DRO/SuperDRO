package org.superdro.platform.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import com.toxicbakery.logging.Arbor

/** One lock that a controller can take and release: the partial wake lock, or the Wi-Fi lock. */
interface WakeLockHandle {
    fun acquire()
    fun release()
}

/**
 * The partial wake lock while one or more links are open. The app setting
 * turns it off. It is on by default.
 *
 * A link is open while its session is online: `AwaitingData`, `Connected`, or `NoData`. The
 * controller takes the lock for the first open link and releases it when the last link closes or
 * the user turns the setting off. The service calls [release] in `onDestroy`.
 *
 * The class has no Android type, so that a JVM unit test can examine it.
 */
class WakeLockController(private val handle: WakeLockHandle) {

    /** True while the controller holds the lock. */
    var isHeld: Boolean = false
        private set

    /** Gives the number of open links and the value of the setting. */
    fun update(openLinks: Int, settingOn: Boolean) {
        val wanted = settingOn && openLinks > 0
        when {
            wanted && !isHeld -> {
                handle.acquire()
                isHeld = true
                log.i { "Wake lock taken for $openLinks open links" }
            }
            !wanted && isHeld -> release()
        }
    }

    /** Releases the lock if the controller holds it. */
    fun release() {
        if (!isHeld) return
        handle.release()
        isHeld = false
        log.i { "Wake lock released" }
    }

    private companion object {
        val log = Arbor.tag("WakeLock")
    }
}

/**
 * The Android [WakeLockHandle]: one `PARTIAL_WAKE_LOCK` that is not reference counted. The CPU
 * stays on with the screen off, so that the link reads and the timers continue.
 */
class PowerManagerWakeLock(context: Context) : WakeLockHandle {

    private val lock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }

    // The lock has no time limit on purpose: it stays while a link is open. The
    // controller releases it when the last link closes.
    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        lock.acquire()
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TAG = "SuperDRO:links"
    }
}
