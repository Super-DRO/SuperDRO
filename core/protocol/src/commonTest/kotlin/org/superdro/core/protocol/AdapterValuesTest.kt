package org.superdro.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The register model and the value rules. */
class AdapterValuesTest {

    private val macKey = ValueKey.Field(AdapterField.Mac)
    private val nameKey = ValueKey.Field(AdapterField.Name)
    private val uptimeKey = ValueKey.Field(AdapterField.Uptime)

    private fun full(): AdapterValues = AdapterValues()
        .withInfo(0x05, "246f28a1b2c3", 10L)
        .withInfo(0x02, "c0ffee00-1234-4abc-8def-0123456789ab", 10L)
        .withInfo(0x0A, "1", 10L)
        .withInfo(0x09, "e10", 10L)
        .withInfo(0x04, "tda-example", 10L)
        .withInfo(0x03, "Shop adapter", 10L)
        .withInfo(0x06, "1.4.2", 10L)
        .withInfo(0x07, "b42", 10L)
        .withInfo(0x08, "66000000", 10L)
        .withInfo(0x01, "x000f0002", 10L)
        .withDirty(0x05, 10L)
        .withReference(InputChannel.X, ReferenceState.Finished, 10L)

    @Test
    fun theStatesOfANewValue() {
        val values = AdapterValues()
        ValueKey.all.forEach { key -> assertEquals(ValueState.Empty, values.state(key), "$key") }
        assertEquals(9 + 6 + 4 + 1, ValueKey.all.size)
        assertNull(values.reportedIdentity)
    }

    @Test
    fun eachInfoGoesIntoTheFieldThatItNames() {
        val values = full()
        assertEquals("246f28a1b2c3", values.mac.value)
        assertEquals(ValueState.Reported(10L, ValueSource.LegacyGet), values.mac.state)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", values.uuid.value)
        assertEquals(1L, values.features.value)
        assertEquals(3600L, values.uptime.value)
        assertEquals("tda-example", values.model.value)
        assertEquals("Shop adapter", values.name.value)
        assertEquals("1.4.2", values.version.value)
        assertEquals("b42", values.build.value)
        assertEquals(0x66000000L, values.date.value)
        assertEquals(InputMode.QuadratureAB, values.modes.getValue(InputChannel.X).value?.current)
        assertEquals(setOf(InputChannel.X, InputChannel.Z), values.dirty.value)
        assertEquals(ValueState.Reported(10L, ValueSource.Pushed), values.dirty.state)
        assertEquals(ReferenceState.Finished, values.references.getValue(InputChannel.X).value)
        assertEquals("24:6f:28:a1:b2:c5", values.bluetoothId)
        assertEquals("24:6f:28:a1:b2:c5", values.reportedIdentity)
    }

    @Test
    fun theIdentityIsTheMacPlusTwoElseTheUuid() {
        val uuidOnly = AdapterValues().withInfo(0x02, "c0ffee00-1234-4abc-8def-0123456789ab", 0L)
        assertEquals("c0ffee00-1234-4abc-8def-0123456789ab", uuidOnly.reportedIdentity)
        assertNull(uuidOnly.bluetoothId)
    }

    @Test
    fun noSecondQueryWhileFetchingForLessThan5000Ms() {
        val fetching = AdapterValues().fetching(macKey, 1_000L)
        assertEquals(ValueState.Fetching(1, 1_000L), fetching.state(macKey))
        assertFalse(fetching.shouldQuery(macKey, 5_999L))
        assertTrue(fetching.shouldQuery(macKey, 6_000L))
        assertEquals(ValueState.Fetching(2, 6_000L), fetching.fetching(macKey, 6_000L).state(macKey))
        assertTrue(AdapterValues().shouldQuery(macKey, 0L))
        // A reported value can get a new query. The value stays while it is "Fetching".
        val reported = full().fetching(nameKey, 20L)
        assertEquals(ValueState.Fetching(1, 20L), reported.state(nameKey))
        assertEquals("Shop adapter", reported.name.value)
    }

    @Test
    fun aValueWithNoCommandIsUnsupported() {
        val key = ValueKey.Reference(InputChannel.Y)
        assertNull(key.command)
        assertFalse(AdapterValues().shouldQuery(key, 0L))
        assertEquals(ValueState.Failed(FailureCause.Unsupported, 5L), AdapterValues().read(key, 5L).state(key))
        assertEquals(ValueState.Failed(FailureCause.Unsupported, 5L), AdapterValues().read(ValueKey.Dirty, 5L).state(ValueKey.Dirty))
        assertEquals(ValueState.Fetching(1, 5L), AdapterValues().read(macKey, 5L).state(macKey))
    }

    @Test
    fun aValueThatIsNotValidIsDropped() {
        val before = AdapterValues().fetching(macKey, 1L)
        listOf(
            0x05 to "246f28a1b2c", 0x05 to "246f28a1b2c3a", 0x02 to "00000000-0000-0000-0000-000000000000",
            0x02 to "c0ffee00", 0x08 to "zz", 0x09 to "", 0x0A to "x1", 0x01 to "X000f0002", 0x01 to "q000f0002",
            0x64 to "QUJD", 0x0B to "other", 0xFF to "",
        ).forEach { (field, text) ->
            assertSame(before, before.withInfo(field, text, 9L), "?${field.toString(16)}$text")
        }
        assertEquals(ValueState.Fetching(1, 1L), before.state(macKey))
    }

    @Test
    fun aSmallerUptimeClearsTheValuesThatAreNotConstant() {
        val values = full()
        assertTrue(values.isRestart(5L))
        assertFalse(values.isRestart(3600L))
        val restarted = values.withInfo(0x09, "5", 20L)
        assertEquals(5L, restarted.uptime.value)
        assertEquals(ValueState.Reported(20L, ValueSource.LegacyGet), restarted.uptime.state)
        // Constant values stay.
        assertEquals(values.uuid, restarted.uuid)
        assertEquals(values.model, restarted.model)
        assertEquals(values.mac, restarted.mac)
        assertEquals(values.version, restarted.version)
        assertEquals(values.build, restarted.build)
        assertEquals(values.date, restarted.date)
        // Session and dynamic values are empty.
        assertEquals(AdapterValue(), restarted.name)
        assertEquals(AdapterValue(), restarted.features)
        assertEquals(AdapterValue(), restarted.dirty)
        assertTrue(restarted.modes.values.all { it == AdapterValue<ChannelModes>() })
        assertTrue(restarted.references.values.all { it == AdapterValue<ReferenceState>() })
        // A larger uptime is not a restart.
        assertEquals("Shop adapter", values.withInfo(0x09, "e11", 20L).name.value)
    }

    @Test
    fun aSessionDropMarksEachFetchingValueFailed() {
        val values = full().fetching(nameKey, 30L).fetching(ValueKey.Mode(InputChannel.Z), 30L)
        val dropped = values.dropped(40L)
        assertEquals(ValueState.Failed(FailureCause.SessionDropped, 40L), dropped.state(nameKey))
        assertEquals(ValueState.Failed(FailureCause.SessionDropped, 40L), dropped.state(ValueKey.Mode(InputChannel.Z)))
        assertEquals("Shop adapter", dropped.name.value)
        assertEquals(values.mac, dropped.mac)
    }

    @Test
    fun onlyTheSixModeValuesAreWritable() {
        val writable = ValueKey.all.filter { it.writable }
        assertEquals(InputChannel.entries.map { ValueKey.Mode(it) }, writable)
    }

    @Test
    fun theLifetimesAndTheAgeLimit() {
        val values = full()
        assertEquals(Lifetime.Dynamic, uptimeKey.lifetime)
        assertFalse(values.isStale(uptimeKey, 5_009L))
        assertTrue(values.isStale(uptimeKey, 5_010L))
        assertFalse(values.isStale(macKey, 1_000_000L))
        assertEquals(Lifetime.Dynamic, ValueKey.Reference(InputChannel.X).lifetime)
        assertEquals(Lifetime.Session, ValueKey.Dirty.lifetime)
    }

    @Test
    fun aGetModeMakesTheModeUnknownWithAnEmptyList() {
        val values = full().fetching(ValueKey.Mode(InputChannel.X), 50L).withModeUnknown(InputChannel.X)
        val x = values.modes.getValue(InputChannel.X)
        assertEquals(InputMode.Unknown, x.value?.current)
        assertEquals(emptyList(), x.value?.supported)
        assertEquals(ValueState.Fetching(1, 50L), x.state)
    }

    @Test
    fun aFailedReadKeepsTheLastValue() {
        val failed = full().failed(nameKey, FailureCause.Status(0x21), 60L)
        assertEquals("Shop adapter", failed.name.value)
        assertEquals(ValueState.Failed(FailureCause.Status(0x21), 60L), failed.state(nameKey))
    }
}
