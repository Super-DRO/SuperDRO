package org.superdro.core.connection

import kotlinx.coroutines.flow.Flow
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * Lists the adapters of one link type that the user can add: the paired Bluetooth devices, or
 * the attached USB serial devices. A TCP adapter has no discovery: the user types the host and
 * the port.
 */
interface AdapterDiscovery {

    /** The link type that this discovery lists. */
    val kind: TransportKind

    /**
     * The adapters that the user can add now. The flow gives the current list first, and then a
     * new list at each change (for example a USB attach or detach). A permission that is not granted gives
     * an empty list. [CapabilitySource] reports the permission.
     */
    val adapters: Flow<List<DiscoveredAdapter>>
}

/**
 * One adapter that a discovery found.
 *
 * @property displayName The name to show: for Bluetooth the alias (API 30 and higher) or the
 *   name, for USB the product name.
 * @property detail A second line, for example the Bluetooth address or the USB vendor and product IDs.
 * @property needsPermission True when the user must give a permission before the connect, for
 *   example the USB permission. Until then, a USB address has no serial number.
 * @property deviceKey The key of the system for one attached device, for example the USB device
 *   path, or null. Two USB devices with the same vendor ID and product ID have different keys. The
 *   key is valid only while the device stays attached, so a profile does not keep it.
 */
data class DiscoveredAdapter(
    val address: AdapterAddress,
    val displayName: String,
    val detail: String? = null,
    val needsPermission: Boolean = false,
    val deviceKey: String? = null,
)
