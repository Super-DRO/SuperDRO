package org.superdro.platform.android.bluetooth

import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.PermissionKind
import org.superdro.core.model.AdapterAddress

/**
 * The rules of the SPP link with no Android type, so that a JVM unit test can examine them.
 */
object SppRules {

    /** API 30 (Android 11): `BluetoothDevice.getAlias()` is public. */
    const val ALIAS_API: Int = 30

    /** API 31 (Android 12): the run-time permissions `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN`. */
    const val RUNTIME_PERMISSION_API: Int = 31

    /** The manifest name of `BLUETOOTH_CONNECT`. */
    const val BLUETOOTH_CONNECT: String = "android.permission.BLUETOOTH_CONNECT"

    /** The manifest name of `BLUETOOTH_SCAN`. */
    const val BLUETOOTH_SCAN: String = "android.permission.BLUETOOTH_SCAN"

    /**
     * Gives the name of a paired adapter: `getAlias()` on API 30 and higher, else `getName()`.
     * SuperDRO uses no reflection. A blank value goes to the next one: the alias, the name, then
     * the Bluetooth address.
     */
    fun displayName(sdkInt: Int, alias: String?, name: String?, address: String): String {
        val first = if (sdkInt >= ALIAS_API) alias else null
        return first?.takeIf { text -> text.isNotBlank() }
            ?: name?.takeIf { text -> text.isNotBlank() }
            ?: address
    }

    /**
     * True when the transport can call `cancelDiscovery()` before the connect: with
     * `BLUETOOTH_SCAN` on API 31 and higher, with `BLUETOOTH_ADMIN` on API 30 and lower.
     */
    fun mayCancelDiscovery(sdkInt: Int, hasScanPermission: Boolean, hasAdminPermission: Boolean): Boolean =
        if (sdkInt >= RUNTIME_PERMISSION_API) hasScanPermission else hasAdminPermission

    /** True when [address] is in [pairedAddresses]. The compare ignores the case of the hex digits. */
    fun isPaired(pairedAddresses: Collection<String>, address: String): Boolean =
        pairedAddresses.any { paired -> paired.equals(address, ignoreCase = true) }

    /**
     * Gives the reason why a link cannot open, or null when it can open. The order of the checks:
     * no Bluetooth adapter ([LinkFailure.RadioOff]), no `BLUETOOTH_CONNECT`
     * ([LinkFailure.PermissionMissing]), the radio is off ([LinkFailure.RadioOff]), and the
     * address is not paired ([LinkFailure.DeviceGone]).
     */
    fun linkProblem(hasAdapter: Boolean, hasConnectPermission: Boolean, radioOn: Boolean, paired: Boolean): LinkFailure? =
        when {
            !hasAdapter -> LinkFailure.RadioOff
            !hasConnectPermission -> LinkFailure.PermissionMissing
            !radioOn -> LinkFailure.RadioOff
            !paired -> LinkFailure.DeviceGone
            else -> null
        }

    /**
     * The start of the `IOException` message of `BluetoothSocket.connect()` when the Bluetooth stack
     * closes the socket before the link opens, for example "read failed, socket might closed or
     * timeout, read ret: -1". The Android source of API 34 has this text. A page timeout gives this
     * text, but an SDP failure and an RFCOMM refusal also give it.
     */
    const val SOCKET_CLOSED_MESSAGE: String = "read failed, socket might closed or timeout"

    /** The value of `BluetoothSocketException.L2CAP_ACL_FAILURE` (API 34): the ACL link did not open. */
    const val L2CAP_ACL_FAILURE: Int = 2

    /**
     * Gives the link failure of an RFCOMM connect that failed.
     *
     * Android gives no public page-timeout code for an RFCOMM socket. Thus the Android socket
     * monitors `ACTION_ACL_CONNECTED` for the adapter during the connect. [aclConnected] is true when
     * an ACL link to the adapter came up, false when no ACL link came up, and null when the platform
     * cannot tell.
     *
     * - The error code [L2CAP_ACL_FAILURE] is [LinkFailure.NoAnswer].
     * - The message [SOCKET_CLOSED_MESSAGE] with no ACL link is [LinkFailure.NoAnswer]: the adapter
     *   did not answer the page, because it is off or out of range.
     * - Each other failure is [LinkFailure.Refused]. This includes a failure after the adapter
     *   answered, and a failure when the platform cannot tell.
     *
     * @param message The message of the exception of `connect()`.
     * @param socketErrorCode The `BluetoothSocketException` error code on API 34 and higher, or null.
     */
    fun connectFailure(message: String?, socketErrorCode: Int?, aclConnected: Boolean?): LinkFailure = when {
        socketErrorCode == L2CAP_ACL_FAILURE -> LinkFailure.NoAnswer
        aclConnected != false -> LinkFailure.Refused
        message?.startsWith(SOCKET_CLOSED_MESSAGE) == true -> LinkFailure.NoAnswer
        else -> LinkFailure.Refused
    }

    /**
     * Gives the Bluetooth capability for the SPP and the BLE links: no
     * adapter or a radio that is off is [LinkFailure.RadioOff], and no `BLUETOOTH_CONNECT` is
     * [LinkFailure.PermissionMissing].
     */
    fun capability(hasAdapter: Boolean, hasConnectPermission: Boolean, radioOn: Boolean): LinkCapability =
        when (val problem = linkProblem(hasAdapter, hasConnectPermission, radioOn, paired = true)) {
            null -> LinkCapability.Available
            else -> LinkCapability.Unavailable(problem)
        }

    /** One paired device, as `getBondedDevices()` gives it. */
    data class PairedDevice(val address: String, val alias: String?, val name: String?)

    /**
     * Gives the rows of the add-device list for the paired devices: all devices, no filter
     * and no scan. The name comes from [displayName]. The detail is the Bluetooth address. The rows
     * are in the order of the name, then the address.
     */
    fun pairedAdapters(sdkInt: Int, devices: Collection<PairedDevice>): List<DiscoveredAdapter> =
        devices
            .map { device ->
                DiscoveredAdapter(
                    address = AdapterAddress.BluetoothSpp(device.address),
                    displayName = displayName(sdkInt, device.alias, device.name, device.address),
                    detail = device.address,
                )
            }
            .sortedWith(compareBy({ adapter -> adapter.displayName.lowercase() }, { adapter -> adapter.detail }))

    /**
     * Gives the manifest permissions of a Bluetooth [kind] on [sdkInt]. Before API 31 the
     * install-time permissions `BLUETOOTH` and `BLUETOOTH_ADMIN` apply, so the list is empty. A kind
     * that is not a Bluetooth kind gives an empty list.
     */
    fun manifestPermissions(kind: PermissionKind, sdkInt: Int): List<String> = when {
        sdkInt < RUNTIME_PERMISSION_API -> emptyList()
        kind == PermissionKind.BluetoothConnect -> listOf(BLUETOOTH_CONNECT)
        kind == PermissionKind.BluetoothScan -> listOf(BLUETOOTH_SCAN)
        else -> emptyList()
    }
}
