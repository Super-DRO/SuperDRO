package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.superdro.core.store.StoreIOProvider
import org.superdro.core.store.StoreIOProviderJvm

actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    // A lambda, not `bindProviderOf(::StoreIOProviderJvm)`: the constructor has a parameter with
    // a default (the data directory, for tests), and a constructor reference makes Kodein look
    // for a File binding.
    bindProvider<StoreIOProvider> { StoreIOProviderJvm() }
}
