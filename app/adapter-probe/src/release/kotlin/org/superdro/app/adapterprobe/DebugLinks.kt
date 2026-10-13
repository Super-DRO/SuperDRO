package org.superdro.app.adapterprobe

import org.superdro.core.connection.TransportFactory

/** The release form of the debug menu: no fake adapter. `core:fake-adapter` is not on the release class path. */
object DebugLinks {

    /** False: this build has no fake adapter. */
    const val available: Boolean = false

    /** No transport factory of the link type `Fake`. */
    val factory: TransportFactory? = null

    /** No fake adapter scenario. */
    val scenarios: List<String> = emptyList()

    /** Does nothing in a release build. */
    suspend fun startTcpServer(scenario: String): Int? = null

    /** Does nothing in a release build. */
    fun stopTcpServer() = Unit
}
