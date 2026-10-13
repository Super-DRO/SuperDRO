package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals

/** The log format 1. The expected lines are the lines that the PC tool writes. */
class ProbeLogFormatTest {

    @Test
    fun blockLinesOfTheLogFormat() {
        // The length column is the number of bytes, as the tool writes it (len(data)). These are
        // 17 bytes, so the line has 17.
        assertEquals(
            "     123.456 RX      17 58 31 32 37 30 30 3b 59 2d 33 31 37 35 3b 5a 30 3b | X12700;Y-3175;Z0;",
            ProbeLogFormat.block(123_456L, ProbeLogFormat.RX, "X12700;Y-3175;Z0;".encodeToByteArray()),
        )
        assertEquals(
            "    2001.020 TX      11 22 23 67 65 74 3a 6d 61 63 3b 22 | \"#get:mac;\"",
            ProbeLogFormat.block(2_001_020L, ProbeLogFormat.TX, "\"#get:mac;\"".encodeToByteArray()),
        )
    }

    @Test
    fun theAsciiColumnHasADotForEachOtherByte() {
        assertEquals("       0.000 TX       1 07 | .", ProbeLogFormat.block(0L, ProbeLogFormat.TX, byteArrayOf(0x07)))
        assertEquals("       5.500 RX2      3 50 31 3b | P1;", ProbeLogFormat.block(5_500L, ProbeLogFormat.RX2, "P1;".encodeToByteArray()))
        assertEquals("       1.000 RX       4 58 31 0d 0a | X1..", ProbeLogFormat.block(1_000L, ProbeLogFormat.RX, byteArrayOf(0x58, 0x31, 0x0D, 0x0A)))
    }

    @Test
    fun noteLinesAndTimes() {
        assertEquals(
            "    2003.311 NOTE request '\"#get:mac;\"': ack 1.2 ms",
            ProbeLogFormat.note(2_003_311L, "request '\"#get:mac;\"': ack 1.2 ms"),
        )
        assertEquals("      10.000 NOTE a / b", ProbeLogFormat.note(10_000L, "a\nb"))
        assertEquals("  123456.789", ProbeLogFormat.time(123_456_789L))
        assertEquals("       0.001", ProbeLogFormat.time(1L))
    }

    @Test
    fun headerLines() {
        assertEquals(
            listOf(
                "# adapter_probe.py log, format 1",
                "# start (UTC): 2026-10-12T08:00:00Z; mode: T03-idle; label: mill adapter",
                "# link: TCP 192.168.1.50:8080 (TCP_NODELAY on)",
                "# keep-alive at start: on; time scale: 1.0",
                "# columns: time_ms RX|TX length hex_bytes | ascii  (or: time_ms NOTE text)",
                "# writer: SuperDRO adapter probe 1.0",
            ),
            ProbeLogFormat.header("2026-10-12T08:00:00Z", "T03-idle", "mill adapter", "TCP 192.168.1.50:8080 (TCP_NODELAY on)", true, "SuperDRO adapter probe 1.0"),
        )
        assertEquals("# start (UTC): x; mode: interactive; label: -", ProbeLogFormat.header("x", "interactive", null, "l", false, "w")[1])
        assertEquals("2026-10-12T08:00:00Z", ProbeTexts.utcSecond(ProbeHarness.WALL_START_MS))
        assertEquals("20261012-080000", ProbeTexts.fileStamp(ProbeHarness.WALL_START_MS, kotlinx.datetime.TimeZone.UTC))
    }

    @Test
    fun theLogIsAsciiWithBackslashForms() {
        assertEquals("caf\\xe9 \\u20ac \\U0001f600", ProbeLogFormat.ascii("café € 😀"))
        assertEquals("plain", ProbeLogFormat.ascii("plain"))
    }

    @Test
    fun theFilesGetANumberWhenTheNameIsInUse() {
        val files = MemoryProbeFiles()
        assertEquals("a.log", files.open("a").logName)
        assertEquals("a-2.log", files.open("a").logName)
        assertEquals("a-3.bin", files.open("a").binName)
    }
}
