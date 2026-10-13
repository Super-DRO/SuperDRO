package org.superdro.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The code tables, and the channel and mode tables. */
class CodesAndChannelsTest {

    @Test
    fun theStatusTextsOfTheTable() {
        val expected = mapOf(
            0 to "OK", 1 to "Failed", 2 to "Invalid state", 3 to "Invalid argument", 4 to "Peripheral not found",
            5 to "Not supported", 20 to "Initialization failed", 21 to "System timeout", 22 to "User timeout",
            23 to "Peripheral busy", 24 to "Peripheral overflow", 30 to "Data checksum failed",
            31 to "Invalid data length", 32 to "Busy", 33 to "Bad data", 160 to "Initialized", 161 to "Finished",
            162 to "Ready", 163 to "Received", 165 to "Write OK", 166 to "Read OK", 167 to "Not changed", 168 to "Done",
            169 to "Cancelled", 170 to "NOOP", 200 to "X Axis Cleared", 201 to "Y Axis Cleared", 202 to "Z Axis Cleared",
            203 to "W Axis Cleared", 204 to "U Axis Cleared", 205 to "V Axis Cleared", 255 to "Rebooting", -1 to "None",
        )
        expected.forEach { (code, text) -> assertEquals(text, StatusCode.text(code), "Code $code") }
        assertEquals("Status code 164", StatusCode.text(164))
        assertEquals("Status code 256", StatusCode.text(256))
        assertEquals("Status code 65535", StatusCode.text(0xFFFF))
    }

    @Test
    fun theErrorPathIsTheCodes1To159() {
        assertFalse(StatusCode.isErrorPath(0))
        assertTrue(StatusCode.isErrorPath(1))
        assertTrue(StatusCode.isErrorPath(159))
        assertFalse(StatusCode.isErrorPath(160))
        assertFalse(StatusCode.isErrorPath(0xFFFF))
    }

    @Test
    fun theSubsystemNamesOfTheTable() {
        val expected = mapOf(
            -1 to "None", 1 to "ESP32", 2 to "Core", 3 to "Brownout Detection", 4 to "Firmware Update", 6 to "Inputs",
            7 to "Touch Probe", 8 to "Tachometer", 9 to "Home Switch", 10 to "RX", 50 to "Dirty Axes",
            119 to "W Axis Reference", 120 to "X Axis Reference", 121 to "Y Axis Reference", 122 to "Z Axis Reference",
        )
        expected.forEach { (id, name) -> assertEquals(name, Subsystem.name(id), "Subsystem $id") }
        assertEquals("Unknown", Subsystem.name(5))
        assertEquals("Unknown", Subsystem.name(0x33))
    }

    @Test
    fun theFailureTexts() {
        assertEquals("Command failed: Invalid argument (6:3)", CommandFailure.of(6, 3).message)
        assertEquals("Command failed: Ready (120:162)", CommandFailure.of(0x78, 0xA2).message)
        assertEquals("Command failed: Status code 164 (10:164)", CommandFailure.of(10, 164).message)
        assertEquals("Command failed: Timeout (-1:256)", CommandFailure.TIMEOUT.message)
        assertTrue(CommandFailure.TIMEOUT.isTimeout)
        assertFalse(CommandFailure.of(10, 256).isTimeout)
        assertEquals("Status code 256", CommandFailure.of(10, 256).text)
    }

    @Test
    fun theChannelTable() {
        assertEquals(listOf('x', 'y', 'z', 'w', 'u', 'v'), InputChannel.entries.map { it.letter })
        assertEquals(listOf("Input X", "Input Y", "Input Z", "Input W", "Aux 1", "Aux 2"), InputChannel.entries.map { it.uiName })
        assertEquals(listOf(true, true, true, true, false, false), InputChannel.entries.map { it.referenceAllowed })
        assertEquals(listOf(0x78, 0x79, 0x7A, 0x77, null, null), InputChannel.entries.map { it.referenceSubsystem })
        assertEquals(listOf(200, 201, 202, 203, 204, 205), InputChannel.entries.map { it.clearedCode })
        assertEquals(InputChannel.W, InputChannel.forReferenceSubsystem(0x77))
        assertNull(InputChannel.forReferenceSubsystem(0x0A))
        assertNull(InputChannel.forLetter('X'))
    }

    @Test
    fun theDirtyMaskUsesBitNForTheChannelWithIndexN() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), InputChannel.entries.map { it.index })
        assertEquals(setOf(InputChannel.X, InputChannel.Z), InputChannel.dirtyChannels(0x05))
        assertEquals(setOf(InputChannel.U), InputChannel.dirtyChannels(0x10))
        assertEquals(setOf(InputChannel.V), InputChannel.dirtyChannels(0x20))
        assertEquals(InputChannel.entries.toSet(), InputChannel.dirtyChannels(0x3F))
        assertEquals(emptySet(), InputChannel.dirtyChannels(0x40))
        assertEquals(emptySet(), InputChannel.dirtyChannels(0))
    }

    @Test
    fun theModeTable() {
        val expected = listOf(
            Triple(1, "0001", "Disabled"), Triple(2, "0002", "Quadrature A/B"), Triple(4, "0004", "Tachometer"),
            Triple(8, "0008", "Digital Probe"), Triple(16, "0010", "Limit (X,Y)"), Triple(32, "0020", "Limit (Z,W)"),
            Triple(64, "0040", "Binary Switch"), Triple(128, "0080", "BCD-7"), Triple(256, "0100", "BIN6"),
            Triple(512, "0200", "iGaging 21 Bit"), Triple(1024, "0400", "iGaging SPC"), Triple(2048, "0800", "Mitutoyo SPC"),
        )
        assertEquals(expected, InputMode.wireModes.map { Triple(it.id, it.hexId, it.text) })
        val countModes = InputMode.wireModes.filter { it.rawView == RawView.ChannelCount }
        assertEquals(listOf(InputMode.QuadratureAB, InputMode.Bin6, InputMode.MitutoyoSpc), countModes)
        assertEquals(RawView.TachometerValue, InputMode.Tachometer.rawView)
        assertEquals(RawView.ProbeForm, InputMode.Disabled.rawView)
        assertEquals(InputMode.Unknown, InputMode.forId(3))
        assertEquals(InputMode.Unknown, InputMode.forId(0))
        assertEquals(InputMode.Unknown, InputMode.forId(4096))
    }

    @Test
    fun theModeAnswerExamples() {
        val x = ChannelModes.parse("x000f0002")!!
        assertEquals(InputChannel.X, x.channel)
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB, InputMode.Tachometer, InputMode.DigitalProbe), x.supported)
        assertEquals(InputMode.QuadratureAB, x.current)
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB), ChannelModes.parse("x00030002")!!.supported)
        assertEquals(InputMode.Tachometer, ChannelModes.parse("x000f0004")!!.current)
        assertEquals(InputMode.wireModes, ChannelModes.parse("v0fff0800")!!.supported)
        // The current mode must be exactly one mode ID.
        assertEquals(InputMode.Unknown, ChannelModes.parse("y000f0003")!!.current)
        assertEquals(InputMode.Unknown, ChannelModes.parse("y000f0000")!!.current)
        // The value must match [xyzwuv][0-9a-f]{8}: lower case only.
        assertNull(ChannelModes.parse("X000f0002"))
        assertNull(ChannelModes.parse("x000F0002"))
        assertNull(ChannelModes.parse("q000f0002"))
        assertNull(ChannelModes.parse("x000f002"))
        assertNull(ChannelModes.parse("x000f00020"))
        assertNull(ChannelModes.parse(""))
    }

    @Test
    fun theReferenceAndClearRulesOfTheChannelRows() {
        val quadrature = InputMode.QuadratureAB
        assertTrue(ChannelRules.showReference("tda-example", InputChannel.X, quadrature, modeUnchanged = true))
        assertTrue(ChannelRules.showReference("TDA-Mill", InputChannel.W, quadrature, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("other", InputChannel.X, quadrature, modeUnchanged = true))
        assertFalse(ChannelRules.showReference(null, InputChannel.X, quadrature, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.U, quadrature, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.X, InputMode.Tachometer, modeUnchanged = true))
        assertFalse(ChannelRules.showReference("tda-example", InputChannel.X, quadrature, modeUnchanged = false))
        assertTrue(ChannelRules.showClear(quadrature, modeUnchanged = true))
        assertFalse(ChannelRules.showClear(InputMode.Bin6, modeUnchanged = true))
        assertFalse(ChannelRules.showClear(quadrature, modeUnchanged = false))
        assertEquals(listOf(InputMode.Disabled, InputMode.QuadratureAB), ChannelRules.modeList(ChannelModes.parse("z00030002")!!))
        assertTrue(ChannelRules.showSave(InputMode.Tachometer, InputMode.QuadratureAB))
        assertFalse(ChannelRules.showSave(InputMode.QuadratureAB, InputMode.QuadratureAB))
        assertTrue(AdapterDialect.isGenuineModel("tda-example"))
    }
}
