package org.superdro.core.ui.adapter.encoder.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.functions.errormap.ErrorMapBuild
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.RecordedPass
import org.superdro.core.functions.errormap.SamplePair

/** The cache and the cancellation of the map builds of the review screens. */
class ReviewBuildsTest {

    private fun session(offset: Long): ErrorMapWizardSession = ErrorMapWizardSession.of(
        adapterId = "a1",
        input = 'x',
        referenceInput = 'w',
        passes = listOf(RecordedPass((0L until 10L).map { SamplePair(it, it + offset, it) })),
        verification = null,
    )

    private fun ErrorMapWizardSession.passes(): List<RecordedPass> = passes.map { it.toPass() }

    /** A fake builder that records each call and runs [during] in the build. */
    private class FakeBuilder {
        val calls = ArrayList<Long>()
        var cancelled = 0
        var during: (Long, () -> Unit) -> Unit = { _, _ -> }

        fun build(passes: List<RecordedPass>, verification: RecordedPass?, checkCancelled: () -> Unit): ErrorMapBuild {
            val offset = passes.first().pairs.first().reference
            calls += offset
            try {
                during(offset, checkCancelled)
                checkCancelled()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
            return ErrorMapBuild.NotEnoughData
        }
    }

    private fun TestScope.builds(fake: FakeBuilder): ReviewBuilds =
        ReviewBuilds(StandardTestDispatcher(testScheduler), fake::build)

    @Test
    fun theSameSessionBuildsOnlyOnce() = runTest {
        val fake = FakeBuilder()
        val builds = builds(fake)
        val first = session(1)
        builds.build(first, first.passes(), null)
        builds.build(session(1), first.passes(), null)
        assertEquals(listOf(1L), fake.calls)
        builds.build(session(2), session(2).passes(), null)
        assertEquals(listOf(1L, 2L), fake.calls, "A different session builds again.")
    }

    @Test
    fun aNewReviewStopsTheOldBuild() = runTest {
        val fake = FakeBuilder()
        val builds = builds(fake)
        val old = session(1)
        val new = session(2)
        fake.during = { offset, _ ->
            if (offset == 1L) launch(start = CoroutineStart.UNDISPATCHED) { builds.build(new, new.passes(), null) }
        }
        val oldJob = launch { builds.build(old, old.passes(), null) }
        advanceUntilIdle()
        assertTrue(oldJob.isCancelled)
        assertEquals(1, fake.cancelled)
        assertEquals(listOf(1L, 2L), fake.calls)
        builds.build(new, new.passes(), null)
        assertEquals(listOf(1L, 2L), fake.calls, "The new build is in the cache.")
    }

    @Test
    fun aClosedScreenStopsItsBuildAndKeepsNoResult() = runTest {
        val fake = FakeBuilder()
        val builds = builds(fake)
        val one = session(1)
        lateinit var screen: Job
        fake.during = { _, _ -> screen.cancel() }
        screen = launch { builds.build(one, one.passes(), null) }
        advanceUntilIdle()
        assertTrue(screen.isCancelled)
        assertEquals(1, fake.cancelled)
        fake.during = { _, _ -> }
        builds.build(one, one.passes(), null)
        assertEquals(listOf(1L, 1L), fake.calls, "The stopped build gave no result, so the next review builds again.")
    }

    @Test
    fun aSecondScreenWaitsForTheBuildOfTheSameSession() = runTest {
        val fake = FakeBuilder()
        val builds = builds(fake)
        val one = session(1)
        val results = ArrayList<ErrorMapBuild>()
        launch { results += builds.build(one, one.passes(), null) }
        launch { results += builds.build(one, one.passes(), null) }
        advanceUntilIdle()
        assertEquals(2, results.size)
        assertEquals(listOf(1L), fake.calls)
    }
}
