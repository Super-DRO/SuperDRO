package org.superdro.core.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The bus of the [ConnectionRequest] values. Common code sends a request
 * with [send]. `MainActivity` collects [bus] in its lifecycle scope and calls the Android API. A
 * failure of one request must not stop the collector.
 *
 * The bus has no buffer and no replay: [send] waits until each collector has the request. With
 * no collector, the request goes away. Thus send a request only while the UI is visible.
 */
class ConnectionRequestManager {
    private val _bus = MutableSharedFlow<ConnectionRequest>()

    /** The requests, in the order of [send]. */
    val bus: SharedFlow<ConnectionRequest> = _bus.asSharedFlow()

    /** Sends [request] to the collectors of [bus]. */
    suspend fun send(request: ConnectionRequest) {
        _bus.emit(request)
    }
}
