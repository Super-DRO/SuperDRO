package org.superdro.core.functions.thread

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandardDrillTablesTest {

    @Test
    fun metricTableCovers1MmTo68MmAndIncludesQuarterAndHalfTenthTapSizes() {
        val metric = StandardDrillTables.metricDrills
        assertEquals(1000.0, metric.first().diameterUm, 1e-6)
        assertEquals("1.0 mm", metric.first().label)
        assertEquals(68000.0, metric.last().diameterUm, 1e-6)
        assertEquals("68.0 mm", metric.last().label)

        for (i in 1 until metric.size) {
            assertTrue(metric[i].diameterUm > metric[i - 1].diameterUm)
        }

        val labels = metric.map { it.label }.toSet()
        assertTrue("1.25 mm" in labels)
        assertTrue("2.05 mm" in labels)
        assertTrue("3.25 mm" in labels)
        assertTrue("4.25 mm" in labels)
        assertTrue("6.75 mm" in labels)
        assertTrue("8.5 mm" in labels)
        assertTrue("10.25 mm" in labels)
    }

    @Test
    fun numberTableCoversNumber80ToNumber1InAscendingOrder() {
        val numbers = StandardDrillTables.numberDrills
        assertEquals(80, numbers.size)
        assertEquals("#80", numbers.first().label)
        assertEquals(342.9, numbers.first().diameterUm, 1e-6)
        assertEquals("#1", numbers.last().label)
        assertEquals(5791.2, numbers.last().diameterUm, 1e-6)

        val num7 = assertNotNull(numbers.firstOrNull { it.label == "#7" })
        assertEquals(0.2010 * 25400.0, num7.diameterUm, 1e-6)

        val num21 = assertNotNull(numbers.firstOrNull { it.label == "#21" })
        assertEquals(0.1590 * 25400.0, num21.diameterUm, 1e-6)
    }

    @Test
    fun letterTableCoversAToZInAscendingOrder() {
        val letters = StandardDrillTables.letterDrills
        assertEquals(26, letters.size)
        assertEquals("A", letters.first().label)
        assertEquals(5943.6, letters.first().diameterUm, 1e-6)
        assertEquals("Z", letters.last().label)
        assertEquals(10490.2, letters.last().diameterUm, 1e-6)

        val letterC = assertNotNull(letters.firstOrNull { it.label == "C" })
        assertEquals(0.2420 * 25400.0, letterC.diameterUm, 1e-6)
    }

    @Test
    fun fractionalTableCoversOneSixtyFourthToTwoInches() {
        val fractional = StandardDrillTables.fractionalDrills
        assertEquals(128, fractional.size)
        assertEquals("1/64 in", fractional.first().label)
        assertEquals(396.875, fractional.first().diameterUm, 1e-6)
        assertEquals("2 in", fractional.last().label)
        assertEquals(50800.0, fractional.last().diameterUm, 1e-6)

        val quarter = assertNotNull(fractional.firstOrNull { it.label == "1/4 in" })
        assertEquals(6350.0, quarter.diameterUm, 1e-6)
    }

    @Test
    fun nearestInSeriesHandlesBoundsAndBetweenStepValues() {
        val metricMatch = assertNotNull(StandardDrillTables.nearestInSeries(8538.58, DrillSeries.Metric))
        assertEquals("8.5 mm", assertNotNull(metricMatch.smallerOrEqual).label)
        assertEquals("8.6 mm", assertNotNull(metricMatch.largerOrEqual).label)
        assertEquals("8.5 mm", metricMatch.closest.label)

        val belowMin = assertNotNull(StandardDrillTables.nearestInSeries(200.0, DrillSeries.Metric))
        assertNull(belowMin.smallerOrEqual)
        assertEquals("1.0 mm", assertNotNull(belowMin.largerOrEqual).label)
        assertEquals("1.0 mm", belowMin.closest.label)

        val aboveMax = assertNotNull(StandardDrillTables.nearestInSeries(75000.0, DrillSeries.Metric))
        assertEquals("68.0 mm", assertNotNull(aboveMax.smallerOrEqual).label)
        assertNull(aboveMax.largerOrEqual)
        assertEquals("68.0 mm", aboveMax.closest.label)
    }
}
