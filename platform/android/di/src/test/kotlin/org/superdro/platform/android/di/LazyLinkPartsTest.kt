package org.superdro.platform.android.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/** The BLE parts make the BlueFalcon engine only at the first BLE link or the first BLE list. */
class LazyLinkPartsTest {

    private class NoTransport(override val address: AdapterAddress) : Transport {
        override val incoming = emptyFlow<ByteArray>()
        override suspend fun open() = Unit
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun close() = Unit
    }

    @Test
    fun theFactoryIsMadeAtTheFirstLinkAndOnlyOneTime() {
        var made = 0
        val factory = LazyTransportFactory {
            made += 1
            TransportFactory { address -> NoTransport(address) }
        }
        assertEquals(0, made)
        val address = AdapterAddress.Ble("AA:BB:CC:DD:EE:FF")
        assertEquals(address, factory.create(address).address)
        factory.create(address)
        assertEquals(1, made)
    }

    @Test
    fun theDiscoveryIsMadeAtTheFirstCollection() = runTest {
        var made = 0
        val row = DiscoveredAdapter(AdapterAddress.Ble("AA:BB:CC:DD:EE:FF"), displayName = "DRO")
        val discovery = LazyAdapterDiscovery(TransportKind.Ble) {
            made += 1
            object : AdapterDiscovery {
                override val kind = TransportKind.Ble
                override val adapters = flowOf(listOf(row))
            }
        }
        assertEquals(TransportKind.Ble, discovery.kind)
        assertEquals(0, made)
        assertEquals(listOf(row), discovery.adapters.first())
        assertEquals(1, made)
    }
}
