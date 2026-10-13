package org.superdro.core.ui.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.Channels
import org.superdro.core.model.MachineType
import org.superdro.core.model.TransportKind
import org.superdro.core.ui.tabs.adddevice.AdapterAliases
import org.superdro.core.ui.tabs.adddevice.HostCheck
import org.superdro.core.ui.tabs.adddevice.LinkTypes
import org.superdro.core.ui.tabs.adddevice.MachineSetup
import org.superdro.core.ui.tabs.adddevice.SetupInput

/** The rules of the add-device sheet: the host check, the link types, the aliases, and the machine setup. */
class AddDeviceRulesTest {

    @Test
    fun theHostCheckAcceptsIpv4Ipv6AndHostNames() {
        listOf("192.168.4.1", "10.0.0.5", "0.0.0.0", "255.255.255.255").forEach { host -> assertTrue(HostCheck.isValid(host), host) }
        listOf("fe80::1", "::1", "::", "2001:db8::8a2e:370:7334", "1:2:3:4:5:6:7:8", "[::1]", "::ffff:192.168.1.10").forEach { host ->
            assertTrue(HostCheck.isValid(host), host)
        }
        listOf("dro.local", "touch-adapter", "esp32", "adapter.example.com.").forEach { host -> assertTrue(HostCheck.isValid(host), host) }
        listOf("192.168.1.300", "1.2.3", "1.2.3.4.5", "1::2::3", "fe80::1%eth0", "1:2:3:4:5:6:7:8:9", "-bad", "bad-", "bad_name", "a..b", "x".repeat(64))
            .forEach { host -> assertFalse(HostCheck.isValid(host), host) }
        assertEquals(HostCheck.Result.Blank, HostCheck.check("   "))
        assertEquals(HostCheck.Result.Valid, HostCheck.check(" 192.168.4.1 "))
        assertEquals(HostCheck.Result.NotValid, HostCheck.check("192.168.1.256"))
    }

    @Test
    fun thePortIsAWholeNumberFrom1To65535() {
        assertEquals(8080, HostCheck.port("8080"))
        assertEquals(1, HostCheck.port("1"))
        assertEquals(65_535, HostCheck.port("65535"))
        assertNull(HostCheck.port("0"))
        assertNull(HostCheck.port("65536"))
        assertNull(HostCheck.port("80a"))
        assertNull(HostCheck.port(""))
    }

    @Test
    fun theAppSettingsHideALinkTypeAndOneTypeStays() {
        assertEquals(listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp), LinkTypes.shown(emptySet()))
        assertEquals(listOf(TransportKind.UsbSerial, TransportKind.Tcp), LinkTypes.shown(setOf(TransportKind.BluetoothSpp)))
        assertEquals(listOf(TransportKind.Tcp), LinkTypes.shown(setOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial)))
        assertEquals(LinkTypes.SHEET_TYPES, LinkTypes.shown(setOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp)))
    }

    @Test
    fun anAdapterAliasIsUniqueAndHas40CharactersOrLess() {
        assertEquals("HC-05", AdapterAliases.unique("HC-05", emptyList()))
        assertEquals("HC-05 2", AdapterAliases.unique("HC-05", listOf("hc-05")))
        assertEquals("HC-05 3", AdapterAliases.unique("HC-05", listOf("HC-05", "HC-05 2")))
        assertEquals("Mill", AdapterAliases.unique("  ", emptyList(), fallback = "Mill"))
        val long = "A".repeat(50)
        assertEquals("A".repeat(40), AdapterAliases.unique(long, emptyList()))
        val second = AdapterAliases.unique(long, listOf("A".repeat(40)))
        assertEquals("A".repeat(38) + " 2", second)
        assertEquals(40, second.length)
    }

    @Test
    fun theMillSetupGivesTheInputsOfEachAxis() {
        val setup = MachineSetup(machineType = MachineType.HorizontalMill, metric = true, quill = SetupInput(true, Channels.W), tachometer = true)
        val profile = setup.toProfile("Bridgeport")
        assertEquals("Bridgeport", profile.name)
        assertEquals(MachineType.HorizontalMill, profile.machineType)
        assertTrue(profile.metricDefault)
        assertEquals(Channels.X, profile.x.primary.channel)
        assertEquals(Channels.Y, profile.y.primary.channel)
        assertEquals(Channels.Z, profile.z.primary.channel)
        assertEquals(Channels.W, profile.z.secondary?.channel)
        assertTrue(profile.tachometer.enabled)
        assertTrue(profile.y.enabled)
    }

    @Test
    fun theLatheSetupUsesYAsTheAuxiliaryReadout() {
        val off = MachineSetup(machineType = MachineType.Lathe).toProfile("Lathe")
        assertEquals(false, off.y.enabled)
        assertEquals(null, off.z.secondary)
        val on = MachineSetup(machineType = MachineType.Lathe, auxiliary = SetupInput(true, Channels.U), auxiliaryType = AuxiliaryType.CompoundSlide)
            .toProfile("Lathe")
        assertTrue(on.y.enabled)
        assertEquals(Channels.U, on.y.primary.channel)
        assertEquals(AuxiliaryType.CompoundSlide, on.auxiliary.type)
        assertTrue(on.workspaces.single().axisStates.getValue(org.superdro.core.model.AxisId.X).diameterMode)
    }

    @Test
    fun theSetupOffersOnlyTheLettersXyzwuv() {
        assertEquals(listOf('x', 'y', 'z', 'w', 'u', 'v'), MachineSetup.LETTERS)
    }

    @Test
    fun theSetupRefusesNoAxisAndTheSameQuillInput() {
        val none = MachineSetup(x = SetupInput(false, 'x'), y = SetupInput(false, 'y'), z = SetupInput(false, 'z'))
        assertEquals("At least one output axis must be enabled", none.refusal()?.english())
        val same = MachineSetup(quill = SetupInput(true, 'z'))
        assertEquals("Primary and secondary inputs can't use the same prefix", same.refusal()?.english())
        assertNull(MachineSetup().refusal())
    }

    @Test
    fun theConflictCheckGivesTheSuperDroTexts() {
        assertEquals(emptyList(), MachineSetup().conflicts())
        val shared = MachineSetup(y = SetupInput(true, 'x'))
        assertEquals(listOf("Input X is used by X, Y. These axes show the same value."), shared.conflicts().map { it.english() })
        val quill = MachineSetup(quill = SetupInput(true, 'x'))
        assertEquals(
            listOf(
                "Input X is used by X, Z. These axes show the same value.",
                "The quill uses input X, which X, Y, or Z also uses. The head tilt projection stays off.",
            ),
            quill.conflicts().map { it.english() },
        )
        val compound = MachineSetup(
            machineType = MachineType.Lathe,
            auxiliary = SetupInput(true, 'z'),
            auxiliaryType = AuxiliaryType.CompoundSlide,
        )
        assertEquals(listOf("Input Z is used by Y, Z. These axes show the same value."), compound.conflicts().map { it.english() })
    }
}
