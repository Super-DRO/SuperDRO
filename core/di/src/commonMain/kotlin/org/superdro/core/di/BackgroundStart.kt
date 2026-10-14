package org.superdro.core.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.bindEagerSingleton
import org.kodein.di.instance
import org.kodein.di.provider

/**
 * Makes the singleton [T] in the background when the graph starts.
 *
 * The singleton of [T] starts its work when the graph makes it. The graph does not make it at once
 * on the thread that reads the graph first (on Android, the main thread in `MainActivity.onCreate`).
 * An eager binding with the tag `backgroundStart:<T>` launches the make of [T] in the [APP_SCOPE]
 * (`Dispatchers.Default`). A read of [T] before this launch makes [T] on the thread of the read, as
 * before. Each Kodein singleton has its own lock, so the graph makes one object only.
 *
 * Use it only for a singleton that the first frame does not need.
 */
internal inline fun <reified T : Any> DI.Builder.bindBackgroundStart() {
    bindEagerSingleton<Job>(tag = "backgroundStart:${T::class.simpleName}") {
        startInBackground(scope = instance(tag = APP_SCOPE), make = provider<T>())
    }
}

/** Launches [make] in [scope]. The call does not wait for [make]. */
internal fun startInBackground(scope: CoroutineScope, make: () -> Any): Job = scope.launch { make() }
