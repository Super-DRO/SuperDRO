package org.superdro.platform.android.service

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.flow.StateFlow
import org.superdro.core.connection.ConnectionKeepAlive
import org.superdro.core.connection.KeepAliveStatus
import org.superdro.core.connection.KeepAliveSummary

/**
 * The Android keep-alive. It starts [ConnectionService] for the first summary that is not null.
 *
 * - A summary that is not null, while no instance runs: the keep-alive checks the prerequisite of
 *   `connectedDevice` ([ForegroundPrerequisite]), then calls `startForegroundService`.
 * - A summary that is not null, while an instance runs: nothing. The service collects the same
 *   summary and updates its notification.
 * - A null summary: nothing. The service stops itself.
 * - `ForegroundServiceStartNotAllowedException`: the sessions continue while the app is visible.
 *   [status] becomes [KeepAliveStatus.StartNotAllowed], and the tab shows a warning.
 *
 * Call [recheck] when the activity comes back to the foreground and after a permission result: a
 * start that Android refused or a prerequisite that was missing can then succeed.
 */
class AndroidConnectionKeepAlive(
    context: Context,
    private val serviceState: ConnectionServiceState,
) : ConnectionKeepAlive {

    private val context: Context = context.applicationContext

    @Volatile
    private var last: KeepAliveSummary? = null

    override val status: StateFlow<KeepAliveStatus> get() = serviceState.status

    override fun update(summary: KeepAliveSummary?) {
        last = summary
        if (summary == null) {
            serviceState.onIdle()
            return
        }
        startIfNeeded(summary)
    }

    /** Tries the start again for the last summary, when no instance runs. */
    fun recheck() {
        last?.let(::startIfNeeded)
    }

    private fun startIfNeeded(summary: KeepAliveSummary) {
        if (serviceState.isActive) return
        if (!prerequisiteMet(summary)) {
            log.w { "No start of the service: the prerequisite of connectedDevice is not met" }
            serviceState.onPrerequisiteMissing()
            return
        }
        serviceState.onStartRequested()
        try {
            ContextCompat.startForegroundService(context, ConnectionService.intent(context, ConnectionServiceRules.ACTION_START))
            log.i { "Start of the service for ${summary.devices.size} machines" }
        } catch (error: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31 and higher) is an IllegalStateException.
            // API 26 to 30 throw an IllegalStateException for a background start of a service.
            log.w(error) { "Android did not let the service start" }
            serviceState.onStartRefused(KeepAliveStatus.StartNotAllowed)
        } catch (error: SecurityException) {
            log.w(error) { "Android refused the service type" }
            serviceState.onStartRefused(KeepAliveStatus.PrerequisiteMissing)
        }
    }

    private fun prerequisiteMet(summary: KeepAliveSummary): Boolean =
        ForegroundPrerequisite.isMet(
            sdkInt = Build.VERSION.SDK_INT,
            bluetoothConnectGranted = isGranted(ForegroundPrerequisite.BLUETOOTH_CONNECT),
            usbDeviceGranted = hasPermittedUsbDevice(),
            changeNetworkStateDeclared = isGranted(ForegroundPrerequisite.CHANGE_NETWORK_STATE),
            transports = summary.devices.map { line -> line.transport },
        )

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasPermittedUsbDevice(): Boolean {
        val manager = context.getSystemService(UsbManager::class.java) ?: return false
        return try {
            manager.deviceList.values.any { device -> manager.hasPermission(device) }
        } catch (_: RuntimeException) {
            false
        }
    }

    private companion object {
        val log = Arbor.tag("ConnectionKeepAlive")
    }
}
