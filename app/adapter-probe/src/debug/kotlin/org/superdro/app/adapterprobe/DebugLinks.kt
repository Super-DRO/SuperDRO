package org.superdro.app.adapterprobe

import org.superdro.core.connection.TransportFactory
import org.superdro.core.fakeadapter.FakeScenarios
import org.superdro.core.fakeadapter.FakeTcpServer
import org.superdro.core.fakeadapter.FakeTransportFactory

/**
 * The debug menu of the probe app: the fake adapter of `core:fake-adapter`, and the fake
 * adapter behind a local TCP server for the SuperDRO TCP transport. Only a debug build has this
 * file. The release build has a copy with no fake adapter.
 */
object DebugLinks {

    /** True: this build has the fake adapter. */
    const val available: Boolean = true

    private val fakes = FakeTransportFactory()
    private var server: FakeTcpServer? = null

    /** The transport factory of the link type `Fake`. */
    val factory: TransportFactory? = fakes

    /** The names of the fake adapter scenarios. */
    val scenarios: List<String> = FakeScenarios.all.map { it.name }

    /** Starts the fake adapter of [scenario] behind a TCP server on 127.0.0.1:8080. Gives the port, or null. */
    suspend fun startTcpServer(scenario: String): Int? {
        stopTcpServer()
        val adapter = fakes.adapter(scenario) ?: return null
        val next = FakeTcpServer(adapter)
        return try {
            next.start().also { server = next }
        } catch (failure: Exception) {
            next.close()
            null
        }
    }

    /** Stops the fake TCP server. */
    fun stopTcpServer() {
        server?.close()
        server = null
    }
}
