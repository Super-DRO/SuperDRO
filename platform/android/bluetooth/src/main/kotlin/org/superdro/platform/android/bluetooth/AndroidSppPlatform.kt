package org.superdro.platform.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.superdro.core.connection.LinkFailure

/** Gives the Bluetooth adapter of the device, or null when the device has no Bluetooth. */
internal fun Context.bluetoothAdapter(): BluetoothAdapter? =
    getSystemService(BluetoothManager::class.java)?.adapter

/**
 * The Android Bluetooth calls of [SppTransport]. Each call that needs
 * `BLUETOOTH_CONNECT` comes after the check of [Spp.hasConnectPermission]. A `SecurityException`
 * goes to the transport, which makes it `LinkFailure.PermissionMissing`.
 */
@SuppressLint("MissingPermission")
internal class AndroidSppPlatform(context: Context) : SppPlatform {

    private val context: Context = context.applicationContext

    override fun linkProblem(mac: String): LinkFailure? {
        val adapter = context.bluetoothAdapter()
        val permitted = Spp.hasConnectPermission(context)
        val radioOn = adapter != null && adapter.isEnabled
        val paired = adapter != null && permitted && radioOn && pairedDevice(adapter, mac) != null
        return SppRules.linkProblem(hasAdapter = adapter != null, hasConnectPermission = permitted, radioOn = radioOn, paired = paired)
    }

    override fun cancelDiscoveryIfPermitted() {
        if (!Spp.mayCancelDiscovery(context)) return
        try {
            context.bluetoothAdapter()?.cancelDiscovery()
        } catch (_: SecurityException) {
            // The permission went away after the check. The connect can go on without it.
        }
    }

    override fun createSocket(mac: String): SppSocket {
        val adapter = context.bluetoothAdapter() ?: throw IOException("This device has no Bluetooth adapter.")
        val device = pairedDevice(adapter, mac) ?: throw IOException("The device $mac is not paired.")
        // The insecure RFCOMM socket with the SPP UUID. No secure socket, no reflection.
        return AndroidSppSocket(device.createInsecureRfcommSocketToServiceRecord(Spp.SERVICE_UUID))
    }

    private fun pairedDevice(adapter: BluetoothAdapter, mac: String): BluetoothDevice? =
        adapter.bondedDevices.orEmpty().firstOrNull { device -> device.address.equals(mac, ignoreCase = true) }
}

/** An [SppSocket] on a `BluetoothSocket`. `close()` stops a `connect()` and a `read()` that block. */
@SuppressLint("MissingPermission")
internal class AndroidSppSocket(private val socket: BluetoothSocket) : SppSocket {
    override fun connect() = socket.connect()
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun close() = socket.close()
}
