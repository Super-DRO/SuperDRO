package org.superdro.core.ui.tabs.adddevice

/**
 * Gives true when the add-device sheet shows the hint for `rfcomm bind`.
 *
 * The desktop app on Linux opens a Bluetooth SPP adapter through a serial port of the system
 * (`AdapterAddress.BluetoothSpp.serialPort`). The user binds the adapter to that port first with
 * `rfcomm bind`, so the sheet shows the hint only there. Android opens the RFCOMM socket itself.
 */
expect fun platformRfcommBindHint(): Boolean
