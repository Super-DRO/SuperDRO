package org.superdro.core.connection

import org.superdro.core.model.AdapterAddress

/** A run-time permission that a link or the service needs. */
enum class PermissionKind {
    /** `BLUETOOTH_CONNECT` (API 31 and higher). */
    BluetoothConnect,

    /** `BLUETOOTH_SCAN` with `neverForLocation` (API 31 and higher). */
    BluetoothScan,

    /** `POST_NOTIFICATIONS` (API 33 and higher). */
    Notifications,

    /** The local network permission, where the Android version needs it. */
    LocalNetwork,

    /** `CAMERA`, before the first camera access and before `IMAGE_CAPTURE`. */
    Camera,
}

/**
 * A request from common code that needs a visible activity. Common code sends it on the
 * [ConnectionRequestManager] bus. `MainActivity` collects the bus and calls the Android API.
 *
 * A connect or a disconnect is not a request: common code calls the connection manager
 * directly, so that a link does not need a live activity.
 */
sealed interface ConnectionRequest {

    /** Show the system dialog for [kinds]. */
    data class RequestPermissions(val kinds: Set<PermissionKind>) : ConnectionRequest

    /** Ask the user to turn on the Bluetooth radio. */
    data object EnableBluetooth : ConnectionRequest

    /** Open the system Bluetooth settings, so that the user can pair a new adapter. */
    data object OpenBluetoothSettings : ConnectionRequest

    /**
     * Ask for the USB permission of the device at [address]. [deviceKey] is the
     * [DiscoveredAdapter.deviceKey] of the selected row, or null. With a key, the request goes to
     * that attached device, also when other devices have the same vendor ID and product ID.
     */
    data class RequestUsbPermission(val address: AdapterAddress.UsbSerial, val deviceKey: String? = null) : ConnectionRequest

    /**
     * Ask the user to exempt SuperDRO from the battery optimization
     * (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). The UI shows the reason first.
     */
    data object RequestIgnoreBatteryOptimizations : ConnectionRequest

    /** Open the system battery optimization settings. */
    data object OpenBatteryOptimizationSettings : ConnectionRequest

    /** Open the system settings of the app, for a permission that the user refused two times. */
    data object OpenAppSettings : ConnectionRequest
}
