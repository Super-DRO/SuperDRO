package org.superdro.core.ui.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.ConnectionRequest
import org.superdro.core.connection.PermissionKind
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.model.DataOutputSecurity

/** The status chip of the data output in the status strip. */
class DataOutputChipPresenterTest {

    private val plain = DataOutputConfig(security = DataOutputSecurity.PlainText, plainTextConfirmed = true)

    @Test
    fun theChipShowsWhenTheOutputIsOnAndATapStartsAndStopsIt() = runTest {
        val harness = DataOutputHarness(this)
        val profile = harness.profile { stored -> stored.copy(dataOutput = plain) }
        harness.session(profile)
        harness.start()
        val chip = DataOutputChipPresenter(profile.id, harness.runner, harness.store.machines, harness.requests, harness.presenterContext())
        harness.settle()
        assertFalse(chip.uiState.value.visible, "No chip while the output is off.")

        harness.store.machines.updateProfile(profile.id) { stored -> stored.copy(dataOutput = plain.copy(enabled = true)) }
        harness.settle()
        assertTrue(chip.uiState.value.visible)
        assertEquals(DataOutputStatus.Stopped, chip.uiState.value.status)

        chip.onTap()
        harness.settle()
        assertEquals(DataOutputStatus.Ready, chip.uiState.value.status)
        assertEquals(listOf<ConnectionRequest>(ConnectionRequest.RequestPermissions(setOf(PermissionKind.LocalNetwork))), harness.requests.sent)
        assertEquals(1, harness.sockets.open.size)

        chip.onTap()
        harness.settle()
        assertEquals(DataOutputStatus.Stopped, chip.uiState.value.status)
        assertTrue(harness.sockets.open.isEmpty())
        chip.close()
        harness.close()
    }

    @Test
    fun withAlwaysOnATapOpensTheDiagnosticsOfTheScreenAndStopTurnsOffTheSwitch() = runTest {
        // The dialog rule: the diagnostics show in the status section of the data
        // output screen, not in a dialog of the chip. The tap asks the entry to open that screen.
        val harness = DataOutputHarness(this)
        val profile = harness.profile { stored -> stored.copy(dataOutput = plain.copy(enabled = true, activation = DataOutputActivation.AlwaysOn)) }
        harness.session(profile)
        harness.start()
        val chip = DataOutputChipPresenter(profile.id, harness.runner, harness.store.machines, harness.requests, harness.presenterContext())
        harness.settle()
        assertTrue(chip.uiState.value.running)
        assertFalse(chip.uiState.value.openDetails)
        chip.onTap()
        harness.settle()
        assertTrue(chip.uiState.value.openDetails)
        assertTrue(chip.uiState.value.running, "The tap of Always on does not stop the output.")
        chip.detailsOpened()
        assertFalse(chip.uiState.value.openDetails)

        val screen = harness.presenter()
        screen.selectProfile(profile.id)
        harness.settle()
        screen.onShowDiagnostics()
        harness.settle()
        assertEquals(DataOutputDialog.Diagnostics, screen.uiState.value.dialog)
        val lines = assertNotNull(screen.uiState.value.diagnostics)
        assertEquals("192.168.1.20:5800", lines.listening)
        screen.onStopFromDiagnostics()
        harness.settle()
        assertNull(screen.uiState.value.diagnostics)
        assertFalse(harness.store.machines.loadProfile(profile.id)!!.dataOutput.enabled, "Always on stops with the switch.")
        assertTrue(harness.sockets.open.isEmpty())
        screen.close()
        chip.close()
        harness.close()
    }
}
