package org.superdro.core.dataoutput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.model.DataOutputCadence
import org.superdro.core.model.DataOutputSecurity
import org.superdro.core.model.DataOutputTransport
import org.superdro.core.model.BindInterface

/** The statistics, the status, and the warning rules. */
class WarningRulesTest {

    private val cadence = DataOutputCadence.OnChangeAndInterval

    @Test
    fun eachWriteToEachClientCounts() {
        var statistics = OutputStatistics(startedAtMs = 0L)
        statistics = statistics.withWrite(true, 10L).withWrite(true, 20L).withWrite(false, 30L).withRecord()
        assertEquals(2L, statistics.sent)
        assertEquals(1L, statistics.failed)
        assertEquals(20L, statistics.lastSuccessAtMs)
        assertEquals(1L, statistics.records)
        assertEquals(33, statistics.recentFailurePercent)
        repeat(150) { statistics = statistics.withWrite(true, 40L) }
        assertEquals(OutputStatistics.RECENT_COUNT, statistics.recentFailures.size)
        assertEquals(0, statistics.recentFailurePercent)
    }

    @Test
    fun anErrorGivesAWarningFor30Seconds() {
        val error = DataOutputError(DataOutputErrorType.PublishFailed, 1000L, "192.168.1.30")
        val statistics = OutputStatistics(0L).withWrite(true, 1000L).withError(error)
        assertTrue(WarningRules.warning(statistics, DataOutputCadence.OnChange, 500L, 30_999L))
        assertFalse(WarningRules.warning(statistics, DataOutputCadence.OnChange, 500L, 31_000L))
    }

    @Test
    fun moreThan10PercentOfFailuresGiveAWarning() {
        var statistics = OutputStatistics(0L)
        repeat(90) { statistics = statistics.withWrite(true, 100L) }
        repeat(10) { statistics = statistics.withWrite(false, 100L) }
        assertFalse(WarningRules.warning(statistics, DataOutputCadence.OnChange, 500L, 200L))
        statistics = statistics.withWrite(false, 100L)
        assertTrue(WarningRules.warning(statistics, DataOutputCadence.OnChange, 500L, 200L))
    }

    @Test
    fun noSuccessFor5IntervalsGivesAWarningExceptOnChange() {
        val statistics = OutputStatistics(startedAtMs = 1000L)
        assertFalse(WarningRules.warning(statistics, cadence, 500L, 3500L))
        assertTrue(WarningRules.warning(statistics, cadence, 500L, 3501L))
        assertTrue(WarningRules.warning(statistics, DataOutputCadence.FixedInterval, 500L, 3501L))
        assertFalse(WarningRules.warning(statistics, DataOutputCadence.OnChange, 500L, 100_000L))
        val written = statistics.withWrite(true, 3000L)
        assertFalse(WarningRules.warning(written, cadence, 500L, 5500L))
        assertTrue(WarningRules.warning(written, cadence, 500L, 5501L))
    }

    @Test
    fun theStatusFollowsTheSenderState() {
        val statistics = OutputStatistics(0L)
        val error = DataOutputError(DataOutputErrorType.AcceptFailed, 10L, "192.168.1.20", 5800, "socket closed")
        assertEquals(DataOutputStatus.Stopped, WarningRules.status(SenderState.Stopped, statistics, cadence, 500L, 0L))
        assertEquals(DataOutputStatus.Starting, WarningRules.status(SenderState.Starting, statistics, cadence, 500L, 0L))
        assertEquals(DataOutputStatus.Ready, WarningRules.status(SenderState.Ready, statistics, cadence, 500L, 100_000L))
        assertEquals(DataOutputStatus.Sending, WarningRules.status(SenderState.Active, statistics, cadence, 500L, 100L))
        assertEquals(DataOutputStatus.Warning, WarningRules.status(SenderState.Active, statistics, cadence, 500L, 2600L))
        assertEquals(DataOutputStatus.Warning, WarningRules.status(SenderState.Failed(error, 5010L), statistics, cadence, 500L, 20L))
    }

    @Test
    fun theDiagnosticsHaveTheLinesOfTheDialog() {
        val error = DataOutputError(DataOutputErrorType.PublishFailed, 1000L, "192.168.1.30", detail = "Broken pipe")
        val statistics = OutputStatistics(0L).withWrite(true, 2000L).withWrite(false, 2500L).withError(error)
        val state = DataOutputState(
            transport = DataOutputTransport.Tcp, security = DataOutputSecurity.Psk, bindInterface = BindInterface.WiFi, port = 5800,
            brokerHost = "", brokerPort = 8883, brokerTls = true, running = true, block = null, sender = SenderState.Active,
            status = DataOutputStatus.Warning, listening = listOf("192.168.1.20:5800"), clients = listOf("192.168.1.30"),
            statistics = statistics,
        )
        val diagnostics = Diagnostics.of(state, nowMs = 4000L)
        assertEquals(listOf("192.168.1.20:5800"), diagnostics.listening)
        assertEquals(1, diagnostics.clientCount)
        assertEquals(error, diagnostics.problem)
        assertEquals(3000L, diagnostics.problemAgeMs)
        assertEquals(2000L, diagnostics.lastSuccessAgeMs)
        assertEquals(1L, diagnostics.sent)
        assertEquals(1L, diagnostics.failed)
        assertEquals(50, diagnostics.recentFailurePercent)
        assertTrue(diagnostics.showCounts)
        assertEquals(null, diagnostics.broker)
        assertEquals("[fe80::1]:5800", hostAndPort("fe80::1", 5800))
        assertEquals("PublishFailed 192.168.1.30: Broken pipe", error.logText())
    }
}
