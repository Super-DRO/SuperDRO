package org.superdro.core.debugadapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.WantedLink
import org.superdro.core.model.TransportKind

class NoDebugAdapterTest {

    @Test
    fun theReleaseBindingDoesNothing() {
        NoDebugAdapter.applySwitch(true)
        assertFalse(NoDebugAdapter.active)
        assertNull(NoDebugAdapter.transportFactory)
        assertNull(NoDebugAdapter.discovery)
        assertEquals(emptyList(), NoDebugAdapter.demoLinks)
        val factories = mapOf(TransportKind.Tcp to TransportFactory { error("not used") })
        assertSame(factories, NoDebugAdapter.withFakeTransport(factories))
    }

    @Test
    fun aDebugBuildAddsTheFakeLinkType() {
        val fake = TransportFactory { error("not used") }
        val debug = object : DebugAdapter {
            override val active: Boolean = true
            override fun applySwitch(on: Boolean?) = Unit
            override val transportFactory: TransportFactory = fake
            override val demoLinks: List<WantedLink> = emptyList()
        }
        val tcp = TransportFactory { error("not used") }
        assertEquals(mapOf(TransportKind.Tcp to tcp, TransportKind.Fake to fake), debug.withFakeTransport(mapOf(TransportKind.Tcp to tcp)))
        assertNull(debug.discovery)
    }
}
