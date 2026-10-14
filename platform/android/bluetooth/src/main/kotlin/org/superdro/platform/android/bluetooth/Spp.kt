package org.superdro.platform.android.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import org.superdro.core.model.TransportKind

/**
 * The values of the Bluetooth Classic SPP link.
 * [SppTransport] uses them.
 */
object Spp {

    /** The link type of this module. */
    val kind: TransportKind = TransportKind.BluetoothSpp

    /** The Serial Port Profile UUID of the insecure RFCOMM socket. */
    val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** A write that does not end in this time is `LinkFailure.Lost`. */
    const val WRITE_WATCHDOG_MS: Long = 4000L

    /**
     * After a connect fails with no `ACTION_ACL_CONNECTED` for the adapter, the socket waits this
     * time for a late broadcast. Then it reports that the adapter did not answer.
     */
    const val ACL_GRACE_MS: Long = 500L

    /** The size of the read buffer. */
    const val READ_BUFFER_BYTES: Int = 1024

    /** The BEL byte. While one BEL byte waits, the transport does not queue a second one. */
    const val BEL: Byte = 0x07

    /**
     * True when the app can connect: `BLUETOOTH_CONNECT` on API 31 and higher. Before API 31 the
     * install-time permission `BLUETOOTH` is enough.
     */
    fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)

    /**
     * True when the app can call `cancelDiscovery()`: `BLUETOOTH_SCAN` on API 31 and higher,
     * `BLUETOOTH_ADMIN` on API 30 and lower.
     */
    fun mayCancelDiscovery(context: Context): Boolean = SppRules.mayCancelDiscovery(
        sdkInt = Build.VERSION.SDK_INT,
        hasScanPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN),
        hasAdminPermission = isGranted(context, Manifest.permission.BLUETOOTH_ADMIN),
    )

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
