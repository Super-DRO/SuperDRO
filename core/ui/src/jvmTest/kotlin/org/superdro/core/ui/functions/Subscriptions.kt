package org.superdro.core.ui.functions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Collects [flow] in the background scope of the test, as a screen does. A state with
 * `SharingStarted.WhileSubscribed` then runs. The collection starts at once, because
 * `advanceUntilIdle` does not run background work alone. It stops at the end of the test.
 */
fun <T> TestScope.subscribe(flow: Flow<T>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
}
