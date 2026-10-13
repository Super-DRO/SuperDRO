package org.superdro.core.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.kodein.di.DI
import org.kodein.di.bindEagerSingleton
import org.kodein.di.bindFactory
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.dataoutput.DataOutputPlatform
import org.superdro.core.dataoutput.KtorListenSocketFactory
import org.superdro.core.dataoutput.ListenSocket
import org.superdro.core.dataoutput.ListenSocketFactory
import org.superdro.core.ui.dataoutput.DataOutputChipPresenter
import org.superdro.core.ui.dataoutput.DataOutputPresenter
import org.superdro.core.ui.dataoutput.DataOutputRunner

/**
 * The bindings of the data output: the one [DataOutputPlatform] of the app with the real
 * `NetworkInterfaces`, the real `SecretStore`, and one `PortRegistry`, the runner of the outputs,
 * and the presenters of the data output screen and of the status chip.
 *
 * The runner is an eager singleton: it starts when the graph is made, in the app scope. Each
 * output runs only while its machine is online. The connection service keeps the links of the
 * online machines, so the outputs run in the service. The service needs no change.
 */
internal val dataOutputDiModule = DI.Module("dataOutputDiModule") {
    bindSingleton {
        DataOutputPlatform(
            // The `NetworkInterfaces` contract gives only bindable addresses (BindAddresses of core:connection).
            networkInterfaces = instance(),
            secretStore = instance(),
            listenSockets = LazyListenSockets { KtorListenSocketFactory(Dispatchers.IO) },
            clock = instance(),
            secretsDispatcher = Dispatchers.IO,
        )
    }
    bindEagerSingleton {
        DataOutputRunner(machines = instance(), connections = instance(), platform = instance())
            .also { runner -> runner.start(instance(tag = APP_SCOPE)) }
    }
    bindProvider {
        DataOutputPresenter(
            runner = instance(),
            machines = instance(),
            networkInterfaces = instance<DataOutputPlatform>().networkInterfaces,
            requests = instance(),
            context = instance(),
        )
    }
    bindFactory<String, DataOutputChipPresenter> { profileId ->
        DataOutputChipPresenter(profileId = profileId, runner = instance(), machines = instance(), requests = instance(), context = instance())
    }
}

/**
 * Makes the socket factory at the first bind, so that a graph with no output opens no selector
 * thread (no server socket while the output is off).
 */
private class LazyListenSockets(create: () -> ListenSocketFactory) : ListenSocketFactory {
    private val factory by lazy(create)

    override suspend fun bind(host: String, port: Int): ListenSocket = factory.bind(host, port)
}
