package org.superdro.app.android

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.superdro.core.debugadapter.DebugAdapter
import org.superdro.core.debugadapter.NoDebugAdapter

/**
 * The release bindings. The fake adapter is only in the debug source set, so this build binds
 * NoDebugAdapter.
 */
internal val debugModule = DI.Module("debugModule") {
    bindSingleton<DebugAdapter> { NoDebugAdapter }
}
