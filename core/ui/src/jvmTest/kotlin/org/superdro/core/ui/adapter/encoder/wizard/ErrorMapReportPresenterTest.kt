package org.superdro.core.ui.adapter.encoder.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.errormap.ErrorMapAdvice
import org.superdro.core.functions.errormap.ErrorMapWizardSession
import org.superdro.core.functions.errormap.HeldOutFolds
import org.superdro.core.functions.errormap.MapProvenance
import org.superdro.core.model.AdapterProfile
import org.superdro.core.protocol.AdapterValue
import org.superdro.core.protocol.AdapterValues
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_advice_worse
import org.superdro.core.resources.encoder_report_saved
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.ScriptedSession
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText

/** The report and the diagnostics of the error map wizard. */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorMapReportPresenterTest {

    private val device = DeviceId("mill")

    private suspend fun TestScope.withAdapter(block: suspend TestScope.(AdapterFixture, AdapterProfile) -> Unit) {
        val fixture = AdapterFixture(this)
        try {
            block(fixture, fixture.machineWithAdapter(device))
        } finally {
            fixture.close()
        }
    }

    private suspend fun AdapterFixture.store(adapter: AdapterProfile, session: ErrorMapWizardSession) =
        store.adapters.saveWizardSession(adapter.id, 'x', ErrorMapWizardSession.serializer(), session)

    @Test
    fun twoGoodPassesGiveAMapThatSaveMapWrites() = runTest {
        withAdapter { fixture, adapter ->
            fixture.store(adapter, WizardTestData.goodSession(adapter.id))
            val presenter = ErrorMapReportPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertNull(state.load)
            val report = assertNotNull(state.report)
            assertTrue(report.canSave)
            assertEquals(2, report.statistics.passCount)
            assertTrue(report.statistics.improvement >= 1.0)

            presenter.onSave()
            advanceUntilIdle()
            assertTrue(presenter.uiState.value.saved)
            assertEquals(UiText.of(Res.string.encoder_report_saved), presenter.uiState.value.message)
            val encoder = fixture.store.adapters.loadAdapterProfile(adapter.id)!!.encoders.getValue('x')
            assertEquals(report.nodes, encoder.errorMap)
            assertNull(
                fixture.store.adapters.loadWizardSession(adapter.id, 'x', ErrorMapWizardSession.serializer()),
                "Save Map deletes the session file.",
            )
            presenter.close()
        }
    }

    @Test
    fun onePassCannotBeSaved() = runTest {
        withAdapter { fixture, adapter ->
            fixture.store(adapter, WizardTestData.onePassSession(adapter.id))
            val presenter = ErrorMapReportPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            val report = assertNotNull(presenter.uiState.value.report)
            assertFalse(report.canSave)
            presenter.onSave()
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.saved)
            assertTrue(fixture.store.adapters.loadAdapterProfile(adapter.id)!!.encoders.getValue('x').errorMap.isEmpty())
            val rows = ErrorMapRows.report(report).map { it.label.english() }
            assertTrue("Expected error (from the same data)" in rows, "One pass gives the interleaved folds (in-sample).")
            presenter.close()
        }
    }

    @Test
    fun aMapThatMakesTheErrorWorseShowsTheAdviceAndCannotBeSaved() = runTest {
        withAdapter { fixture, adapter ->
            val session = ErrorMapWizardSession.of(
                adapterId = adapter.id,
                input = 'x',
                referenceInput = 'w',
                passes = listOf(
                    WizardTestData.roundTrip(0, 95000, 100) { 0 },
                    WizardTestData.roundTrip(0, 95000, 100, 5000) { 0 },
                    WizardTestData.roundTrip(0, 95000, 100, 10000) { WizardTestData.squareWave(it) },
                ),
                verification = null,
            )
            fixture.store(adapter, session)
            val presenter = ErrorMapReportPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            val report = assertNotNull(presenter.uiState.value.report)
            assertTrue(ErrorMapAdvice.MapMakesItWorse in report.advice)
            assertFalse(report.canSave)
            assertEquals(UiText.of(Res.string.encoder_advice_worse), ErrorMapRows.advice(ErrorMapAdvice.MapMakesItWorse))
            presenter.close()
        }
    }

    @Test
    fun noSessionAndTooFewSamplesHaveTheirOwnTexts() = runTest {
        withAdapter { fixture, adapter ->
            val none = ErrorMapReportPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            assertEquals(ReviewLoad.NoSession, none.uiState.value.load)
            none.close()

            val tiny = ErrorMapWizardSession.of(
                adapterId = adapter.id,
                input = 'x',
                referenceInput = 'w',
                passes = listOf(org.superdro.core.functions.errormap.RecordedPass(listOf(org.superdro.core.functions.errormap.SamplePair(5, 6, 1)))),
                verification = null,
            )
            fixture.store(adapter, tiny)
            val notEnough = ErrorMapReportPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            assertEquals(ReviewLoad.NotEnoughData, notEnough.uiState.value.load)
            notEnough.close()
        }
    }

    @Test
    fun theDiagnosticsHaveTheExpectedRows() = runTest {
        withAdapter { fixture, adapter ->
            val session = fixture.connect(ScriptedSession(device))
            session.adapterInfo.value = session.adapterInfo.value.copy(
                values = AdapterValues(version = AdapterValue("1.4.2"), build = AdapterValue("2026-09-01")),
            )
            fixture.store(adapter, WizardTestData.goodSession(adapter.id))
            val presenter = ErrorMapDiagnosticsPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            val diagnostics = assertNotNull(presenter.uiState.value.diagnostics)
            assertEquals("1.0.0", diagnostics.context.appVersion)
            assertEquals("Test adapter", diagnostics.context.adapterName)
            assertEquals("1.4.2, 2026-09-01", diagnostics.context.firmware)
            assertEquals('W', diagnostics.context.referenceInput)
            assertEquals(HeldOutFolds.WholePass, diagnostics.heldOutFolds)
            assertEquals(2, diagnostics.passRows.size)
            val rows = ErrorMapRows.diagnostics(diagnostics).map { it.label.english() to it.value.english() }
            val labels = rows.map { it.first }
            listOf(
                "App version", "Adapter", "Firmware", "Reference input", "Raw error", "Expected error", "Percentile spread",
                "Repeatability", "Improvement", "Linear term", "Direction split", "Periodic error", "Travel ends", "Dead band",
                "Reference drift", "Verification offset", "Zero at", "Nodes", "Node spacing", "Readings for each node", "Readings",
                "Passes", "Held-out data", "Capture at the limit", "Glitches removed", "Pass 1", "Pass 2",
            ).forEach { label -> assertTrue(label in labels, label) }
            assertEquals("Input W", rows.first { it.first == "Reference input" }.second)
            assertEquals("Whole pass", rows.first { it.first == "Held-out data" }.second)
            val text = diagnosticsText("Error map diagnostics: Input X", rows)
            assertTrue(text.startsWith("Error map diagnostics: Input X\nApp version: 1.0.0\n"))
            presenter.close()
        }
    }

    @Test
    fun theSavedMapTextHasTheProvenanceOfTheWizard() = runTest {
        withAdapter { fixture, adapter ->
            fixture.store(adapter, WizardTestData.goodSession(adapter.id))
            val presenter = ErrorMapReportPresenter(device, 'x', fixture.deps)
            advanceUntilIdle()
            val report = assertNotNull(presenter.uiState.value.report)
            val saved = report.saveMap("2026-10-12 08:00:00")
            val text = (saved as org.superdro.core.functions.errormap.WizardSaveResult.Saved).text
            assertTrue(text.startsWith("# ${MapProvenance.KEY_GENERATED_BY}: ${MapProvenance.SUPERDRO_WIZARD}\n# generated-on: 2026-10-12 08:00:00\n# passes: 2\n"))
            presenter.close()
        }
    }
}
