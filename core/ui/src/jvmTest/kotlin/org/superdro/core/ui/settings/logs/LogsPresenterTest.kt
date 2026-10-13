package org.superdro.core.ui.settings.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.RecordedChunk
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.FakeDeviceSession
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.util.FilePicker
import org.superdro.core.util.PickedFile

/** A [FilePicker] that keeps each shared file. [result] is the answer of [share]. */
class RecordingFilePicker(var result: Boolean = true) : FilePicker {
    val shared = mutableListOf<Triple<String, String, String>>()
    override suspend fun openDocument(mimeTypes: List<String>): PickedFile? = null
    override suspend fun createDocument(suggestedName: String, mimeType: String, content: ByteArray): Boolean = false
    override suspend fun capturePhoto(): PickedFile? = null
    override suspend fun share(name: String, mimeType: String, content: ByteArray): Boolean {
        shared += Triple(name, mimeType, content.decodeToString())
        return result
    }
}

/** The presenter of the log screen. */
class LogsPresenterTest {

    private class Harness(scope: TestScope) {
        val store = UiStoreFixture(scope)
        val sink = SessionLogSink(store.wallClock)
        val streams = RecordedStreams(clock = store.wallClock)
        val connections = FakeConnections(connectState = SessionState.Connecting(1))
        val files = RecordingFilePicker()
        val presenter = LogsPresenter(sink, streams, connections, store.machines, files, scope.testPresenterContext(), store.wallClock, TimeZone.UTC)

        suspend fun machine(name: String): DeviceId =
            DeviceId(store.machines.createProfile(MachineProfile.create(MachineType.VerticalMill, name)).valueOrNull()!!.id)

        fun close() {
            presenter.close()
            store.close()
        }
    }

    @Test
    fun theFilterShowsAllLinesTheAppLinesOrTheLinesOfOneMachine() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val lathe = harness.machine("Lathe")
        harness.sink.add(LogLevel.Info, SessionLogSink.SESSION_TAG, "${mill.value}: link 1 open")
        harness.sink.add(LogLevel.Warning, SessionLogSink.SESSION_TAG, "${lathe.value}: link 1 lost: Lost")
        harness.sink.add(LogLevel.Error, "App", "An app coroutine failed")
        advanceUntilIdle()
        val state = harness.presenter.uiState.value
        assertEquals(listOf("All", "App", "Lathe", "Mill"), state.filters.map { it.label.english() })
        // Newest first.
        assertEquals(listOf("An app coroutine failed", "link 1 lost: Lost", "link 1 open"), state.lines.map { it.text })
        assertEquals(listOf(null, "Lathe", "Mill"), state.lines.map { it.machine })
        assertEquals("08:00:00.000", state.lines.first().time)

        harness.presenter.onSelectFilter(LogFilter.Machine(mill))
        advanceUntilIdle()
        assertEquals(listOf("link 1 open"), harness.presenter.uiState.value.lines.map { it.text })
        harness.presenter.onSelectFilter(LogFilter.App)
        advanceUntilIdle()
        assertEquals(listOf(LogLevel.Error), harness.presenter.uiState.value.lines.map { it.level })
        harness.close()
    }

    @Test
    fun theScreenReadsNewLinesAtMostEachRefreshTime() = runTest {
        val harness = Harness(this)
        advanceUntilIdle()
        harness.sink.add(LogLevel.Info, "App", "first")
        runCurrent()
        assertEquals(listOf("first"), harness.presenter.uiState.value.lines.map { it.text })
        harness.sink.add(LogLevel.Info, "App", "second")
        harness.sink.add(LogLevel.Info, "App", "third")
        runCurrent()
        assertEquals(1, harness.presenter.uiState.value.lines.size)
        advanceTimeBy(LogsPresenter.REFRESH_MS + 1)
        runCurrent()
        assertEquals(listOf("third", "second", "first"), harness.presenter.uiState.value.lines.map { it.text })
        harness.presenter.onClear()
        advanceUntilIdle()
        assertTrue(harness.presenter.uiState.value.lines.isEmpty())
        harness.close()
    }

    @Test
    fun exportSharesTheShownLinesAsPlainText() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Bridgeport mill")
        harness.sink.add(LogLevel.Info, SessionLogSink.SESSION_TAG, "${mill.value}: link 1 open")
        harness.sink.add(LogLevel.Info, "App", "app line")
        advanceUntilIdle()
        harness.presenter.onSelectFilter(LogFilter.Machine(mill))
        harness.presenter.onExport()
        advanceUntilIdle()
        val (name, mime, text) = harness.files.shared.single()
        assertEquals("superdro-log-Bridgeport_mill-20261012-080000.txt", name)
        assertEquals("text/plain", mime)
        assertEquals(
            "# SuperDRO log\n# Machine: Bridgeport mill\n# Exported: 2026-10-12 08:00:00\n" +
                "08:00:00.000 I DeviceSession [Bridgeport mill]: link 1 open\n",
            text,
        )
        assertEquals("The file is ready to share.", harness.presenter.uiState.value.message!!.text.english())
        harness.presenter.onMessageShown(harness.presenter.uiState.value.message!!.id)

        harness.files.result = false
        harness.presenter.onSelectFilter(LogFilter.All)
        harness.presenter.onExport()
        advanceUntilIdle()
        assertEquals("superdro-log-all-20261012-080000.txt", harness.files.shared.last().first)
        assertEquals("No app can receive the file.", harness.presenter.uiState.value.message!!.text.english())
        harness.close()
    }

    @Test
    fun theRecordSwitchStartsANewLinkWithTheRecordAndTheTabStays() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        val session = FakeDeviceSession(mill, "Mill", AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), SessionState.Connected(0L))
        harness.connections.add(session)
        advanceUntilIdle()
        assertEquals(listOf(RecordSessionUiState(mill, "Mill", recording = false, available = true)), harness.presenter.uiState.value.sessions)

        harness.presenter.onRecordChange(mill, true)
        advanceUntilIdle()
        // One call, no disconnect: the session stays online, so its tab does not hide.
        assertEquals(listOf("record ${mill.value} true"), harness.connections.calls)
        assertTrue(session.device.value.recordStream)
        assertEquals(SessionState.Connected(0L), session.state.value)
        assertTrue(harness.presenter.uiState.value.sessions.single().recording)

        // The same value does nothing.
        harness.presenter.onRecordChange(mill, true)
        assertEquals(1, harness.connections.calls.size)

        harness.presenter.onRecordChange(mill, false)
        advanceUntilIdle()
        assertFalse(session.device.value.recordStream)
        assertEquals(listOf("record ${mill.value} true", "record ${mill.value} false"), harness.connections.calls)
        harness.close()
    }

    @Test
    fun aPausedMachineHasNoRecordSwitch() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Mill")
        harness.connections.add(FakeDeviceSession(mill, "Mill", initialState = SessionState.Disconnected))
        advanceUntilIdle()
        assertFalse(harness.presenter.uiState.value.sessions.single().available)
        harness.presenter.onRecordChange(mill, true)
        assertTrue(harness.connections.calls.isEmpty())
        harness.close()
    }

    @Test
    fun theExportOfARecordedLinkSharesTheTextOfTheFile() = runTest {
        val harness = Harness(this)
        val mill = harness.machine("Demo mill")
        val sink = harness.streams.open(mill, AdapterAddress.Fake("mill"), link = 2)
        sink.write(RecordedChunk(0L, "X1200;".encodeToByteArray()))
        advanceUntilIdle()
        val record = harness.presenter.uiState.value.records.single()
        assertEquals(RecordedLinkUiState(record.id, "Demo mill", 2, "08:00:00.000", 1, 6L, open = true, truncated = false), record)
        harness.presenter.onExportRecord(record.id)
        advanceUntilIdle()
        assertEquals(Triple("Demo_mill-2.txt", "text/plain", "# SuperDRO byte stream 1\n0 58313230303b\n"), harness.files.shared.single())
        harness.close()
    }
}
