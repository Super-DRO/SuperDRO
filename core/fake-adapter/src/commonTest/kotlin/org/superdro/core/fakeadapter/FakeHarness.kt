package org.superdro.core.fakeadapter

import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.superdro.core.connection.CapabilitySource
import org.superdro.core.connection.CapabilityState
import org.superdro.core.connection.ConnectionManager
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DeviceRef
import org.superdro.core.connection.DeviceSession
import org.superdro.core.connection.DeviceSetup
import org.superdro.core.connection.DeviceSetupSource
import org.superdro.core.connection.RecordSink
import org.superdro.core.connection.RecordedChunk
import org.superdro.core.connection.ReconnectPolicy
import org.superdro.core.connection.StreamRecorder
import org.superdro.core.connection.WantedLink
import org.superdro.core.connection.WantedLinkSource
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.TransportKind
import org.superdro.core.util.Clock

/** A random source that gives the base reconnect delays exactly. */
class HalfRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextDouble(): Double = 0.5
}

/** The connection manager with the fake adapter and virtual time. */
class FakeHarness(val test: TestScope) {
    val clock: Clock = Clock { test.testScheduler.currentTime }
    val factory = FakeTransportFactory(clock)
    val records = HashMap<Int, ArrayList<RecordedChunk>>()
    val setup = DeviceSetup(MachineProfile.create().copy(adapterConfigurationCommands = true), AdapterProfile())
    val manager = ConnectionManager(
        factories = mapOf(TransportKind.Fake to factory),
        capabilities = object : CapabilitySource {
            override val state = MutableStateFlow(CapabilityState())
        },
        wantedLinks = object : WantedLinkSource {
            override val wantedLinks: Flow<List<WantedLink>> = flowOf(emptyList())
        },
        clock = clock,
        scope = test.backgroundScope,
        linkIoDispatcher = StandardTestDispatcher(test.testScheduler),
        reconnectPolicy = ReconnectPolicy(HalfRandom()),
        deviceSetups = DeviceSetupSource { flowOf(setup) },
        streamRecorder = StreamRecorder { _, _, link ->
            val list = ArrayList<RecordedChunk>().also { records[link] = it }
            object : RecordSink {
                override fun write(chunk: RecordedChunk) {
                    list += chunk
                }

                override fun close() = Unit
            }
        },
    )

    /** Connects a session to the fake adapter of [scenario]. */
    fun connect(scenario: FakeScenario, record: Boolean = false, twoWay: Boolean = true): DeviceSession =
        manager.connect(DeviceRef(DeviceId(scenario.name), scenario.name, AdapterAddress.Fake(scenario.name), twoWay, record))

    /** The fake adapter of [scenario]. */
    fun adapter(scenario: FakeScenario): FakeAdapter = factory.adapter(scenario.name)!!

    /** Records each item of [flow]. */
    fun <T> record(flow: Flow<T>): List<T> {
        val list = ArrayList<T>()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { flow.collect { list += it } }
        return list
    }

    fun now(): Long = test.testScheduler.currentTime
}
