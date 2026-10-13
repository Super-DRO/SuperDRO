package org.superdro.core.di

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.store.json.TabStore

/**
 * Binds the `StoreIOProvider` of the platform (a platform contract). The JVM module binds
 * `StoreIOProviderJvm`. The Android module is empty: a core module must not depend on a
 * platform module, so the Android app binds `StoreIOProviderAndroid` of `platform:android:store`.
 */
expect val storeIOProviderModule: DI.Module

/** Binds the JSON file stores: the app settings and the tab state. */
val jsonStoreModule = DI.Module("jsonStoreModule") {
    bindSingleton { AppSettingsStore(storeIO = instance(), json = instance()) }
    bindSingleton { TabStore(storeIO = instance(), json = instance()) }
}
