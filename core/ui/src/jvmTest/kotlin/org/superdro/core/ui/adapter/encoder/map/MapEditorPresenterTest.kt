package org.superdro.core.ui.adapter.encoder.map

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
import org.superdro.core.functions.errormap.MapImportProblem
import org.superdro.core.functions.errormap.MapKind
import org.superdro.core.functions.errormap.MapProblem
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint
import org.superdro.core.resources.Res
import org.superdro.core.resources.encoder_map_clipboard_empty
import org.superdro.core.resources.encoder_map_converted
import org.superdro.core.resources.encoder_map_exported
import org.superdro.core.resources.encoder_map_not_saved
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.adapter.encoder.EncoderSource
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.PickedFile

/** The presenter of the map editor. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapEditorPresenterTest {

    private val device = DeviceId("mill")

    private suspend fun TestScope.withFixture(block: suspend TestScope.(AdapterFixture) -> Unit) {
        val fixture = AdapterFixture(this)
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private suspend fun AdapterFixture.adapterWith(encoder: EncoderProfile): AdapterProfile =
        machineWithAdapter(device, AdapterProfile(alias = "Bridgeport X", encoders = mapOf(encoder.input to encoder)))

    private suspend fun AdapterFixture.stored(adapter: AdapterProfile, input: Char = 'x'): EncoderProfile =
        store.adapters.loadAdapterProfile(adapter.id)!!.encoders.getValue(input)

    @Test
    fun theStoredMapLoadsWithItsProblemList() = runTest {
        withFixture { fixture ->
            val encoder = EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(100, -100)))
            fixture.adapterWith(encoder)
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertEquals(listOf(MapPoint(0, 0), MapPoint(100, -100)), state.rows)
            assertEquals(listOf(MapEditorTexts.problem(MapProblem.DeadBand(0, 100))), state.problems)
            assertEquals("The corrected position does not move between positions 0 and 100.", state.problems.single().english())
            assertTrue(state.wizardAvailable)
            assertFalse(state.dirty)
            presenter.close()
        }
    }

    @Test
    fun rowsChangeAndSaveWritesTheMapInOneWrite() = runTest {
        withFixture { fixture ->
            val adapter = fixture.adapterWith(EncoderProfile.defaultFor('x'))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onAddRow()
            presenter.onRowPosition("10000")
            presenter.onRowValue("4")
            presenter.onSaveRow()
            presenter.onAddRow()
            presenter.onRowPosition("0")
            presenter.onRowValue("x")
            presenter.onSaveRow()
            assertNotNull(presenter.uiState.value.rowEdit?.error, "A value that is not a whole number stays in the dialog.")
            presenter.onRowValue("0")
            presenter.onSaveRow()
            assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4)), presenter.uiState.value.rows, "The table is sorted by position.")
            presenter.onEditRow(1)
            presenter.onRowValue("5")
            presenter.onSaveRow()
            assertTrue(presenter.uiState.value.dirty)
            assertEquals(2, presenter.uiState.value.summary.intervalCount + 1)

            presenter.onSave()
            advanceUntilIdle()
            assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 5)), fixture.stored(adapter).errorMap)
            assertFalse(presenter.uiState.value.dirty)

            presenter.onEditRow(0)
            presenter.onDeleteRow()
            assertEquals(listOf(MapPoint(10000, 5)), presenter.uiState.value.rows)
            presenter.close()
        }
    }

    @Test
    fun anInvalidTableIsNotSaved() = runTest {
        withFixture { fixture ->
            val adapter = fixture.adapterWith(EncoderProfile.defaultFor('x'))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onPaste("0,50\n100,-60\n")
            presenter.onSave()
            advanceUntilIdle()
            assertTrue(fixture.stored(adapter).errorMap.isEmpty())
            assertTrue(presenter.uiState.value.importProblems.isNotEmpty(), "The paste of a reversal is refused at the import.")

            presenter.onAddRow()
            presenter.onRowPosition("0")
            presenter.onRowValue("50")
            presenter.onSaveRow()
            presenter.onAddRow()
            presenter.onRowPosition("100")
            presenter.onRowValue("-60")
            presenter.onSaveRow()
            presenter.onSave()
            advanceUntilIdle()
            assertEquals(listOf(MapEditorTexts.problem(MapProblem.Reversal(0, 100))), presenter.uiState.value.problems)
            assertEquals(UiText.of(Res.string.encoder_map_not_saved), presenter.uiState.value.message)
            assertTrue(fixture.stored(adapter).errorMap.isEmpty())
            presenter.close()
        }
    }

    @Test
    fun theImportChecksTheFileNameAndTheText() = runTest {
        withFixture { fixture ->
            fixture.adapterWith(EncoderProfile.defaultFor('x'))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()

            fixture.files.nextOpen = PickedFile("map.txt", "0,0\n100,1\n".encodeToByteArray())
            presenter.onImport()
            advanceUntilIdle()
            assertEquals(listOf(MapEditorTexts.importProblem(MapImportProblem.ChooseCsvFile)), presenter.uiState.value.importProblems)
            assertEquals(MapEditorPresenter.IMPORT_MIME_TYPES, fixture.files.opened.last())

            fixture.files.nextOpen = PickedFile("map.csv", "position,correction\n0,0\n1.5,2\n100,1\n".encodeToByteArray())
            presenter.onImport()
            advanceUntilIdle()
            assertEquals(
                listOf(MapEditorTexts.importProblem(MapImportProblem.MalformedLine(3)), MapEditorTexts.importProblem(MapImportProblem.DecimalValues)),
                presenter.uiState.value.importProblems,
            )

            fixture.files.nextOpen = PickedFile("MAP.CSV", "# reference input w\nposition,correction\n0,0\n10000,4\n20000,9\n".encodeToByteArray())
            presenter.onImport()
            advanceUntilIdle()
            val state = presenter.uiState.value
            assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9)), state.rows)
            assertTrue(state.importProblems.isEmpty())
            assertEquals(listOf("reference input w"), state.provenance.notes)
            assertTrue(state.dirty)

            fixture.files.nextOpen = PickedFile("big.csv", ByteArray(MapEditorPresenter.MAX_FILE_BYTES + 1) { '1'.code.toByte() })
            presenter.onImport()
            advanceUntilIdle()
            assertEquals(1, presenter.uiState.value.importProblems.size)
            presenter.close()
        }
    }

    @Test
    fun anImportIntoATableWithRowsAsksToReplaceThem() = runTest {
        withFixture { fixture ->
            fixture.adapterWith(EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(500, 1))))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onPaste("0,0\n1000,2\n2000,3\n")
            val question = presenter.uiState.value.replaceQuestion
            assertEquals(ReplaceUi(2, 3, 0, 2000), question)
            presenter.onCancelReplace()
            assertEquals(2, presenter.uiState.value.rows.size)
            presenter.onPaste("0,0\n1000,2\n2000,3\n")
            presenter.onConfirmReplace()
            assertEquals(3, presenter.uiState.value.rows.size)
            assertNull(presenter.uiState.value.replaceQuestion)
            presenter.close()
        }
    }

    @Test
    fun anErrorMapOfTruePositionsChangesToCorrections() = runTest {
        withFixture { fixture ->
            fixture.adapterWith(EncoderProfile.defaultFor('x'))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onPaste("0,0\n10000,10004\n20000,20009\n")
            val state = presenter.uiState.value
            assertEquals(listOf(MapPoint(0, 0), MapPoint(10000, 4), MapPoint(20000, 9)), state.rows)
            assertEquals(listOf(UiText.of(Res.string.encoder_map_converted)), state.notices)
            presenter.onPaste(null)
            assertEquals(UiText.of(Res.string.encoder_map_clipboard_empty), presenter.uiState.value.message)
            presenter.close()
        }
    }

    @Test
    fun aMapOfTheWizardIsReadOnly() = runTest {
        withFixture { fixture ->
            fixture.adapterWith(EncoderProfile.defaultFor('x'))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onPaste("# generated-by: SuperDRO Error Map Wizard\n# passes: 2\n0,0\n1000,2\n")
            assertTrue(presenter.uiState.value.readOnly)
            assertEquals(2, presenter.uiState.value.summary.source.passes)
            presenter.onAddRow()
            assertNull(presenter.uiState.value.rowEdit, "A read-only table gets no new row.")
            presenter.onClear()
            assertFalse(presenter.uiState.value.readOnly)
            assertTrue(presenter.uiState.value.rows.isEmpty())
            presenter.close()
        }
    }

    @Test
    fun theExportWritesTheHeaderAndTheRows() = runTest {
        withFixture { fixture ->
            fixture.adapterWith(EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(10000, 4))))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onExport()
            advanceUntilIdle()
            val (name, mime, text) = fixture.files.created.single()
            assertEquals("superdro-error_map-Bridgeport_X-X-20261012_080000.csv", name)
            assertEquals("text/csv", mime)
            val lines = text.lines()
            assertEquals("# SuperDRO error correction map", lines[0])
            assertEquals("# adapter: Bridgeport X  |  input: X  |  exported: 2026-10-12 08:00:00", lines[1])
            assertTrue("position,correction" in lines)
            assertEquals(listOf("0,0", "10000,4", ""), lines.takeLast(3))
            assertEquals(UiText.of(Res.string.encoder_map_exported, name), presenter.uiState.value.message)
            presenter.close()
        }
    }

    @Test
    fun aBacklashMapUsesItsOwnValidatorAndHasNoWizard() = runTest {
        withFixture { fixture ->
            val encoder = EncoderProfile.defaultFor('x').copy(type = EncoderType.LeadscrewEncoder, countsPerScrewRev = 2000.0, backlashMode = BacklashMode.Map)
            val adapter = fixture.adapterWith(encoder)
            val presenter = MapEditorPresenter(EncoderSource.Offline(adapter.id), 'x', MapKind.Backlash, fixture.deps)
            advanceUntilIdle()
            assertFalse(presenter.uiState.value.wizardAvailable)
            assertFalse(presenter.onWizard())
            presenter.onPaste("0,30\n10000,2500\n")
            assertEquals(listOf(MapEditorTexts.problem(MapProblem.BacklashExceedsScrewRev(10000, 2500, 2000))), presenter.uiState.value.importProblems)
            presenter.onPaste("0,30\n10000,35\n")
            presenter.onSave()
            advanceUntilIdle()
            val stored = fixture.stored(adapter)
            assertEquals(listOf(MapPoint(0, 30), MapPoint(10000, 35)), stored.backlashMap)
            assertTrue(stored.errorMap.isEmpty())
            presenter.close()
        }
    }

    @Test
    fun aSaveOfAnEmptyTableAsksBeforeItRemovesTheMap() = runTest {
        withFixture { fixture ->
            val adapter = fixture.adapterWith(EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(10000, 4))))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            presenter.onClear()
            presenter.onSave()
            assertTrue(presenter.uiState.value.confirmClear)
            presenter.onCancelClear()
            advanceUntilIdle()
            assertEquals(2, fixture.stored(adapter).errorMap.size)
            presenter.onSave()
            presenter.onConfirmClear()
            advanceUntilIdle()
            assertTrue(fixture.stored(adapter).errorMap.isEmpty())
            presenter.close()
        }
    }

    @Test
    fun theWizardAsksFirstWhenTheTableHasUnsavedChanges() = runTest {
        withFixture { fixture ->
            fixture.adapterWith(EncoderProfile.defaultFor('x').copy(errorMap = listOf(MapPoint(0, 0), MapPoint(10000, 4))))
            val presenter = MapEditorPresenter(EncoderSource.Online(device), 'x', MapKind.Error, fixture.deps)
            advanceUntilIdle()
            assertTrue(presenter.onWizard())
            presenter.onClear()
            assertFalse(presenter.onWizard())
            assertTrue(presenter.uiState.value.confirmWizard)
            assertTrue(presenter.onConfirmWizard())
            assertFalse(presenter.uiState.value.dirty)
            assertEquals(2, presenter.uiState.value.rows.size, "The unsaved changes go away.")
            presenter.close()
        }
    }
}
