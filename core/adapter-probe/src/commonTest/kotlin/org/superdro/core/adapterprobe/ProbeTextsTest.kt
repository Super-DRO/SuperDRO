package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.protocol.AdapterCommand
import org.superdro.core.protocol.AdapterField

/**
 * The helper checks of `adapter_probe.py --self-test` (part 2): the `#get_sig` seconds, the command
 * texts, the MAC + 2 rule, the mode decode, the raw escapes, and the unit guess.
 */
class ProbeTextsTest {

    @Test
    fun getSigSecondsCountFrom20120112() {
        assertEquals(465_307_200L, AdapterCommand.GetSig.secondsAt(1_791_676_800_000L))
        assertEquals(416_318_400L, AdapterCommand.GetSig.secondsAt(1_742_688_000_000L))
        assertEquals("#get_sig:465307200,1;", ProbeTexts.getSigText(1_791_676_800_000L))
    }

    @Test
    fun getTextHasTheTwoQuoteBytes() {
        assertEquals("\"#get:mac;\"", ProbeTexts.getText("mac"))
        assertEquals(
            listOf(0x22, 0x23, 0x67, 0x65, 0x74, 0x3a, 0x6d, 0x61, 0x63, 0x3b, 0x22),
            AdapterCommand.GetField(AdapterField.Mac).encode().map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun macPlusTwoWithTheCarryAndOnTheLastByte() {
        assertEquals("24:6F:28:A1:B2:C5" to "24:6F:28:A1:B2:C5", ProbeTexts.macPlusTwo("246f28a1b2c3"))
        assertEquals("24:6F:28:A1:B3:01" to "24:6F:28:A1:B2:01", ProbeTexts.macPlusTwo("246f28a1b2ff"))
        assertNull(ProbeTexts.macPlusTwo("246f28a1b2"))
        assertEquals("246F28A1B2C5", ProbeTexts.normMac("24:6f:28:a1:b2:c5"))
    }

    @Test
    fun modeDecodeGivesTheSupportedModes() {
        val mode = assertNotNull(ProbeTexts.decodeMode("x000f0002"))
        assertEquals(listOf(1, 2, 4, 8), mode.supported)
        assertEquals(2, mode.mode)
        assertEquals("Quadrature A/B", mode.name)
        assertNull(ProbeTexts.decodeMode("x000F0002"), "upper case is refused")
        assertEquals("channel x: mode 2 Quadrature A/B; supported 1, 2, 4, 8", ProbeTexts.decodeInfo(1, "x000f0002"))
    }

    @Test
    fun decodeInfoOfTheFields() {
        assertEquals("MAC + 2 = 24:6F:28:A1:B2:C5 (display with no carry: 24:6F:28:A1:B2:C5)", ProbeTexts.decodeInfo(5, "246f28a1b2c3"))
        assertEquals("not valid (the protocol needs 12 hex digits)", ProbeTexts.decodeInfo(5, "246f"))
        assertEquals("UUID text", ProbeTexts.decodeInfo(2, "c0ffee00-1234-4abc-8def-0123456789ab"))
        assertEquals("decimal 1, bit 0 (Plus adapter) = 1", ProbeTexts.decodeInfo(10, "1"))
        assertEquals("hex e10 = 3600", ProbeTexts.decodeInfo(9, "e10"))
        assertEquals("starts with tda-", ProbeTexts.decodeInfo(4, "tda-fake"))
        assertEquals("2024-10-08 21:11 UTC", ProbeTexts.decodeInfo(8, "6705a000"))
        assertEquals("not valid for this field", ProbeTexts.decodeInfo(9, "xyz"))
    }

    @Test
    fun rawEscapesGiveBytes() {
        assertEquals(listOf(0x07) + "#get:mac;\\".map { it.code }, ProbeTexts.decodeEscapes("\\x07#get:mac;\\\\").map { it.toInt() and 0xFF })
        assertEquals(listOf(0x0D, 0x0A), ProbeTexts.decodeEscapes("\\r\\n").map { it.toInt() })
    }

    @Test
    fun unitGuessWithin3Percent() {
        assertEquals("ms", ProbeTexts.unitName(1001.0))
        assertEquals("s", ProbeTexts.unitName(1.0))
        assertEquals("us", ProbeTexts.unitName(1_000_000.0))
        assertEquals("not known", ProbeTexts.unitName(50.0))
    }

    @Test
    fun consoleCommandTexts() {
        assertEquals("#set_mode:x,4;", ConsoleCommands.build("set_mode x 4", 0L).text)
        assertEquals("#ref_axis:z,15;", ConsoleCommands.build("ref_axis z", 0L).text)
        assertEquals("#clear_counter:v;", ConsoleCommands.build("clear_counter v", 0L).text)
        assertEquals("#reboot:1000;", ConsoleCommands.build("reboot", 0L).text)
        assertEquals("#get_mode:u;", ConsoleCommands.build("get_mode u", 0L).text)
        assertEquals("\"#get:uptime;\"", ConsoleCommands.build("get uptime", 0L).text)
        // A soft keyboard can give a capital letter: the field name is not case-sensitive.
        assertEquals("\"#get:mac;\"", ConsoleCommands.build("get Mac", 0L).text)
        assertTrue(ConsoleCommands.build("get_sig", 1_791_676_800_000L).text.startsWith("#get_sig:"))
        assertEquals("#get_sig:465307200,1;", ConsoleCommands.build("get_sig", 1_791_676_800_000L).text)
        // The four state commands and #get_sig need the typed yes. The queries need none.
        assertNotNull(ConsoleCommands.build("raw #reboot:1000;", 0L).caution)
        assertNotNull(ConsoleCommands.build("set_mode x 4", 0L).caution)
        assertNotNull(ConsoleCommands.build("clear_counter x", 0L).caution)
        assertNotNull(ConsoleCommands.build("ref_axis x", 0L).caution)
        assertNotNull(ConsoleCommands.build("reboot", 0L).caution)
        assertNotNull(ConsoleCommands.build("get_sig", 0L).caution)
        assertNull(ConsoleCommands.build("get mac", 0L).caution)
        assertNull(ConsoleCommands.build("raw #get:mac;", 0L).caution)
        assertEquals("\\x07", (ConsoleCommands.build("raw \\x07", 0L) as ConsoleCommand.Raw).let { ProbeTexts.printable(it.bytes) })
    }

    @Test
    fun consoleCommandUsageTexts() {
        assertEquals(
            "usage: get <field>  (fields: mac uuid features uptime model name version build date)",
            assertFailsWith<IllegalArgumentException> { ConsoleCommands.build("get foo", 0L) }.message,
        )
        assertEquals("ref_axis accepts only x y z w (the protocol defines only these)", assertFailsWith<IllegalArgumentException> { ConsoleCommands.build("ref_axis u", 0L) }.message)
        assertEquals("usage: get_mode <axis>  (axis: x y z w u v)", assertFailsWith<IllegalArgumentException> { ConsoleCommands.build("get_mode q", 0L) }.message)
        assertEquals("unknown command 'foo'. Type help.", assertFailsWith<IllegalArgumentException> { ConsoleCommands.build("foo", 0L) }.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { ConsoleCommands.build("set_mode x 3", 0L) }.message!!.startsWith("usage: set_mode"))
    }

    @Test
    fun testIdsAgreeWithTheTool() {
        assertEquals((1..16).map { "T" + it.toString().padStart(2, '0') }, ProbeTestId.entries.filterNot { it.onTablet }.map { it.name })
        assertEquals(listOf("TT1", "TT2"), ProbeTestId.entries.filter { it.onTablet }.map { it.name })
        assertEquals("T03-idle", ProbeTestId.T03.tag)
        assertEquals(ProbeTestId.T03, ProbeTestId.find("t3"))
        assertEquals(ProbeTestId.T14, ProbeTestId.find("second-link"))
        assertEquals(ProbeTestId.T10, ProbeTestId.find("T10"))
        assertNull(ProbeTestId.find("T99"))
    }
}
