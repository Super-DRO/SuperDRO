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
