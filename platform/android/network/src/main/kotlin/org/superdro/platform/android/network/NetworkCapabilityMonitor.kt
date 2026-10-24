package org.superdro.platform.android.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.LinkCapability

/**
 * The network part of the capability source: the network for the TCP links, with the local network
 * permission. `CapabilityState.network` gets [capability].
 *
 * - No network is `LinkFailure.NetworkOff`. The session waits in `WaitingForDevice`.
 * - On API 37 and higher, `ACCESS_LOCAL_NETWORK` that is not granted is
 *   `LinkFailure.PermissionMissing`. The UI then calls [requestPermissionIfNeeded], and
 *   `MainActivity` shows the system dialog. Android sends no broadcast for a permission change:
 *   call [refresh] after the permission result.
 */
class NetworkCapabilityMonitor(context: Context, scope: CoroutineScope) {

    private val context: Context = context.applicationContext
    private val tracker = NetworkTracker(this.context, scope)
    private val _localNetworkGranted = MutableStateFlow(isLocalNetworkGranted())

    /**
     * True while the local network permission is granted. Below API 37 the value is always true.
     * The MCP runner gets this flow on API 37 and higher (`LocalNetworkPermission`), so the server
     * waits for the grant.
     */
    val localNetworkGranted: StateFlow<Boolean> = _localNetworkGranted.asStateFlow()

    /** The capability of the TCP links now. */
    val capability: StateFlow<LinkCapability> =
        combine(tracker.networks, _localNetworkGranted, ::capabilityOf)
            .stateIn(scope, SharingStarted.Eagerly, capabilityOf(tracker.networks.value, _localNetworkGranted.value))

    /** Reads the local network permission again. */
    fun refresh() {
        _localNetworkGranted.value = isLocalNetworkGranted()
    }

    /**
     * Sends `ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))` on the request
     * bus when the Android version needs the permission and the user did not grant it.
     * Send it only while the UI is visible. Gives true when the request went out.
     */
    suspend fun requestPermissionIfNeeded(requests: ConnectionRequestManager): Boolean {
        if (!NetworkRules.localNetworkPermissionNeeded(Build.VERSION.SDK_INT) || isLocalNetworkGranted()) return false
        requests.send(NetworkRules.localNetworkRequest)
        return true
    }

    private fun capabilityOf(networks: List<NetworkSnapshot>, granted: Boolean): LinkCapability =
        NetworkRules.tcpCapability(
            hasNetwork = NetworkRules.hasNetwork(networks),
            localNetworkNeeded = NetworkRules.localNetworkPermissionNeeded(Build.VERSION.SDK_INT),
            localNetworkGranted = granted,
        )

    private fun isLocalNetworkGranted(): Boolean =
        !NetworkRules.localNetworkPermissionNeeded(Build.VERSION.SDK_INT) ||
            ContextCompat.checkSelfPermission(context, NetworkRules.LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
}
