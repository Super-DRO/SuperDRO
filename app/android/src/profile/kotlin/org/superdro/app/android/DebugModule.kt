package org.superdro.app.android

import org.kodein.di.DI
import org.superdro.core.fakeadapter.fakeAdapterModule

/**
 * The bindings of the profile build types (nonMinifiedRelease and benchmarkRelease) that the
 * baselineProfile plugin makes from release. The profile generator starts the app with the demo
 * extra, so the two demo machines connect and the live readout shows. The release build type does
 * not compile this file: it binds NoDebugAdapter (src/release), and the demo extra has no effect.
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeAdapterModule)
}
