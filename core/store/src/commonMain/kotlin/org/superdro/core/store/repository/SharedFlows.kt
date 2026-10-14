package org.superdro.core.store.repository

import kotlin.jvm.Synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * One shared flow for each key, for example one flow of a workspace for all its collectors. Then
 * a change of the database causes one read, not one read for each collector.
 *
 * - A shared flow starts with its first collector and stops when its last collector stops. Then
 *   it forgets its value, thus the next collector reads again. Thus [scope] has no work when no
 *   collector exists.
 * - A new collector of a shared flow that runs gets the last value immediately.
 * - A slow collector gets only the last value.
 * - An exception of the upstream flow goes to each collector, as with a flow that is not shared.
 *   The next collector starts the upstream flow again.
 *
 * @param scope The scope of the upstream flows.
 */
internal class SharedFlows<T>(private val scope: CoroutineScope) {

    private val flows = HashMap<String, Flow<T>>()

    /** Gives the shared flow of [key]. The first call makes it from [upstream]. */
    @Synchronized
    fun get(key: String, upstream: () -> Flow<T>): Flow<T> = flows.getOrPut(key) {
        upstream()
            .map { value -> Result.success(value) }
            .catch { error -> emit(Result.failure(error)) }
            .conflate()
            .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 1)
            .map { result -> result.getOrThrow() }
    }
}
