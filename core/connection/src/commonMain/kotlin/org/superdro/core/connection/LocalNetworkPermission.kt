package org.superdro.core.connection

import kotlinx.coroutines.flow.StateFlow

/**
 * The local network permission of the platform, for a server socket on the local network.
 *
 * Android 17 (API 37) and higher has the runtime permission `ACCESS_LOCAL_NETWORK`. The Android
 * platform binds this contract only on these versions, with the state of
 * `NetworkCapabilityMonitor`. A platform that needs no permission does not bind it: the MCP runner
 * then gets null and does not wait for a grant.
 *
 * @property granted True while the permission is granted. The platform reads the permission again
 *   after each permission result.
 */
class LocalNetworkPermission(val granted: StateFlow<Boolean>)
