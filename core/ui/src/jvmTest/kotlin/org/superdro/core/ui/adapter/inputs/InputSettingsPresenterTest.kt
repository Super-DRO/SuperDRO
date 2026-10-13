package org.superdro.core.ui.adapter.inputs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DeviceId
import org.superdro.core.connection.SessionState
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AxisId
import org.superdro.core.model.AxisState
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderType
import org.superdro.core.model.ProbeContactType
import org.superdro.core.model.Workspace
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.OperationResult
import org.superdro.core.ui.adapter.AdapterFixture
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.text.UiText

/** The tach pulses sheet and the probe input sheet. */
class InputSettingsPresenterTest {

    private val device = DeviceId("lathe")

    /** A [TabReadout] that applies each workspace change to [workspace]. */
    private class RecordingReadout : TabReadout {
        var workspace = Workspace(axisStates = AxisId.entries.associateWith { AxisState() } + (AxisId.Tachometer to AxisState(absoluteOffset = 120.0, incrementalOffset = 30.0)))
        val labels = mutableListOf<UiText?>()
        override val deviceId: DeviceId = DeviceId("lathe")
        override val frame: StateFlow<ReadoutFrame?> = MutableStateFlow(null)
        override val sessionState: StateFlow<SessionState?> = MutableStateFlow(null)
        override fun positionUnavailable(): UiText? = null
        override fun apply(result: OperationResult, label: UiText?) = Unit
        override fun applyWorkspace(label: UiText?, transform: (Workspace) -> Workspace) {
            labels += label
            workspace = transform(workspace)
        }
        override fun showMessage(text: UiText) = Unit
    }

    @Test
    fun thePulsesAreTheResolutionOfInputTAndAChangeResetsTheTachometerOffsets() = runTest {
        val fixture = AdapterFixture(this)
        val adapter = fixture.machineWithAdapter(device)
        val readout = RecordingReadout()
        val presenter = TachPulsesPresenter(AdapterSource.Tab(device), fixture.deps, readout)
        try {
            runCurrent()
            assertEquals("1", presenter.uiState.value.text, "A new input t has 1 count for each revolution.")
            assertTrue(presenter.uiState.value.hasProfile)
            presenter.onText("4")
            presenter.onSet()
            runCurrent()
            assertTrue(presenter.uiState.value.done)
            val t = fixture.store.adapters.loadAdapterProfile(adapter.id)!!.encoders.getValue('t')
            assertEquals(4.0, t.resolution)
            assertEquals(EncoderType.RotaryEncoder, t.type)
            assertEquals(EncoderKind.Angular, t.kind)
            assertEquals(CalibrationStatus.UserSet, t.calibration.status)
            val tach = readout.workspace.axisStates.getValue(AxisId.Tachometer)
            assertEquals(0.0, tach.absoluteOffset)
            assertEquals(0.0, tach.incrementalOffset)
            assertEquals("The tachometer offsets are 0 now.", readout.labels.single()?.english())
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun thePulsesMustBeMoreThanZero() = runTest {
        val fixture = AdapterFixture(this)
        val adapter = fixture.machineWithAdapter(device)
        val readout = RecordingReadout()
        val presenter = TachPulsesPresenter(AdapterSource.Tab(device), fixture.deps, readout)
        try {
            runCurrent()
            listOf("0", "-2", "", "abc").forEach { text ->
                presenter.onText(text)
                presenter.onSet()
                runCurrent()
                assertEquals("Enter a number more than 0.", presenter.uiState.value.error?.english(), text)
                assertFalse(presenter.uiState.value.done)
            }
            presenter.onText("2,5")
            assertNull(presenter.uiState.value.error, "A new text clears the error.")
            presenter.onSet()
            runCurrent()
            assertEquals(2.5, fixture.store.adapters.loadAdapterProfile(adapter.id)!!.encoders.getValue('t').resolution)
            // The same value again: no offset reset.
            readout.labels.clear()
            val again = TachPulsesPresenter(AdapterSource.Tab(device), fixture.deps, readout)
            runCurrent()
            assertEquals("2.5", again.uiState.value.text)
            again.onSet()
            runCurrent()
            assertTrue(readout.labels.isEmpty())
            again.close()
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun withNoAdapterProfileTheSheetShowsOneAndCannotSave() = runTest {
        val fixture = AdapterFixture(this)
        fixture.machineWithoutAdapter(device)
        val presenter = TachPulsesPresenter(AdapterSource.Tab(device), fixture.deps)
        try {
            runCurrent()
            assertEquals("1", presenter.uiState.value.text)
            assertFalse(presenter.uiState.value.hasProfile)
            presenter.onSet()
            runCurrent()
            assertFalse(presenter.uiState.value.done)
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theOfflineSheetWritesTheStoredProfile() = runTest {
        val fixture = AdapterFixture(this)
        val stored = fixture.store.adapters.saveAdapterProfile(AdapterProfile(id = "spare", alias = "Spare"))
        val presenter = TachPulsesPresenter(AdapterSource.Stored("spare"), fixture.deps)
        try {
            runCurrent()
            presenter.onText("60")
            presenter.onSet()
            runCurrent()
            assertEquals(60.0, fixture.store.adapters.loadAdapterProfile("spare")!!.encoders.getValue('t').resolution)
            assertTrue(stored.toString().isNotEmpty())
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theProbeInputWritesTheContactTypeAndTheDebounce() = runTest {
        val fixture = AdapterFixture(this)
        val adapter = fixture.machineWithAdapter(device)
        val presenter = ProbeInputPresenter(AdapterSource.Tab(device), fixture.deps)
        try {
            runCurrent()
            assertEquals(ProbeContactType.Auto, presenter.uiState.value.contactType)
            assertEquals("200", presenter.uiState.value.debounceText, "The default debounce is 0.2 s.")
            presenter.onContactType(ProbeContactType.NormallyClosed)
            presenter.onDebounce("1.5")
            presenter.onSave()
            runCurrent()
            assertEquals("Enter a whole number of 0 or more.", presenter.uiState.value.error?.english())
            presenter.onDebounce("-1")
            presenter.onSave()
            runCurrent()
            assertEquals("Enter a whole number of 0 or more.", presenter.uiState.value.error?.english())
            presenter.onDebounce("0")
            presenter.onSave()
            runCurrent()
            assertTrue(presenter.uiState.value.done)
            val stored = fixture.store.adapters.loadAdapterProfile(adapter.id)!!
            assertEquals(ProbeContactType.NormallyClosed, stored.probeContactType)
            assertEquals(0L, stored.probeDebounceMs)
        } finally {
            presenter.close()
            fixture.close()
        }
    }

    @Test
    fun theNumberTextOfAPulseValue() {
        assertEquals("4", numberText(4.0))
        assertEquals("2.5", numberText(2.5))
        assertEquals(2.5, parseNumber(" 2,5 "))
        assertNull(parseNumber(""))
        assertNull(parseNumber("NaN"))
    }
}
