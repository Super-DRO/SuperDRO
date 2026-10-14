package org.superdro.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.AdapterAddress

/** The state of the paired Bluetooth list. */
class PairedListStateTest {

    private val adapter = DiscoveredAdapter(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), "HC-05")

    @Test
    fun aMissingPermissionIsNotAnEmptyList() {
        val capability = LinkCapability.Unavailable(LinkFailure.PermissionMissing)
        assertEquals(PairedListState.PermissionMissing, PairedListState.of(capability, emptyList()))
        assertEquals(PairedListState.PermissionMissing, PairedListState.of(capability, listOf(adapter)))
    }

    @Test
    fun aRadioThatIsOffIsNotAnEmptyList() {
        val capability = LinkCapability.Unavailable(LinkFailure.RadioOff)
        assertEquals(PairedListState.RadioOff, PairedListState.of(capability, emptyList()))
        assertEquals(PairedListState.RadioOff, PairedListState.of(capability, listOf(adapter)))
    }

    @Test
    fun onlyAnAvailableLinkWithAnEmptyListHasNoPairedAdapter() {
        assertEquals(PairedListState.NoPaired, PairedListState.of(LinkCapability.Available, emptyList()))
        assertEquals(PairedListState.Paired, PairedListState.of(LinkCapability.Available, listOf(adapter)))
    }

    @Test
    fun onlyTheStatesWithTheRadioOnAndThePermissionAreReadable() {
        assertTrue(PairedListState.NoPaired.readable)
        assertTrue(PairedListState.Paired.readable)
        assertFalse(PairedListState.RadioOff.readable)
        assertFalse(PairedListState.PermissionMissing.readable)
    }
}
