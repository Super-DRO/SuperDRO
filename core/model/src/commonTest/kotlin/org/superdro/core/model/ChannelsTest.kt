package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The channel rules and the default channels of the outputs. */
class ChannelsTest {

    @Test
    fun theUserCanSelectOnlyXYZWUVForAnAxis() {
        assertEquals(listOf('x', 'y', 'z', 'w', 'u', 'v'), Channels.axisChannels)
        assertTrue(Channels.isAxisChannel('U'))
        "abcdefrs".forEach { letter -> assertFalse(Channels.isAxisChannel(letter), "$letter") }
    }

    @Test
    fun theProbePortsArePAndQ() {
        assertEquals(listOf('p', 'q'), Channels.probeChannels)
        assertTrue(Channels.isProbeChannel('Q'))
        assertFalse(Channels.isProbeChannel('r'))
        assertFalse(Channels.isProbeChannel('s'))
    }

    @Test
    fun theTachometerUsesT() {
        assertEquals('t', Channels.TACHOMETER)
    }

    @Test
    fun theParserIgnoresAToFAndRAndS() {
        "ABCDEFRSabcdefrs".forEach { letter -> assertTrue(Channels.isIgnored(letter), "$letter") }
        "xyzwuvpqt".forEach { letter -> assertFalse(Channels.isIgnored(letter), "$letter") }
    }

    @Test
    fun theDefaultChannelsOfTheOutputs() {
        assertEquals(
            mapOf(
                AxisId.X to ('x' to 'w'),
                AxisId.Y to ('y' to 'w'),
                AxisId.Z to ('z' to 'w'),
                AxisId.Angular to ('w' to null),
                AxisId.Tachometer to ('t' to null),
                AxisId.Caliper to ('w' to null),
            ),
            AxisId.entries.associateWith { axis -> Channels.defaultPrimary(axis) to Channels.defaultSecondary(axis) },
        )
        assertEquals('p', ProbeConfig().channel)
    }
}
