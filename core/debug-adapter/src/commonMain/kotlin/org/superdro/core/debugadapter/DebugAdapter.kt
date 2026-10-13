package org.superdro.core.debugadapter

import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.TransportFactory
import org.superdro.core.connection.WantedLink
import org.superdro.core.model.TransportKind

/**
 * The seam that lets a debug build use a simulated adapter. The fake adapter replaces the
 * transport, not the session: the session, the parser, and the input stage are the real code.
 *
 * The seam adds the link type [TransportKind.Fake] through a [TransportFactory] and an
 * [AdapterDiscovery] in the DI graph. `core:ui` sees only this seam interface, not
 * `core:fake-adapter`.
 *
 * The simulated adapter is in `core:fake-adapter`. Only `debugImplementation` puts that module on
 * a class path, so no part of it gets into a release APK. A release build binds [NoDebugAdapter].
 */
interface DebugAdapter {

    /** True while the demo mode is on. Always false in a release build. */
    val active: Boolean

    /**
     * Sets the demo mode. Null means "no change": for example a restored activity. The activity
     * reads the switch from its launch intent, so the seam has no Android type.
     */
    fun applySwitch(on: Boolean?)

    /**
     * The transport factory of the fake adapter for the link type [TransportKind.Fake], or null in
     * a release build. `core:di` adds it to the factory map of the connection manager.
     */
    val transportFactory: TransportFactory?

    /** The discovery that adds the fake adapters to the add-device list, or null in a release build. */
    val discovery: AdapterDiscovery? get() = null

    /** The machines of the demo entry: two fake tabs. Empty in a release build. */
    val demoLinks: List<WantedLink>
}

/**
 * Gives [factories] with the factory of the link type [TransportKind.Fake] added, when the build
 * has a fake adapter. A release build gives [factories] with no change.
 */
fun DebugAdapter.withFakeTransport(factories: Map<TransportKind, TransportFactory>): Map<TransportKind, TransportFactory> {
    val fake = transportFactory ?: return factories
    return factories + (TransportKind.Fake to fake)
}

/** The release binding. No simulated adapter exists, so each member does nothing. */
object NoDebugAdapter : DebugAdapter {

    override val active: Boolean = false

    override fun applySwitch(on: Boolean?) = Unit

    override val transportFactory: TransportFactory? = null

    override val discovery: AdapterDiscovery? = null

    override val demoLinks: List<WantedLink> = emptyList()
}
