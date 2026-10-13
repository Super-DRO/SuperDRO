package org.superdro.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The adapter fields and the MAC + 2 rule. */
class AdapterFieldValuesTest {

    @Test
    fun theMacPlusTwoHasTheCarry() {
        assertEquals("24:6f:28:a1:b2:c5", AdapterFieldValues.macPlusTwo("246f28a1b2c3"))
        assertEquals("30:ae:a4:a1:b2:c5", AdapterFieldValues.macPlusTwo("30aea4a1b2c3"))
        assertEquals("24:6f:28:a1:b3:00", AdapterFieldValues.macPlusTwo("246f28a1b2fe"))
        assertEquals("24:6f:28:a1:b3:01", AdapterFieldValues.macPlusTwo("246f28a1b2ff"))
        assertEquals("24:6f:28:a2:00:00", AdapterFieldValues.macPlusTwo("246f28a1fffe"))
        assertEquals("00:00:00:00:00:01", AdapterFieldValues.macPlusTwo("ffffffffffff"))
        assertEquals("00:00:00:00:00:02", AdapterFieldValues.macPlusTwo("000000000000"))
        assertEquals("24:6f:28:a1:b2:c5", AdapterFieldValues.macPlusTwo("246F28A1B2C3"))
        assertNull(AdapterFieldValues.macPlusTwo("246f28a1b2c"))
        assertNull(AdapterFieldValues.macPlusTwo("24:6f:28:a1:b2:c3"))
    }

    @Test
    fun theMacIsExactly12HexDigitsInLowerCase() {
        assertEquals("246f28a1b2c3", AdapterFieldValues.parseMac("246F28A1B2C3"))
        assertNull(AdapterFieldValues.parseMac("246f28a1b2c"))
        assertNull(AdapterFieldValues.parseMac("246f28a1b2c3a"))
        assertNull(AdapterFieldValues.parseMac("246f28a1b2cg"))
        assertNull(AdapterFieldValues.parseMac(""))
    }

    @Test
    fun theUuidIsAUuidTextThatIsNotZero() {
        assertEquals("123e4567-e89b-12d3-a456-426614174000", AdapterFieldValues.parseUuid("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", AdapterFieldValues.parseUuid("C0FFEE00-1234-4ABC-8DEF-0123456789AB"))
        assertNull(AdapterFieldValues.parseUuid("00000000-0000-0000-0000-000000000000"))
        assertNull(AdapterFieldValues.parseUuid("123e4567e89b12d3a456426614174000"))
        assertNull(AdapterFieldValues.parseUuid("123e4567-e89b-12d3-a456-42661417400"))
        assertNull(AdapterFieldValues.parseUuid("not a uuid"))
    }

    @Test
    fun theDateAndTheUptimeAreHexAndTheFeaturesAreDecimal() {
        assertEquals(0x66000000L, AdapterFieldValues.parseHex("66000000"))
        assertEquals(3600L, AdapterFieldValues.parseHex("e10"))
        assertEquals(3600L, AdapterFieldValues.parseHex("E10"))
        assertNull(AdapterFieldValues.parseHex(""))
        assertNull(AdapterFieldValues.parseHex("-1"))
        assertNull(AdapterFieldValues.parseHex("12g"))
        assertNull(AdapterFieldValues.parseHex("1" + "0".repeat(16)))
        assertEquals(Long.MAX_VALUE, AdapterFieldValues.parseHex("7fffffffffffffff"))
        assertEquals(1L, AdapterFieldValues.parseDecimal("1"))
        assertEquals(10L, AdapterFieldValues.parseDecimal("10"))
        assertNull(AdapterFieldValues.parseDecimal("a"))
        assertNull(AdapterFieldValues.parseDecimal(""))
        assertNull(AdapterFieldValues.parseDecimal("-1"))
    }

    @Test
    fun theFieldTableAndTheGroups() {
        assertEquals(
            listOf(1 to null, 2 to "uuid", 3 to "name", 4 to "model", 5 to "mac", 6 to "version", 7 to "build", 8 to "date", 9 to "uptime", 10 to "features", 100 to null),
            AdapterField.entries.map { it.id to it.getName },
        )
        assertEquals("0a", AdapterField.Features.hexId)
        assertEquals("64", AdapterField.Signature.hexId)
        assertNull(AdapterField.forId(0x0B))
        assertEquals(listOf(AdapterField.Mac, AdapterField.Uuid, AdapterField.Features, AdapterField.Uptime, AdapterField.Model), AdapterField.idGroup)
        assertEquals(listOf(AdapterField.Name, AdapterField.Version, AdapterField.Build, AdapterField.Date), AdapterField.secondaryGroup)
        assertEquals(
            listOf(Lifetime.Constant, Lifetime.Session, Lifetime.Constant, Lifetime.Constant, Lifetime.Constant, Lifetime.Constant, Lifetime.Constant, Lifetime.Dynamic, Lifetime.Session),
            AdapterField.entries.filter { it.gettable }.map { it.lifetime },
        )
    }

    @Test
    fun theDialectConstants() {
        val timing = AdapterDialect.Esp32TwoWay.timing
        assertEquals(7_000L, timing.noDataMs)
        assertEquals(30_000L, timing.stateLimitMs)
        assertEquals(2_000L, timing.queueStartMs)
        assertEquals(500L, timing.firstWaitMs)
        assertEquals(2_000L, timing.fullWaitMs)
        assertEquals(100L, timing.referenceWaitStepMs)
        assertEquals(10_000L, timing.signatureWaitMs)
        assertEquals(5_000L, timing.idRetryMs)
        assertEquals(10_000L, timing.resultLimitMs)
        assertEquals(5_000L, timing.readAgainLimitMs)
        assertEquals(750L, timing.noDataRecoveryMs)
        assertEquals(5_000L, timing.tcpConnectMs)
        assertEquals(4_000L, timing.bluetoothWriteWatchdogMs)
        assertEquals(50L, timing.queueGapMs)
        assertEquals(500L, timing.belPeriodMs)
        assertEquals(3, timing.maxTransmissions)
        assertEquals(250L, timing.restartCloseMs)
        assertEquals(2_000L, timing.restartConnectMs)
        assertEquals(timing, AdapterDialect.LegacyOneWay.timing)
        assertEquals(timing, AdapterDialect.Esp32TwoWayTcp.timing)
        assertEquals(listOf(true, true, false), AdapterDialect.entries.map { it.answersCommands })
        assertEquals(listOf(true, true, false), AdapterDialect.entries.map { it.allMessageForms })
    }
}
