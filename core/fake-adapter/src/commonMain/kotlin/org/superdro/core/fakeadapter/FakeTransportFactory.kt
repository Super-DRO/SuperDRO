package org.superdro.core.fakeadapter

import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.LinkFailureException
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * The transport factory of the link type `Fake`. It keeps one [FakeAdapter] for each
 * scenario name, so that the adapter state stays from one link to the next (for example a
 * restart after `#reboot:1000;`).
 *
 * @param clock The clock of the fake adapters. A test gives the clock of the test scheduler.
 * @param scenarios Gives the scenario of a name. The default is [FakeScenarios.byName].
 */
class FakeTransportFactory(
    private val clock: Clock = MonotonicClock(),
    private val scenarios: (String) -> FakeScenario? = FakeScenarios::byName,
) : TransportFactory {

    private val adapters = HashMap<String, FakeAdapter>()

    /** Gives the adapter of the scenario [name], or null when the name has no scenario. */
    fun adapter(name: String): FakeAdapter? {
        adapters[name]?.let { return it }
        val scenario = scenarios(name) ?: return null
        return FakeAdapter(scenario, clock).also { adapters[name] = it }
    }

    override fun create(address: AdapterAddress): Transport {
        val fake = address as? AdapterAddress.Fake ?: return RefusedTransport(address, "Not a fake address: $address")
        val adapter = adapter(fake.scenario) ?: return RefusedTransport(address, "No fake scenario \"${fake.scenario}\"")
        return adapter.transport(address)
    }
}

/** A transport whose open always fails with [LinkFailure.Refused]. */
private class RefusedTransport(override val address: AdapterAddress, private val reason: String) : Transport {
    override val incoming = kotlinx.coroutines.flow.emptyFlow<ByteArray>()

    override suspend fun open() {
        throw LinkFailureException(LinkFailure.Refused, reason)
    }

    override suspend fun write(bytes: ByteArray) {
        throw LinkFailureException(LinkFailure.Lost, reason)
    }

    override suspend fun close() = Unit
}
