package org.superdro.core.fakeadapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AdapterAddress
import org.superdro.core.util.Clock

/**
 * The scenarios for the tests of the adapter probe app: a probe that follows the time for T09,
 * and a reference search that ends with User timeout for T11.
 */
class FakeProbeAppScenariosTest {

    @Test
    fun theTimedProbeGivesTheStepsOfT09InEachFrame() = runTest {
        val h = FakeHarness(this)
        h.connect(FakeScenarios.ProbeTimed, record = true)
        runCurrent()
        advanceTimeBy(30_999)
        runCurrent()
        // The readout frames: the answers to the start queries of the session are other chunks.
        val chunks = h.records.getValue(1).filter { chunk -> chunk.bytes.decodeToString().contains('X') }
        val probe = chunks.associate { chunk ->
            val values = Regex("P([01]);").findAll(chunk.bytes.decodeToString()).map { it.groupValues[1].toInt() }.toList()
            assertEquals(1, values.size, chunk.bytes.decodeToString())
            chunk.timeMs to values.single()
        }
        assertEquals((0L until 31_000L step 50L).toList(), probe.keys.toList())
        val expected = mapOf(
            0L to 0, 5_450L to 0, 5_500L to 1, 14_450L to 1, 14_500L to 0, 19_950L to 0,
            20_000L to 1, 20_450L to 1, 20_500L to 0, 20_950L to 0, 21_000L to 1, 29_500L to 0, 29_950L to 0, 30_000L to 0,
        )
        expected.forEach { (time, value) -> assertEquals(value, probe.getValue(time), "probe at $time ms") }
        // The taps: 10 closures of 500 ms from 20 s to 30 s.
        val closures = probe.entries.zipWithNext().count { (a, b) -> a.value == 0 && b.value == 1 && b.key in 20_000L until 30_000L }
        assertEquals(10, closures)
        assertEquals(30_000L, TimedProbe.T09.cycleMs)
        h.manager.disconnectAll()
        runCurrent()
    }

    @Test
    fun aReferenceSearchWithNoMarkEndsWithUserTimeout() = runTest {
        val adapter = FakeAdapter(FakeScenarios.ReferenceTimeout, Clock { testScheduler.currentTime })
        val transport = adapter.transport(AdapterAddress.Fake(FakeScenarios.ReferenceTimeout.name))
        transport.open()
        val statuses = ArrayList<Pair<Long, String>>()
        backgroundScope.launch {
            transport.incoming.collect { bytes ->
                Regex("\\$[0-9a-f]+;").findAll(bytes.decodeToString()).forEach { statuses += testScheduler.currentTime to it.value }
            }
        }
        runCurrent()
        val start = testScheduler.currentTime
        transport.write("#ref_axis:x,15;".encodeToByteArray())
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(listOf("\$0aa3;", "\$78a0;"), statuses.map { it.second })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(start to "\$0aa3;", start to "\$78a0;", start + 15_000L to "\$7816;"), statuses)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(statuses.none { it.second == "\$78a1;" }, "No Finished after a User timeout: $statuses")
        transport.close()
    }
}
