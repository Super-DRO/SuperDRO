package org.superdro.platform.android.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings

/**
 * The intents of the Bluetooth requests. `MainActivity` starts them for
 * `ConnectionRequest.OpenBluetoothSettings` and `ConnectionRequest.EnableBluetooth`.
 */
object BluetoothIntents {

    /** The action of the system Bluetooth settings, where the user pairs a new adapter. */
    const val BLUETOOTH_SETTINGS_ACTION: String = Settings.ACTION_BLUETOOTH_SETTINGS

    /** The action of the system dialog that turns on the radio. It needs `BLUETOOTH_CONNECT` on API 31 and higher. */
    const val REQUEST_ENABLE_ACTION: String = BluetoothAdapter.ACTION_REQUEST_ENABLE

    /** Opens the system Bluetooth settings, so that the user can pair a new adapter. */
    fun openBluetoothSettings(): Intent = Intent(BLUETOOTH_SETTINGS_ACTION)

    /** Asks the user to turn on the Bluetooth radio. Start it with `startActivityForResult` or an activity result launcher. */
    fun enableBluetooth(): Intent = Intent(REQUEST_ENABLE_ACTION)
}
