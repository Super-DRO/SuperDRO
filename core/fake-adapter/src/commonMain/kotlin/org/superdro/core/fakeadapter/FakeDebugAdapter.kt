package org.superdro.core.fakeadapter

import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.WantedLink
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * The [DebugAdapter] of the debug builds. It adds the
 * link type `Fake` through [transportFactory] and [discovery], and the two fake machines of the
 * demo entry.
 */
class FakeDebugAdapter(clock: Clock = MonotonicClock()) : DebugAdapter {

    private var on = false

    override val active: Boolean get() = on

    override fun applySwitch(on: Boolean?) {
        if (on != null) {
            this.on = on
            log.d { "Demo mode: $on" }
        }
    }

    override val transportFactory: TransportFactory = FakeTransportFactory(clock)

    override val discovery: AdapterDiscovery = object : AdapterDiscovery {
        override val kind: TransportKind = TransportKind.Fake
        override val adapters: Flow<List<DiscoveredAdapter>> = flowOf(
            FakeScenarios.all.map { scenario ->
                DiscoveredAdapter(AdapterAddress.Fake(scenario.name), displayName = "Fake: ${scenario.name}", detail = FakeAdapter.MODEL)
            },
        )
    }

    override val demoLinks: List<WantedLink> = listOf(
        WantedLink(DeviceId(DEMO_MILL_ID), "Demo mill", AdapterAddress.Fake(FakeScenarios.DemoMill.name)),
        WantedLink(DeviceId(DEMO_LATHE_ID), "Demo lathe", AdapterAddress.Fake(FakeScenarios.DemoLathe.name)),
    )

    companion object {
        const val DEMO_MILL_ID: String = "demo-mill"
        const val DEMO_LATHE_ID: String = "demo-lathe"

        private val log = Arbor.tag("FakeAdapter")
    }
}

/** Binds the fake adapter over the [DebugAdapter] seam. `app:android` imports it in debug builds only. */
val fakeAdapterModule: DI.Module = DI.Module("fakeAdapterModule") {
    bindSingleton<DebugAdapter> { FakeDebugAdapter() }
}
