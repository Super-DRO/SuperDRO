package org.superdro.platform.android.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.toxicbakery.logging.Arbor

/**
 * The Wi-Fi lock while the MCP server runs. The lock turns off the Wi-Fi power save, so that an
 * inbound request gets a quick answer. The controller takes the lock when the server starts and
 * releases it when the server stops. The service calls [release] in `onDestroy`.
 *
 * The class has no Android type, so that a JVM unit test can examine it. [WifiLockRules] gives the
 * mode of the lock.
 */
class WifiLockController(private val handle: WakeLockHandle) {

    /** True while the controller holds the lock. */
    var isHeld: Boolean = false
        private set

    /** Gives the state of the MCP server. */
    fun update(serverRuns: Boolean) {
        when {
            serverRuns && !isHeld -> {
                handle.acquire()
                isHeld = true
                log.i { "Wi-Fi lock taken for the MCP server" }
            }
            !serverRuns && isHeld -> release()
        }
    }

    /** Releases the lock if the controller holds it. */
    fun release() {
        if (!isHeld) return
        handle.release()
        isHeld = false
        log.i { "Wi-Fi lock released" }
    }

    private companion object {
        val log = Arbor.tag("WifiLock")
    }
}

/** The mode rule of the Wi-Fi lock, with no Android type. */
object WifiLockRules {

    /** API 29 (Android 10): the first API level with the mode `WIFI_MODE_FULL_LOW_LATENCY`. */
    const val LOW_LATENCY_API: Int = 29

    /**
     * True when the lock uses the mode `WIFI_MODE_FULL_LOW_LATENCY`. Android applies this mode
     * only while the screen is on and the app is in the foreground. A DRO tablet in use has the
     * screen on. Below API 29 the lock uses `WIFI_MODE_FULL_HIGH_PERF`.
     */
    fun lowLatency(sdkInt: Int): Boolean = sdkInt >= LOW_LATENCY_API
}

/**
 * The Android [WakeLockHandle] of the Wi-Fi lock: one `WifiManager.WifiLock` that is not
 * reference counted. A device with no Wi-Fi gives no lock: [acquire] then does nothing.
 */
class WifiManagerWifiLock(context: Context) : WakeLockHandle {

    private val lock: WifiManager.WifiLock? =
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(modeOf(Build.VERSION.SDK_INT), TAG)
            ?.apply { setReferenceCounted(false) }

    override fun acquire() {
        lock?.acquire()
    }

    override fun release() {
        if (lock?.isHeld == true) lock.release()
    }

    private companion object {
        const val TAG = "SuperDRO:mcp"

        @Suppress("DEPRECATION")
        fun modeOf(sdkInt: Int): Int =
            if (WifiLockRules.lowLatency(sdkInt)) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
