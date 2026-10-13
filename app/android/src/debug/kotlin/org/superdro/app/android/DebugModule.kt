package org.superdro.app.android

import org.kodein.di.DI
import org.superdro.core.fakeadapter.fakeAdapterModule

/**
 * The debug bindings. The release source set has a file with the same name that binds
 * NoDebugAdapter. The fake adapter is in core:fake-adapter, which only debugImplementation puts
 * on the class path.
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeAdapterModule)
}
