package org.superdro.platform.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        return AndroidSppSocket(context, device.address, device.createInsecureRfcommSocketToServiceRecord(Spp.SERVICE_UUID))
    }

    private fun pairedDevice(adapter: BluetoothAdapter, mac: String): BluetoothDevice? =
        adapter.bondedDevices.orEmpty().firstOrNull { device -> device.address.equals(mac, ignoreCase = true) }
}

/**
 * An [SppSocket] on a `BluetoothSocket`. `close()` stops a `connect()` and a `read()` that block.
 *
 * During `connect()`, the socket monitors `ACTION_ACL_CONNECTED` for [mac]. When the connect fails,
 * it throws an [SppConnectException] that tells if the adapter answered the page.
 */
@SuppressLint("MissingPermission")
internal class AndroidSppSocket(
    private val context: Context,
    private val mac: String,
    private val socket: BluetoothSocket,
) : SppSocket {

    override fun connect() {
        val watch = AclWatch.start(context, mac)
        try {
            socket.connect()
        } catch (failure: IOException) {
            val aclConnected = watch?.aclConnected(Spp.ACL_GRACE_MS)
            throw SppConnectException(failure.message, socketErrorCode(failure), aclConnected, failure)
        } finally {
            watch?.stop()
        }
    }

    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun close() = socket.close()
}

/** Gives the error code of a `BluetoothSocketException` (API 34 and higher), or null. */
private fun socketErrorCode(failure: IOException): Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && failure is BluetoothSocketException) {
        failure.errorCode
    } else {
        null
    }

/**
 * Monitors `ACTION_ACL_CONNECTED` for one adapter. An ACL link that comes up tells that the
 * adapter answered the page.
 */
private class AclWatch private constructor(private val context: Context, private val mac: String) {

    private val connected = CountDownLatch(1)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device?.address.equals(mac, ignoreCase = true)) connected.countDown()
        }
    }

    /** True when an ACL link to the adapter came up. Waits [graceMs] for a late broadcast. */
    fun aclConnected(graceMs: Long): Boolean = try {
        connected.await(graceMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        connected.count == 0L
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // The receiver is not registered.
        }
    }

    companion object {
        /**
         * Starts a watch for [mac], or gives null when the receiver cannot register. The receiver
         * is exported for the reason that [bluetoothBroadcasts] gives.
         */
        fun start(context: Context, mac: String): AclWatch? {
            val watch = AclWatch(context, mac)
            return try {
                ContextCompat.registerReceiver(
                    context,
                    watch.receiver,
                    IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                watch
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
