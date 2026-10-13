package org.superdro.core.protocol

import org.superdro.core.util.Bytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The command bytes and the waits. */
class AdapterCommandTest {

    private fun hex(command: AdapterCommand): String = Bytes.toHex(command.encode())

    @Test
    fun getMacIsThe11BytesOfTheExample() {
        assertContentEquals(
            byteArrayOf(0x22, 0x23, 0x67, 0x65, 0x74, 0x3A, 0x6D, 0x61, 0x63, 0x3B, 0x22),
            AdapterCommand.GetField(AdapterField.Mac).encode(),
        )
    }

    @Test
    fun eachGetFieldHasTheQuoteBytes() {
        val expected = mapOf(
            AdapterField.Mac to "\"#get:mac;\"",
            AdapterField.Uuid to "\"#get:uuid;\"",
            AdapterField.Features to "\"#get:features;\"",
            AdapterField.Uptime to "\"#get:uptime;\"",
            AdapterField.Model to "\"#get:model;\"",
            AdapterField.Name to "\"#get:name;\"",
            AdapterField.Version to "\"#get:version;\"",
            AdapterField.Build to "\"#get:build;\"",
            AdapterField.Date to "\"#get:date;\"",
        )
        expected.forEach { (field, text) ->
            val command = AdapterCommand.GetField(field)
            assertEquals(text, Bytes.asciiText(command.encode()))
            assertEquals(RequestKind.Query, command.kind)
        }
        assertFailsWith<IllegalArgumentException> { AdapterCommand.GetField(AdapterField.Mode) }
        assertFailsWith<IllegalArgumentException> { AdapterCommand.GetField(AdapterField.Signature) }
    }

    @Test
    fun theOtherCommandBytes() {
        assertEquals("#get_mode:x;", AdapterCommand.GetMode(InputChannel.X).text)
        assertEquals("236765745f6d6f64653a783b", hex(AdapterCommand.GetMode(InputChannel.X)))
        assertEquals("#get_mode:v;", AdapterCommand.GetMode(InputChannel.V).text)
        assertEquals("#set_mode:x,4;", AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer).text)
        assertEquals("237365745f6d6f64653a782c343b", hex(AdapterCommand.SetMode(InputChannel.X, InputMode.Tachometer)))
        assertEquals("#set_mode:u,2048;", AdapterCommand.SetMode(InputChannel.U, InputMode.MitutoyoSpc).text)
        assertEquals("#ref_axis:x,15;", AdapterCommand.RefAxis(InputChannel.X).text)
        assertEquals("237265665f617869733a782c31353b", hex(AdapterCommand.RefAxis(InputChannel.X)))
        assertEquals("#ref_axis:w,15;", AdapterCommand.RefAxis(InputChannel.W).text)
        assertEquals("#clear_counter:y;", AdapterCommand.ClearCounter(InputChannel.Y).text)
        assertEquals("23636c6561725f636f756e7465723a793b", hex(AdapterCommand.ClearCounter(InputChannel.Y)))
        assertEquals("#reboot:1000;", AdapterCommand.Reboot.text)
        assertEquals("237265626f6f743a313030303b", hex(AdapterCommand.Reboot))
        assertEquals("#get_sig:465307200,1;", AdapterCommand.GetSig(465_307_200L).text)
        assertContentEquals(byteArrayOf(0x07), AdapterCommand.Bel.encode())
        assertEquals(RequestKind.Raw, AdapterCommand.Bel.kind)
    }

    @Test
    fun theCommandsCheckTheirArguments() {
        assertFailsWith<IllegalArgumentException> { AdapterCommand.RefAxis(InputChannel.U) }
        assertFailsWith<IllegalArgumentException> { AdapterCommand.RefAxis(InputChannel.V) }
        assertFailsWith<IllegalArgumentException> { AdapterCommand.SetMode(InputChannel.X, InputMode.Unknown) }
    }

    @Test
    fun theWaitsOfEachCommand() {
        val commands = listOf(
            AdapterCommand.GetField(AdapterField.Mac), AdapterCommand.GetMode(InputChannel.Z),
            AdapterCommand.SetMode(InputChannel.Z, InputMode.QuadratureAB), AdapterCommand.ClearCounter(InputChannel.Z),
            AdapterCommand.Reboot,
        )
        commands.forEach { command ->
            assertEquals(500L, command.firstWaitMs, "$command")
            assertEquals(2000L, command.fullWaitMs, "$command")
            assertTrue(command.resendOnBusy, "$command")
        }
        // The full wait of #ref_axis:<ch>,<n>; is n × 100 ms.
        assertEquals(500L, AdapterCommand.RefAxis(InputChannel.X).firstWaitMs)
        assertEquals(1500L, AdapterCommand.RefAxis(InputChannel.X).fullWaitMs)
        assertEquals(2000L, AdapterCommand.RefAxis(InputChannel.X, 20).fullWaitMs)
        assertTrue(AdapterCommand.RefAxis(InputChannel.X).resendOnBusy)
        val sig = AdapterCommand.GetSig(1L)
        assertEquals(500L, sig.firstWaitMs)
        assertEquals(10_000L, sig.fullWaitMs)
        assertFalse(sig.resendOnBusy)
        assertEquals(RequestKind.Query, sig.kind)
        assertEquals(RequestKind.Command, AdapterCommand.Reboot.kind)
        assertEquals(InputChannel.Z, AdapterCommand.GetMode(InputChannel.Z).channel)
    }

    @Test
    fun theGetSigSecondsFormula() {
        // (nowMs − 1326369600000) / 1000
        assertEquals(416_318_400L, AdapterCommand.GetSig.secondsAt(1_742_688_000_000L)) // 2025-03-23 00:00:00 UTC
        assertEquals("#get_sig:416318400,1;", AdapterCommand.GetSig.at(1_742_688_000_000L).text)
        assertEquals("#get_sig:465307200,1;", AdapterCommand.GetSig.at(1_791_676_800_000L).text) // 2026-10-11 00:00:00 UTC
        assertEquals(0L, AdapterCommand.GetSig.secondsAt(AdapterCommand.GetSig.EPOCH_MS + 999L))
    }
}
