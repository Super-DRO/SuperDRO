package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.superdro.core.model.DataOutputConfig

class DataOutputLimitsTest {

    @Test
    fun theMinimumGapUsesAnIntegerDivision() {
        assertEquals(50L, DataOutputLimits.minimumGapMs(20))
        assertEquals(100L, DataOutputLimits.minimumGapMs(10))
        assertEquals(333L, DataOutputLimits.minimumGapMs(3))
        assertEquals(1000L, DataOutputLimits.minimumGapMs(1))
        assertFailsWith<IllegalArgumentException> { DataOutputLimits.minimumGapMs(21) }
    }

    @Test
    fun theDefaultsAreInTheLimits() {
        val config = DataOutputConfig()
        assertEquals(true, config.port in DataOutputLimits.PORT)
        assertEquals(true, config.intervalMs in DataOutputLimits.INTERVAL_MS)
        assertEquals(true, config.maxRateHz in DataOutputLimits.MAX_RATE_HZ)
        assertEquals(true, config.precision in DataOutputLimits.PRECISION)
    }
}
