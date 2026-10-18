package org.superdro.core.fakeadapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.superdro.core.connection.AdapterIdentity
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSetup
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.SessionState
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.connection.tcp.TcpTransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.IdentitySource
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.protocol.AdapterTiming
import org.superdro.core.util.MonotonicClock

/**
 * The fake adapter behind a local TCP server, with the real `TcpTransport` of `core:connection-tcp`.
 * These tests use real sockets and the real
 * time, so the protocol times are shorter than the normal protocol times.
 */
class FakeTcpServerTest {

    private val timing = AdapterTiming(noDataMs = 1_000L, queueStartMs = 200L)

    private class Setup(val timing: AdapterTiming) : AutoCloseable {
        val clock = MonotonicClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factory = TcpTransportFactory()
        val manager = ConnectionManager(
            factories = mapOf(TransportKind.Tcp to factory),
            capabilities = object : CapabilitySource {
                override val state = MutableStateFlow(CapabilityState())
            },
            wantedLinks = object : WantedLinkSource {
                override val wantedLinks = flowOf(emptyList<org.superdro.core.connection.WantedLink>())
            },
            clock = clock,
            scope = scope,
            linkIoDispatcher = Dispatchers.IO,
            deviceSetups = DeviceSetupSource { flowOf(DeviceSetup(MachineProfile.create(), AdapterProfile())) },
            timing = timing,
        )

        override fun close() {
            manager.close()
            factory.close()
            scope.cancel()
        }
    }

    @Test
    fun aSessionConnectsToTheFakeAdapterThroughTheTcpTransport() = runBlocking {
        Setup(timing).use { setup ->
            val adapter = FakeAdapter(FakeScenarios.Commands, setup.clock)
            FakeTcpServer(adapter, port = 0).use { server ->
                server.start()
                val session = setup.manager.connect(DeviceRef(DeviceId("tcp"), "Router", server.address))
                withTimeout(15_000) { session.adapterInfo.first { info -> info.values.model.value != null && info.identity != null } }
                val info = session.adapterInfo.value
                assertEquals(FakeAdapter.MODEL, info.values.model.value)
                assertEquals(AdapterIdentity("24:6f:28:a1:b2:c5", IdentitySource.BtMac), info.identity)
                assertTrue(session.state.value is SessionState.Connected)
                assertEquals(FakeAdapter.START_X, session.inputs.value.channels.getValue('x').rawCount)
                assertEquals(listOf("#get:mac", "#get:uuid"), adapter.received.take(2))
                setup.manager.disconnect(DeviceId("tcp"))
            }
        }
    }

    @Test
    fun aConnectWithNoServerIsRefused() = runBlocking {
        Setup(timing).use { setup ->
            val serverDispatcher = Dispatchers.IO.limitedParallelism(1)
            val server = FakeTcpServer(FakeAdapter(FakeScenarios.Still), port = 0, dispatcher = serverDispatcher)
            val entered = java.util.concurrent.CountDownLatch(1)
            val port = server.start()
            val busy = setup.scope.launch(serverDispatcher) {
                // Let ActorSelectorManager run processInterests() (registering the channel)
                // and suspend at the yield() inside select() before close() runs.
                kotlinx.coroutines.yield()
                entered.countDown()
                Thread.sleep(200)
            }
            entered.await()
            server.close()
            val session = setup.manager.connect(DeviceRef(DeviceId("none"), "None", AdapterAddress.Tcp("127.0.0.1", port)))
            val state = withTimeout(10_000) { session.state.first { state -> state is SessionState.Reconnecting } }
            busy.join()
            assertEquals(LinkFailure.Refused, (state as SessionState.Reconnecting).cause)
            setup.manager.disconnect(DeviceId("none"))
        }
    }

    @Test
    fun aHalfOpenLinkGoesToNoDataAndTheRecoveryConnectsAgain() = runBlocking {
        Setup(timing).use { setup ->
            val scenario = FakeScenario("half-open", faults = FakeFaults(stopDataAfterMs = 500L))
            val adapter = FakeAdapter(scenario, setup.clock)
            FakeTcpServer(adapter, port = 0).use { server ->
                server.start()
                val session = setup.manager.connect(DeviceRef(DeviceId("half"), "Half", server.address, twoWayCommunication = false))
                val states = ArrayList<SessionState>()
                val watcher = setup.scope.launch { session.state.collect { state -> synchronized(states) { states += state } } }
                withTimeout(15_000) {
                    session.state.first { state -> state is SessionState.Connected }
                    session.state.first { state -> state == SessionState.NoData || state is SessionState.Reconnecting }
                    // The session closes the link and opens it again after 750 ms. The server
                    // accepts the new client, so the data comes again.
                    session.state.first { state -> state is SessionState.Connected }
                }
                watcher.cancel()
                assertEquals(2, adapter.links)
                synchronized(states) {
                    assertTrue(states.any { state -> state is SessionState.Reconnecting && state.cause == LinkFailure.Lost }, "$states")
                }
                setup.manager.disconnect(DeviceId("half"))
            }
        }
    }

    @Test
    fun theDefaultPortIs8080() {
        assertEquals(8080, FakeTcpServer(FakeAdapter(FakeScenarios.Still)).address.port)
    }
}
