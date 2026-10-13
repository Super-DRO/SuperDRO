package org.superdro.core.di

import org.kodein.di.DI

actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    // Empty on purpose: the Android app binds StoreIOProviderAndroid (see the expect declaration).
}
