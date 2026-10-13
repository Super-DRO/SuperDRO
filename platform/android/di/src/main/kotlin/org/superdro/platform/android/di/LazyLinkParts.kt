package org.superdro.platform.android.di

import com.toxicbakery.logging.Arbor
import dev.bluefalcon.core.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.superdro.core.connection.AdapterDiscovery
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.Transport
import org.superdro.core.connection.TransportFactory
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

/**
 * A [TransportFactory] that makes its real factory at the first link. The BLE factory needs the
 * BlueFalcon engine, and the engine registers receivers at its start. Thus the app makes the engine
 * only when a BLE link opens.
 */
class LazyTransportFactory(make: () -> TransportFactory) : TransportFactory {
    private val factory by lazy(make)

    override fun create(address: AdapterAddress): Transport = factory.create(address)
}

/** An [AdapterDiscovery] that makes its real discovery at the first collection, for the same reason. */
class LazyAdapterDiscovery(
    override val kind: TransportKind,
    make: () -> AdapterDiscovery,
) : AdapterDiscovery {
    private val discovery by lazy(make)

    override val adapters: Flow<List<DiscoveredAdapter>> = flow { emitAll(discovery.adapters) }
}

/** The BlueFalcon logger of the app: the messages go to Arbor with the tag "BlueFalcon". */
class BlueFalconLogger : Logger {
    override fun error(message: String, cause: Throwable?) {
        if (cause == null) log.e(message) else log.e(cause) { message }
    }

    override fun warn(message: String, cause: Throwable?) {
        if (cause == null) log.w(message) else log.w(cause) { message }
    }

    override fun info(message: String, cause: Throwable?) {
        if (cause == null) log.i(message) else log.i(cause) { message }
    }

    override fun debug(message: String, cause: Throwable?) {
        if (cause == null) log.d(message) else log.d(cause) { message }
    }

    private companion object {
        val log = Arbor.tag("BlueFalcon")
    }
}
