package org.superdro.validation.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.superdro.core.protocol.AdapterMessage.AxisCount
import org.superdro.core.protocol.AdapterMessage.BinaryInput
import org.superdro.core.protocol.AdapterMessage.Timestamp
import org.superdro.core.protocol.AdapterParser
import org.superdro.core.protocol.TimeBase

/**
 * The time stamps, the offset, the drift rule, the reset, and the
 * wall clock formula of the data output. The worked examples are the test data.
 */
class TimeBaseValidationTest {

    private fun AdapterParser.at(timeMs: Long, text: String) = feed(text.encodeToByteArray(), timeMs)

    private val t1 = 50_000L

    @Test
    fun example_first_at_gives_offset_t1_minus_1000_and_t_adapter_t1() {
        val parser = AdapterParser()
        assertEquals(listOf(Timestamp(1000L, t1)), parser.at(t1, "@3E8;"))
        assertEquals(t1 - 1000L, parser.timeBase.offsetMs)
        assertEquals(listOf(Timestamp(2000L, t1 + 1000L)), parser.at(t1 + 1000L, "@7D0;"))
        assertEquals(t1 - 1000L, parser.timeBase.offsetMs)
    }

    @Test
    fun example_readouts_after_an_at_get_the_time_of_that_at_until_the_next_at() {
        val parser = AdapterParser()
        parser.at(t1, "@3E8;")
        assertEquals(listOf(AxisCount('X', 1, t1)), parser.at(t1 + 40L, "X1;"))
        assertEquals(listOf(BinaryInput('P', 1, t1)), parser.at(t1 + 90L, "P1;"))
        parser.at(t1 + 1000L, "@7D0;")
        assertEquals(listOf(AxisCount('Y', 2, t1 + 1000L)), parser.at(t1 + 1030L, "Y2;"))
    }

    @Test
    fun example_drift_of_400_ms_sets_a_new_offset() {
        val parser = AdapterParser()
        parser.at(t1, "@3E8;")
        parser.at(t1 + 1000L, "@7D0;")
        // `@BB8;` (3000) arrives at t1 + 2400. The predicted time is t1 + 2000.
        assertEquals(listOf(Timestamp(3000L, t1 + 2400L)), parser.at(t1 + 2400L, "@BB8;"))
        assertEquals(t1 + 2400L - 3000L, parser.timeBase.offsetMs)
    }

    @Test
    fun drift_of_exactly_250_ms_keeps_the_offset_and_251_ms_changes_it() {
        val keep = AdapterParser()
        keep.at(t1, "@3E8;")
        assertEquals(listOf(Timestamp(3000L, t1 + 2000L)), keep.at(t1 + 2250L, "@BB8;"))
        assertEquals(t1 - 1000L, keep.timeBase.offsetMs)
        val change = AdapterParser()
        change.at(t1, "@3E8;")
        assertEquals(listOf(Timestamp(3000L, t1 + 2251L)), change.at(t1 + 2251L, "@BB8;"))
        assertEquals(t1 + 2251L - 3000L, change.timeBase.offsetMs)
    }

    @Test
    fun negative_drift_does_not_change_the_offset() {
        val parser = AdapterParser()
        parser.at(t1, "@3E8;")
        // The adapter clock is fast: `@BB8;` arrives at t1 + 1200, before the predicted t1 + 2000.
        assertEquals(listOf(Timestamp(3000L, t1 + 2000L)), parser.at(t1 + 1200L, "@BB8;"))
        assertEquals(t1 - 1000L, parser.timeBase.offsetMs)
    }

    @Test
    fun dc18_offset_resets_when_the_adapter_time_goes_back() {
        val parser = AdapterParser()
        parser.at(t1, "@3E8;")
        // The adapter restarted: k = 100 is less than the last k = 1000.
        assertEquals(listOf(Timestamp(100L, t1 + 50L)), parser.at(t1 + 50L, "@64;"))
        assertEquals(t1 + 50L - 100L, parser.timeBase.offsetMs)
    }

    @Test
    fun readout_time_with_no_at_is_the_arrival_time_of_its_first_chunk() {
        val parser = AdapterParser()
        assertEquals(listOf(AxisCount('X', 5, 700L)), parser.at(700L, "X5;"))
        assertEquals(emptyList(), parser.at(800L, "Z-1"))
        assertEquals(listOf(AxisCount('Z', -12, 800L)), parser.at(850L, "2;"))
        assertNull(parser.timeBase.offsetMs)
    }

    @Test
    fun reset_gives_no_offset_and_readouts_get_their_own_time_again() {
        val parser = AdapterParser()
        parser.at(t1, "@3E8;")
        parser.resetTime()
        assertNull(parser.timeBase.offsetMs)
        assertEquals(listOf(AxisCount('X', 1, t1 + 300L)), parser.at(t1 + 300L, "X1;"))
        // The next `@` sets a new offset.
        assertEquals(listOf(Timestamp(5000L, t1 + 400L)), parser.at(t1 + 400L, "@1388;"))
        assertEquals(t1 + 400L - 5000L, parser.timeBase.offsetMs)
        // A new parser (a new link) has no offset.
        assertNull(AdapterParser().timeBase.offsetMs)
    }

    @Test
    fun wall_clock_time_of_a_record_is_wall_now_minus_monotonic_now_plus_t() {
        assertEquals(1_700_000_000_000L - 60_000L + 59_250L, TimeBase.wallClockMs(1_700_000_000_000L, 60_000L, 59_250L))
    }

    @Test
    fun unit_maximum_value_is_0xFFFFFFFF_times_1000() {
        assertEquals(0xFFFFFFFFL * 1000L, TimeBase.MAX_ADAPTER_TIME)
        assertEquals(0x3E7FFFFFC18L, TimeBase.MAX_ADAPTER_TIME)
    }
}
