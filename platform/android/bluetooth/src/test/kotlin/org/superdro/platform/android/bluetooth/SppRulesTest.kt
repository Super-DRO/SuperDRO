package org.superdro.platform.android.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.connection.DiscoveredAdapter
import org.superdro.core.connection.LinkCapability
import org.superdro.core.connection.LinkFailure
import org.superdro.core.connection.PermissionKind
import org.superdro.core.model.AdapterAddress
import org.superdro.core.model.TransportKind

class SppRulesTest {

    @Test
    fun theLinkUsesTheSppUuidA1024ByteBufferAndA4000MsWatchdog() {
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", Spp.SERVICE_UUID.toString())
        assertEquals(1024, Spp.READ_BUFFER_BYTES)
        assertEquals(4000L, Spp.WRITE_WATCHDOG_MS)
        assertEquals(0x07.toByte(), Spp.BEL)
        assertEquals(TransportKind.BluetoothSpp, Spp.kind)
    }

    @Test
    fun theNameIsTheAliasOnApi30AndHigherElseTheName() {
        assertEquals("Mill DRO", SppRules.displayName(30, alias = "Mill DRO", name = "ESP32-DRO", address = "24:6F:28:A1:B2:C3"))
        assertEquals("ESP32-DRO", SppRules.displayName(29, alias = "Mill DRO", name = "ESP32-DRO", address = "24:6F:28:A1:B2:C3"))
        assertEquals("ESP32-DRO", SppRules.displayName(34, alias = " ", name = "ESP32-DRO", address = "24:6F:28:A1:B2:C3"))
        assertEquals("24:6F:28:A1:B2:C3", SppRules.displayName(34, alias = null, name = null, address = "24:6F:28:A1:B2:C3"))
        assertEquals("24:6F:28:A1:B2:C3", SppRules.displayName(24, alias = null, name = "", address = "24:6F:28:A1:B2:C3"))
    }

    @Test
    fun theDiscoveryIsCancelledOnlyWithThePermission() {
        assertTrue(SppRules.mayCancelDiscovery(31, hasScanPermission = true, hasAdminPermission = false))
        assertFalse(SppRules.mayCancelDiscovery(31, hasScanPermission = false, hasAdminPermission = true))
        assertTrue(SppRules.mayCancelDiscovery(30, hasScanPermission = false, hasAdminPermission = true))
        assertFalse(SppRules.mayCancelDiscovery(30, hasScanPermission = true, hasAdminPermission = false))
    }

    @Test
    fun theLinkProblemsHaveAFixedOrder() {
        assertEquals(LinkFailure.RadioOff, SppRules.linkProblem(hasAdapter = false, hasConnectPermission = true, radioOn = true, paired = true))
        assertEquals(LinkFailure.PermissionMissing, SppRules.linkProblem(hasAdapter = true, hasConnectPermission = false, radioOn = false, paired = false))
        assertEquals(LinkFailure.RadioOff, SppRules.linkProblem(hasAdapter = true, hasConnectPermission = true, radioOn = false, paired = false))
        assertEquals(LinkFailure.DeviceGone, SppRules.linkProblem(hasAdapter = true, hasConnectPermission = true, radioOn = true, paired = false))
        assertNull(SppRules.linkProblem(hasAdapter = true, hasConnectPermission = true, radioOn = true, paired = true))
    }

    @Test
    fun theCapabilityFollowsTheRadioAndThePermission() {
        assertEquals(LinkCapability.Available, SppRules.capability(hasAdapter = true, hasConnectPermission = true, radioOn = true))
        assertEquals(
            LinkCapability.Unavailable(LinkFailure.PermissionMissing),
            SppRules.capability(hasAdapter = true, hasConnectPermission = false, radioOn = true),
        )
        assertEquals(
            LinkCapability.Unavailable(LinkFailure.RadioOff),
            SppRules.capability(hasAdapter = true, hasConnectPermission = true, radioOn = false),
        )
        assertEquals(
            LinkCapability.Unavailable(LinkFailure.RadioOff),
            SppRules.capability(hasAdapter = false, hasConnectPermission = false, radioOn = false),
        )
    }

    @Test
    fun thePairingCheckIgnoresTheCaseOfTheAddress() {
        assertTrue(SppRules.isPaired(listOf("24:6F:28:A1:B2:C3"), "24:6f:28:a1:b2:c3"))
        assertFalse(SppRules.isPaired(listOf("24:6F:28:A1:B2:C4"), "24:6F:28:A1:B2:C3"))
        assertFalse(SppRules.isPaired(emptyList(), "24:6F:28:A1:B2:C3"))
    }

    @Test
    fun theAdapterListHasEachPairedDeviceInTheOrderOfTheName() {
        val devices = listOf(
            SppRules.PairedDevice("00:00:00:00:00:03", alias = "lathe", name = "ESP32"),
            SppRules.PairedDevice("00:00:00:00:00:01", alias = null, name = "Headset"),
            SppRules.PairedDevice("00:00:00:00:00:02", alias = "Mill", name = "ESP32"),
        )
        assertEquals(
            listOf(
                DiscoveredAdapter(AdapterAddress.BluetoothSpp("00:00:00:00:00:01"), "Headset", "00:00:00:00:00:01"),
                DiscoveredAdapter(AdapterAddress.BluetoothSpp("00:00:00:00:00:03"), "lathe", "00:00:00:00:00:03"),
                DiscoveredAdapter(AdapterAddress.BluetoothSpp("00:00:00:00:00:02"), "Mill", "00:00:00:00:00:02"),
            ),
            SppRules.pairedAdapters(31, devices),
        )
        assertEquals(listOf("ESP32", "ESP32", "Headset"), SppRules.pairedAdapters(29, devices).map { adapter -> adapter.displayName })
    }

    @Test
    fun thePairButtonOpensTheSystemBluetoothSettings() {
        assertEquals("android.settings.BLUETOOTH_SETTINGS", BluetoothIntents.BLUETOOTH_SETTINGS_ACTION)
        assertEquals("android.bluetooth.adapter.action.REQUEST_ENABLE", BluetoothIntents.REQUEST_ENABLE_ACTION)
    }

    @Test
    fun theRuntimePermissionsApplyOnApi31AndHigher() {
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), SppRules.manifestPermissions(PermissionKind.BluetoothConnect, 31))
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN"), SppRules.manifestPermissions(PermissionKind.BluetoothScan, 34))
        assertEquals(emptyList(), SppRules.manifestPermissions(PermissionKind.BluetoothConnect, 30))
        assertEquals(emptyList(), SppRules.manifestPermissions(PermissionKind.Notifications, 34))
    }

    @Test
    fun aPageTimeoutWithNoAclLinkIsNoAnswer() {
        val timeout = "read failed, socket might closed or timeout, read ret: -1"
        assertEquals(LinkFailure.NoAnswer, SppRules.connectFailure(timeout, socketErrorCode = null, aclConnected = false))
    }

    @Test
    fun theSameMessageAfterTheAdapterAnsweredIsRefused() {
        val closed = "read failed, socket might closed or timeout, read ret: -1"
        assertEquals(LinkFailure.Refused, SppRules.connectFailure(closed, socketErrorCode = null, aclConnected = true))
    }

    @Test
    fun aFailureIsRefusedWhenThePlatformCannotTellAboutTheAclLink() {
        val timeout = "read failed, socket might closed or timeout, read ret: -1"
        assertEquals(LinkFailure.Refused, SppRules.connectFailure(timeout, socketErrorCode = null, aclConnected = null))
    }

    @Test
    fun aDifferentFailureWithNoAclLinkIsRefused() {
        assertEquals(LinkFailure.Refused, SppRules.connectFailure("Connection failure, status: 1", null, aclConnected = false))
        assertEquals(LinkFailure.Refused, SppRules.connectFailure("bt socket connect failed", 18, aclConnected = false))
        assertEquals(LinkFailure.Refused, SppRules.connectFailure(null, null, aclConnected = false))
    }

    @Test
    fun theAclFailureCodeIsNoAnswer() {
        assertEquals(LinkFailure.NoAnswer, SppRules.connectFailure(null, SppRules.L2CAP_ACL_FAILURE, aclConnected = null))
        assertEquals(LinkFailure.NoAnswer, SppRules.connectFailure(null, SppRules.L2CAP_ACL_FAILURE, aclConnected = false))
    }
}
