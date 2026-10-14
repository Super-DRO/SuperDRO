package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.PairedListState
import org.superdro.core.model.AdapterAddress

/** The paired Bluetooth devices of the Link screen. */
class PairedDevicesPresenterTest {

    private val adapter = DiscoveredAdapter(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05", "24:6F:28:A1:B2:C3")
    private val capability = MutableStateFlow<LinkCapability>(LinkCapability.Available)
    private val paired = MutableStateFlow(listOf(adapter))

    @Test
    fun theStateIsUnknownBeforeTheFirstRead() = runTest {
        val adapters = MutableSharedFlow<List<DiscoveredAdapter>>()
        val presenter = PairedDevicesPresenter(capability, adapters, backgroundScope)
        runCurrent()
        assertNull(presenter.state.value.state)
        adapters.emit(emptyList())
        runCurrent()
        assertEquals(PairedListState.NoPaired, presenter.state.value.state)
    }

    @Test
    fun aMissingPermissionShowsThePermissionRequestAndNotNoPairedDevice() = runTest {
        val presenter = PairedDevicesPresenter(capability, paired, backgroundScope)
        // Without BLUETOOTH_CONNECT, Android gives an empty list.
        capability.value = LinkCapability.Unavailable(LinkFailure.PermissionMissing)
        paired.value = emptyList()
        runCurrent()
        assertEquals(PairedDevices(PairedListState.PermissionMissing), presenter.state.value)
    }

    @Test
    fun aRadioThatIsOffShowsBluetoothIsOffAndNotNoPairedDevice() = runTest {
        val presenter = PairedDevicesPresenter(capability, paired, backgroundScope)
        capability.value = LinkCapability.Unavailable(LinkFailure.RadioOff)
        runCurrent()
        assertEquals(PairedDevices(PairedListState.RadioOff), presenter.state.value)
        paired.value = emptyList()
        runCurrent()
        assertEquals(PairedDevices(PairedListState.RadioOff), presenter.state.value)
    }

    @Test
    fun anEmptyListWithTheRadioOnAndThePermissionHasNoPairedDevice() = runTest {
        paired.value = emptyList()
        val presenter = PairedDevicesPresenter(capability, paired, backgroundScope)
        runCurrent()
        assertEquals(PairedDevices(PairedListState.NoPaired), presenter.state.value)
    }

    @Test
    fun thePairedDevicesShowWithTheRadioOnAndThePermission() = runTest {
        val presenter = PairedDevicesPresenter(capability, paired, backgroundScope)
        runCurrent()
        assertEquals(PairedDevices(PairedListState.Paired, listOf(adapter)), presenter.state.value)
    }

    @Test
    fun theStateFollowsAGrantAndARadioChange() = runTest {
        capability.value = LinkCapability.Unavailable(LinkFailure.PermissionMissing)
        paired.value = emptyList()
        val presenter = PairedDevicesPresenter(capability, paired, backgroundScope)
        runCurrent()
        assertEquals(PairedListState.PermissionMissing, presenter.state.value.state)
        // The user grants the permission while the radio is off.
        capability.value = LinkCapability.Unavailable(LinkFailure.RadioOff)
        runCurrent()
        assertEquals(PairedListState.RadioOff, presenter.state.value.state)
        // The user turns on the radio. The discovery reads the list again.
        capability.value = LinkCapability.Available
        paired.value = listOf(adapter)
        runCurrent()
        assertEquals(PairedListState.Paired, presenter.state.value.state)
        assertTrue(presenter.state.value.devices.isNotEmpty())
    }
}
