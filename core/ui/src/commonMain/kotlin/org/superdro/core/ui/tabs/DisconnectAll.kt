package org.superdro.core.ui.tabs

import org.superdro.core.store.json.TabStore
import org.superdro.core.ui.session.Connections

/**
 * "Disconnect all": the overflow menu of the frame and
 * the action of the notification do the same thing. The Android app binds the command of its
 * connection service host (`ConnectionServiceHost.disconnectAll()`).
 */
fun interface DisconnectAllCommand {
    /** Pauses each remembered machine, then each session goes to `Disconnected`. */
    fun disconnectAll()
}

/**
 * The common [DisconnectAllCommand]: it pauses each remembered machine in [tabStore], so that no
 * restore connects it again, and disconnects each session. The JVM tests and a platform with no
 * connection service use it.
 */
class PauseAndDisconnectAll(private val tabStore: TabStore, private val connections: Connections) : DisconnectAllCommand {
    override fun disconnectAll() {
        tabStore.update { state -> state.copy(remembered = state.remembered.map { machine -> machine.copy(paused = true) }) }
        connections.disconnectAll()
    }
}
