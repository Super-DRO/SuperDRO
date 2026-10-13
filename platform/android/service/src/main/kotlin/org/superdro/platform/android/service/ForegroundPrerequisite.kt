package org.superdro.platform.android.service

import org.superdro.core.model.TransportKind

/**
 * The run-time prerequisite of the foreground service type `connectedDevice`. The rule has no
 * Android type, so that a JVM unit test can examine it.
 *
 * On Android 14 (API 34) and higher, `startForeground` throws a `SecurityException` when the app
 * does not meet one prerequisite. `AndroidConnectionKeepAlive` checks the rule before it starts the
 * service. SuperDRO accepts these prerequisites:
 *
 * 1. `BLUETOOTH_CONNECT` is granted.
 * 2. One attached USB device has a granted USB permission.
 * 3. The app declares `CHANGE_NETWORK_STATE` (a normal permission), and one machine of the summary
 *    has a TCP link. Android accepts the declared permission for each machine. SuperDRO uses it
 *    only for a TCP machine, because a TCP machine has no Bluetooth or USB permission.
 */
object ForegroundPrerequisite {

    /** API 34 (Android 14): the first API level that checks the prerequisite. */
    const val PREREQUISITE_API: Int = 34

    /** The manifest name of `CHANGE_NETWORK_STATE`. */
    const val CHANGE_NETWORK_STATE: String = "android.permission.CHANGE_NETWORK_STATE"

    /** The manifest name of `BLUETOOTH_CONNECT`. */
    const val BLUETOOTH_CONNECT: String = "android.permission.BLUETOOTH_CONNECT"

    /**
     * True when the service can start in the foreground.
     *
     * @param sdkInt The API level of the device.
     * @param bluetoothConnectGranted True when `BLUETOOTH_CONNECT` is granted.
     * @param usbDeviceGranted True when one attached USB device has the USB permission.
     * @param changeNetworkStateDeclared True when the app has `CHANGE_NETWORK_STATE`.
     * @param transports The link types of the machines in the summary.
     */
    fun isMet(
        sdkInt: Int,
        bluetoothConnectGranted: Boolean,
        usbDeviceGranted: Boolean,
        changeNetworkStateDeclared: Boolean,
        transports: Collection<TransportKind>,
    ): Boolean =
        sdkInt < PREREQUISITE_API ||
            bluetoothConnectGranted ||
            usbDeviceGranted ||
            (changeNetworkStateDeclared && TransportKind.Tcp in transports)
}
