package org.superdro.core.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance

/** [bindBackgroundStart] makes the singleton after the build of the graph, in the app scope. */
class BackgroundStartTest {

    private class Counter {
        var made = 0
    }

    private class Worker(counter: Counter) {
        init {
            counter.made++
        }
    }

    private fun TestScope.graph(counter: Counter): DI = DI {
        bindSingleton<CoroutineScope>(tag = APP_SCOPE) { backgroundScope }
        bindSingleton { counter }
        bindSingleton { Worker(instance()) }
        bindBackgroundStart<Worker>()
    }

    @Test
    fun theBuildOfTheGraphDoesNotMakeTheSingleton() = runTest {
        val counter = Counter()
        graph(counter).direct.instance<Counter>()
        assertEquals(0, counter.made)

        runCurrent()
        assertEquals(1, counter.made)
    }

    @Test
    fun aReadBeforeTheStartGivesTheOneObject() = runTest {
        val counter = Counter()
        val di = graph(counter)
        val first = di.direct.instance<Worker>()
        assertEquals(1, counter.made)

        runCurrent()
        assertEquals(1, counter.made)
        assertSame(first, di.direct.instance<Worker>())
    }

    @Test
    fun startInBackgroundDoesNotWaitForTheMake() = runTest {
        var made = 0
        startInBackground(backgroundScope) { made++ }
        assertEquals(0, made)

        runCurrent()
        assertEquals(1, made)
    }
}
