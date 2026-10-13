package org.superdro.core.ui.tabs

import kotlin.jvm.Synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.ConnectionRequestManager
import org.superdro.core.connection.PermissionKind
import org.superdro.core.store.json.AppSettingsStore

/**
 * Sends a [ConnectionRequest] to the activity (a platform contract). A sheet or a dialog closes
 * at once after a request, so the request must not go out in the scope of its entry:
 * [ConnectionRequestManager.send] waits until the activity has the request.
 */
fun interface RequestSender {
    /** Sends [request]. The function does not wait. */
    fun send(request: ConnectionRequest)
}

/** The [RequestSender] of the app: it sends each request in [scope], a scope of the app. */
class AppRequestSender(private val manager: ConnectionRequestManager, private val scope: CoroutineScope) : RequestSender {
    override fun send(request: ConnectionRequest) {
        scope.launch { manager.send(request) }
    }
}

/**
 * The request of the local network permission at the first TCP connect. The add-device
 * sheet asks one time. `AppSettings.localNetworkPermissionAsked` keeps the
 * flag, so a restart does not ask again.
 *
 * The other requests of the first connect have one flow in the activity of the app: the
 * notification permission, then one battery optimization request with its reason text
 * (`AppSettings.notificationPermissionAsked` and `batteryExemptionAsked`).
 */
class FirstConnectRequests(private val settings: AppSettingsStore) {

    /** Gives true one time: when the app did not ask for the local network permission yet. */
    suspend fun claimLocalNetwork(): Boolean {
        settings.awaitLoaded()
        if (settings.settings.value.localNetworkPermissionAsked) return false
        settings.update { current -> current.copy(localNetworkPermissionAsked = true) }
        return true
    }

    companion object {
        /** The permission of the local network. */
        val LOCAL_NETWORK: Set<PermissionKind> = setOf(PermissionKind.LocalNetwork)
    }
}
