package org.superdro.core.ui.settings.machine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AxisId
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.store.valueOrNull
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.FakeConnections
import org.superdro.core.ui.testing.UiStoreFixture
import org.superdro.core.ui.text.UiText

/**
 * The machine settings of a stored profile with no tab (GitHub issue #6): the
 * machine profiles screen opens them for each profile. Each change goes into the store, a later
 * tab of the profile reads it, and the rows that need the tab or an adapter profile are not
 * available.
 */
class OfflineMachineSettingsPresenterTest {

    private class Harness(private val scope: TestScope) {
        val store = UiStoreFixture(scope)
        val connections = FakeConnections()
        val messages = mutableListOf<UiText>()
        lateinit var profile: MachineProfile

        suspend fun store(machine: MachineProfile = MachineProfile.create(MachineType.VerticalMill, "Imported")): MachineProfile {
            profile = store.machines.createProfile(machine).valueOrNull() ?: error("The profile was not stored.")
            return profile
        }

        fun presenter(tab: Boolean): MachineSettingsPresenter {
            val presenter = MachineSettingsPresenter(
                profileId = profile.id,
                machines = store.machines,
                adapters = store.adapters,
                connections = connections,
                appSettings = MutableStateFlow(AppSettings()),
                context = scope.testPresenterContext(),
                showMessage = { text -> messages += text },
                tab = tab,
            )
            scope.testScheduler.advanceUntilIdle()
            return presenter
        }

        suspend fun stored(): MachineProfile = store.machines.loadProfile(profile.id)!!
    }

    @Test
    fun withNoTabThePresenterShowsTheStoredProfile() = runTest {
        val harness = Harness(this)
        harness.store()
        val presenter = harness.presenter(tab = false)
        val state = presenter.uiState.value
        assertTrue(state.loaded)
        assertFalse(state.tab)
        assertEquals("Imported", assertNotNull(state.profile).name)
        assertEquals("Imported", state.textOf(SettingsTextField.Name))
        presenter.close()
        harness.store.close()
    }

    @Test
    fun withNoTabTheStateBeforeTheLoadAlsoHasNoTab() = runTest {
        val harness = Harness(this)
        harness.store()
        val presenter = MachineSettingsPresenter(
            harness.profile.id, harness.store.machines, harness.store.adapters, harness.connections, MutableStateFlow(AppSettings()),
            testPresenterContext(), tab = false,
        )
        assertFalse(presenter.uiState.value.tab)
        assertFalse(presenter.uiState.value.profileFileAvailable)
        presenter.close()
        harness.store.close()
    }

    @Test
    fun aChangeWithNoTabGoesIntoTheStoreAndALaterTabUsesIt() = runTest {
        val harness = Harness(this)
        harness.store()
        val offline = harness.presenter(tab = false)
        offline.setName("Bridgeport")
        offline.setMetricDefault(true)
        offline.setReadoutFormat(ReadoutFormat.Format5)
        offline.setLabel(AxisId.X, "Q")
        offline.setPrimaryChannel(AxisId.Z, 'v')
        offline.setInvert(AxisId.Y, true)
        offline.setUsbBaudRate(9600)
        advanceUntilIdle()
        offline.close()

        val stored = harness.stored()
        assertEquals("Bridgeport", stored.name)
        assertTrue(stored.metricDefault)
        assertEquals(ReadoutFormat.Format5, stored.display.readoutFormat)
        assertEquals("Q", stored.x.label)
        assertEquals('v', stored.z.primary.channel)
        assertEquals(-1, stored.y.primary.sign)
        assertEquals(9600, stored.usbBaudRate)

        // The machine settings of a later tab of the profile show the changed values.
        val online = harness.presenter(tab = true)
        val state = online.uiState.value
        assertTrue(state.tab)
        val profile = assertNotNull(state.profile)
        assertEquals("Bridgeport", profile.name)
        assertEquals("Q", profile.x.label)
        assertEquals('v', profile.z.primary.channel)
        assertEquals(9600, profile.usbBaudRate)
        online.close()
        harness.store.close()
    }

    @Test
    fun withNoTabTheValidationRulesStillRefuseAChange() = runTest {
        val harness = Harness(this)
        harness.store()
        val presenter = harness.presenter(tab = false)
        presenter.setSecondaryChannel(AxisId.X, 'x')
        advanceUntilIdle()
        assertEquals(1, harness.messages.size)
        assertEquals(null, harness.stored().x.secondary)
        presenter.close()
        harness.store.close()
    }

    @Test
    fun withNoTabAndNoAdapterProfileTheAdapterInputsAndTheProfileFileAreNotAvailable() = runTest {
        val harness = Harness(this)
        harness.store()
        val presenter = harness.presenter(tab = false)
        val state = presenter.uiState.value
        assertFalse(state.adapterInputsAvailable)
        assertFalse(state.profileFileAvailable)
        presenter.close()
        harness.store.close()
    }

    @Test
    fun withNoTabTheAdapterInputsOfAStoredAdapterProfileAreAvailable() = runTest {
        val harness = Harness(this)
        val adapter = harness.store.adapters.saveAdapterProfile(AdapterProfile(alias = "Adapter")).valueOrNull()!!
        harness.store(MachineProfile.create(MachineType.VerticalMill, "Imported").copy(adapterProfileId = adapter.id))
        val presenter = harness.presenter(tab = false)
        val state = presenter.uiState.value
        assertTrue(state.adapterInputsAvailable)
        assertFalse(state.profileFileAvailable)
        // The pulses of the tachometer are in the stored adapter profile, so they change with no tab.
        presenter.setPulses("4")
        advanceUntilIdle()
        assertEquals(4.0, harness.store.adapters.loadAdapterProfile(adapter.id)!!.encoders[Channels.T]?.resolution)
        presenter.close()
        harness.store.close()
    }

    @Test
    fun inATabEachRowIsAvailable() = runTest {
        val harness = Harness(this)
        harness.store()
        val presenter = harness.presenter(tab = true)
        val state = presenter.uiState.value
        assertTrue(state.adapterInputsAvailable)
        assertTrue(state.profileFileAvailable)
        presenter.close()
        harness.store.close()
    }

    @Test
    fun aLinkChangeWithNoTabIsStoredAndStartsNoSession() = runTest {
        val harness = Harness(this)
        harness.store()
        val presenter = harness.presenter(tab = false)
        presenter.setBluetoothAddress("98-d3-31-f5-12-0a")
        advanceUntilIdle()
        assertEquals(AdapterAddress.BluetoothSpp("98:D3:31:F5:12:0A"), harness.stored().adapter)
        assertTrue(harness.connections.calls.isEmpty())
        assertTrue(harness.connections.sessions.value.isEmpty())
        presenter.close()
        harness.store.close()
    }
}
