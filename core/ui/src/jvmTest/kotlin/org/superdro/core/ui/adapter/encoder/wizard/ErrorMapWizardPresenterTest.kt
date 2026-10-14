package org.superdro.core.ui.adapter.encoder.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.PassRowInfo
import org.superdro.core.functions.errormap.WizardSlot
import org.superdro.core.model.AdapterProfile
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession

/** The presenter of the error map wizard. */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorMapWizardPresenterTest {

    private val device = DeviceId("mill")

    private suspend fun TestScope.withWizard(block: suspend TestScope.(AdapterFixture, AdapterProfile, ScriptedSession) -> Unit) {
        val fixture = AdapterFixture(this)
        try {
            val adapter = fixture.machineWithAdapter(device)
            val session = fixture.connect(ScriptedSession(device))
            block(fixture, adapter, session)
        } finally {
            fixture.close()
        }
    }

    private suspend fun AdapterFixture.storedSession(adapter: AdapterProfile): ErrorMapWizardSession? =
        store.adapters.loadWizardSession(adapter.id, 'x', ErrorMapWizardSession.serializer())

    private fun TestScope.record(session: ScriptedSession, times: LongRange, target: (Long) -> Long, reference: (Long) -> Long) {
        times.forEach { time ->
            session.push(WizardTestData.snapshot(time, 'x' to target(time), 'w' to reference(time)))
            advanceUntilIdle()
        }
    }

    @Test
    fun theReferenceListHasTheOtherInputsThatSentData() = runTest {
        withWizard { fixture, _, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(1, 'x' to 100, 'w' to 5000, 'z' to 7))
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertEquals(listOf('z', 'w'), state.references)
            assertNull(state.reference)
            assertFalse(state.canStartPass, "A pass needs a reference input.")
            assertEquals(0L, state.liveTarget)
            presenter.onReference('w')
            session.push(WizardTestData.snapshot(2, 'x' to 130, 'w' to 5010))
            advanceUntilIdle()
            assertEquals(30L, presenter.uiState.value.liveTarget)
            assertEquals(0L, presenter.uiState.value.liveReference, "The live reference count starts at its first value after the selection.")
            assertTrue(presenter.uiState.value.canStartPass)
            presenter.close()
        }
    }

    @Test
    fun aBurstOfSamplesGivesEachPair() = runTest {
        withWizard { fixture, _, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReference('w')
            presenter.onStartPass()
            advanceUntilIdle()
            // Five frames come before the presenter runs: the conflated inputs keep only the last
            // frame, the sample stream keeps each pair (adapter need 4).
            (1L..5L).forEach { time -> session.push(WizardTestData.snapshot(time, 'x' to time * 10, 'w' to time * 10 + 1)) }
            advanceUntilIdle()
            assertEquals(5, presenter.uiState.value.recordingSamples)
            presenter.close()
        }
    }

    @Test
    fun thePairsPublishTheStateAtMost10TimesInASecond() = runTest {
        withWizard { fixture, _, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReference('w')
            presenter.onStartPass()
            advanceUntilIdle()
            val states = mutableListOf<ErrorMapWizardUiState>()
            val watcher = backgroundScope.launch { presenter.uiState.collect { states += it } }
            runCurrent()
            states.clear()
            // 50 pairs in 50 ms of virtual time: one publication at the end of the interval.
            (1L..50L).forEach { time ->
                session.push(WizardTestData.snapshot(time, 'x' to time * 10, 'w' to time * 10 + 1))
                runCurrent()
                advanceTimeBy(1)
            }
            runCurrent()
            assertEquals(0, states.size, "No publication before the end of the interval.")
            advanceTimeBy(ErrorMapWizardPresenter.PUBLISH_INTERVAL_MS)
            runCurrent()
            assertEquals(1, states.size)
            assertEquals(50, presenter.uiState.value.recordingSamples)
            // An event publishes immediately.
            presenter.onStop()
            assertNull(presenter.uiState.value.recording)
            assertEquals(PassRowInfo(50, 490, 490), presenter.uiState.value.passes[0].info)
            watcher.cancel()
            presenter.close()
        }
    }

    @Test
    fun aPassRecordsThePairsWithEqualTimesAndTheStopSavesTheSession() = runTest {
        withWizard { fixture, adapter, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReference('w')
            presenter.onStartPass()
            assertEquals(WizardSlot.Pass(0), presenter.uiState.value.recording)
            record(session, 1L..10L, { it * 10 }, { it * 10 + 1 })
            // A snapshot with two different times gives no pair.
            session.push(
                org.superdro.core.readout.input.InputSnapshot(
                    channels = WizardTestData.snapshot(11, 'x' to 999).channels + WizardTestData.snapshot(12, 'w' to 999).channels,
                ),
            )
            advanceUntilIdle()
            assertEquals(10, presenter.uiState.value.recordingSamples)
            assertNull(fixture.storedSession(adapter), "The file goes to the store at the stop.")

            presenter.onStop()
            advanceUntilIdle()
            val stored = assertNotNull(fixture.storedSession(adapter))
            assertEquals("W", stored.reference)
            assertEquals(1, stored.passes.size)
            assertEquals((1L..10L).map { it * 10 }, stored.passes[0].targets)
            assertEquals(PassRowInfo(10, 90, 90), presenter.uiState.value.passes[0].info)
            assertTrue(presenter.uiState.value.canStartVerification)
            assertTrue(presenter.uiState.value.canReview)
            presenter.close()
        }
    }

    @Test
    fun theWizardRecordsUpToFivePasses() = runTest {
        withWizard { fixture, _, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReference('w')
            var time = 1L
            repeat(5) {
                presenter.onStartPass()
                record(session, time..time + 2, { it }, { it + 1 })
                time += 3
                presenter.onStop()
                advanceUntilIdle()
            }
            assertEquals(5, presenter.uiState.value.passes.count { it.info != null })
            assertFalse(presenter.uiState.value.canStartPass)
            presenter.onStartVerification()
            assertEquals(WizardSlot.Verification, presenter.uiState.value.recording)
            record(session, time..time + 2, { it }, { it + 2 })
            presenter.onStop()
            advanceUntilIdle()
            assertEquals(3, presenter.uiState.value.verification.info?.sampleCount)
            presenter.onClearPass(0)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.canStartPass)
            presenter.close()
        }
    }

    @Test
    fun reviewSavesTheSessionBeforeTheReportOpens() = runTest {
        withWizard { fixture, adapter, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReview()
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.openReport, "No pass has samples.")
            presenter.onReference('w')
            presenter.onStartPass()
            record(session, 1L..5L, { it }, { it })
            presenter.onReview()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.openReport)
            assertNull(presenter.uiState.value.recording, "Review Map stops the pass that records.")
            assertEquals(5, fixture.storedSession(adapter)?.passes?.single()?.targets?.size)
            presenter.onReportOpened()
            assertFalse(presenter.uiState.value.openReport)
            presenter.close()
        }
    }

    @Test
    fun aStoredSessionAsksToRecoverIt() = runTest {
        withWizard { fixture, adapter, _ ->
            fixture.store.adapters.saveWizardSession(adapter.id, 'x', ErrorMapWizardSession.serializer(), WizardTestData.onePassSession(adapter.id))
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.recovery)
            presenter.onRecover()
            val state = presenter.uiState.value
            assertFalse(state.recovery)
            assertEquals('w', state.reference)
            assertNotNull(state.passes[0].info)
            presenter.close()

            val second = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            assertTrue(second.uiState.value.recovery)
            second.onDropRecovery()
            advanceUntilIdle()
            assertNull(fixture.storedSession(adapter), "A new session deletes the old file.")
            second.close()
        }
    }

    @Test
    fun discardDeletesThePassesAndExitKeepsThem() = runTest {
        withWizard { fixture, adapter, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReference('w')
            presenter.onStartPass()
            record(session, 1L..3L, { it }, { it })
            presenter.onExit()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.exited)
            assertEquals(1, fixture.storedSession(adapter)?.passes?.size, "Exit stops the pass and keeps the file.")

            presenter.onDiscard()
            assertTrue(presenter.uiState.value.confirmDiscard)
            presenter.onConfirmDiscard()
            advanceUntilIdle()
            assertNull(fixture.storedSession(adapter))
            assertTrue(presenter.uiState.value.passes.all { it.info == null })
            presenter.close()
        }
    }

    @Test
    fun theCloseOfTheScreenKeepsAPassThatRecords() = runTest {
        withWizard { fixture, adapter, session ->
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            session.push(WizardTestData.snapshot(0, 'x' to 0, 'w' to 0))
            advanceUntilIdle()
            presenter.onReference('w')
            presenter.onStartPass()
            record(session, 1L..4L, { it * 2 }, { it * 2 })
            presenter.close()
            advanceUntilIdle()
            assertEquals(4, fixture.storedSession(adapter)?.passes?.single()?.targets?.size)
        }
    }

    @Test
    fun aTabWithNoAdapterProfileHasNoWizard() = runTest {
        val fixture = AdapterFixture(this)
        try {
            fixture.machineWithoutAdapter(device)
            val presenter = ErrorMapWizardPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.loaded)
            assertFalse(presenter.uiState.value.available)
            presenter.close()
        } finally {
            fixture.close()
        }
    }
}
