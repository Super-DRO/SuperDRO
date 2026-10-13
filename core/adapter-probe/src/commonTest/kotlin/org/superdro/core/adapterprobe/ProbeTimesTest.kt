package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals

/** The times of the session are the times of the log, as `adapter_probe.py --analyze` reads them. */
class ProbeTimesTest {

    @Test
    fun secondsAreTheTimesOfTheAnalysis() {
        // The analysis reads "511.866" and "562.316" and divides by 1000. A division of the µs by 1e6 gives 50.4 for this gap.
        assertEquals(511.866 / 1000.0, ProbeLogFormat.seconds(511_866L))
        assertEquals(562.316 / 1000.0, ProbeLogFormat.seconds(562_316L))
        assertEquals("50.5", PyText.ms(ProbeLogFormat.seconds(562_316L) - ProbeLogFormat.seconds(511_866L)))
        assertEquals("50.4", PyText.ms(562_316L / 1e6 - 511_866L / 1e6))
    }
}
