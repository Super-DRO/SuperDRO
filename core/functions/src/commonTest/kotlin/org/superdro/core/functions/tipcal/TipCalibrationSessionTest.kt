package org.superdro.core.functions.tipcal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.superdro.core.model.ProbeProfile

/** The session JSON, its digest, the save rules, and the calibration record. */
class TipCalibrationSessionTest {

    private val knownSession = TipCalibrationSession(
        profile = "probe-7",
        complete = true,
        gauge = 25000.0,
        started = "2026-10-11 13:52:10",
        completed = "2026-10-11 13:58:02",
        positions = listOf(
            SessionPosition(
                clock = ClockPosition.Nine,
                x = -11506.2,
                y = 3.1,
                sigmaX = 0.7,
                sigmaY = 0.2,
                spread = 0.7,
                touches = listOf(SessionTouch(x = -11505.4, y = 3.0, vx = -310.5, vy = 0.0, t = 51234567L)),
            ),
        ),
        result = SessionResult(
            centerX = 0.4, centerY = 0.1, radius = 11506.3, effective = 1987.4,
            residual = 0.6, repeatability = 1.1, effectiveX = 1987.0, effectiveY = 1987.8,
        ),
    )

    private val knownText =
        """{"format":1,"profile":"probe-7","complete":true,"gauge":25000.0,""" +
            """"started":"2026-10-11 13:52:10","completed":"2026-10-11 13:58:02",""" +
            """"positions":[{"clock":"NINE","x":-11506.2,"y":3.1,"sigmaX":0.7,"sigmaY":0.2,"spread":0.7,""" +
            """"touches":[{"x":-11505.4,"y":3.0,"vx":-310.5,"vy":0.0,"t":51234567}]}],""" +
            """"result":{"centerX":0.4,"centerY":0.1,"radius":11506.3,"effective":1987.4,"residual":0.6,""" +
            """"repeatability":1.1,"effectiveX":1987.0,"effectiveY":1987.8}}"""

    private fun position(clock: ClockPosition, x: Double, y: Double): CapturedPosition = CapturedPosition(
        clock = clock,
        touches = List(3) { index -> Touch(x, y, -300.0, Double.NaN, 1000L + index) },
        xUm = x,
        yUm = y,
        sigmaXUm = 0.0,
        sigmaYUm = 0.0,
        spreadUm = 0.0,
    )

    private fun completeRun(): TipCalibrationRun = TipCalibrationRun(
        profileId = "probe-7",
        started = "2026-10-11 13:52:10",
        gaugeUm = 25000.0,
        positions = listOf(
            position(ClockPosition.Nine, -11505.9, 0.1),
            position(ClockPosition.Three, 11506.7, 0.1),
            position(ClockPosition.Twelve, 0.4, 11506.4),
            position(ClockPosition.Six, 0.4, -11506.2),
        ),
    )

    @Test
    fun theSessionHasTheKeysInTheExpectedOrder() {
        assertEquals(knownText, TipCalibrationSessions.encode(knownSession))
    }

    @Test
    fun theDigestOfAKnownSessionTextIsTheSha256OfItsUtf8Bytes() {
        // The value comes from Python hashlib.sha256(text.encode()).hexdigest().
        assertEquals("81ac84cb80005e22715ff5374a593c195ed431ef9b8d2a54c083c43f80329f60", TipCalibrationSessions.digest(knownText))
    }

    @Test
    fun theSessionGoesToJsonAndBack() {
        assertEquals(knownSession, TipCalibrationSessions.decode(TipCalibrationSessions.encode(knownSession)))
        assertEquals(knownSession, TipCalibrationSessions.decode(knownText.replace("{\"format\":1,", "{\"format\":1,\"later\":true,")))
    }

    @Test
    fun absentAndNonFiniteValuesAreLeftOut() {
        val run = TipCalibrationRun(
            profileId = "probe-7",
            started = "2026-10-11 13:52:10",
            positions = listOf(position(ClockPosition.Nine, -11505.9, 0.1)),
        )
        val text = TipCalibrationSessions.encode(TipCalibrationSessions.sessionOf(run))
        assertFalse("\"gauge\"" in text, text)
        assertFalse("\"completed\"" in text, text)
        assertFalse("\"result\"" in text, text)
        assertFalse("\"vy\"" in text, text)
        assertFalse("NaN" in text, text)
        assertTrue("\"vx\":-300.0" in text, text)
        assertTrue("\"complete\":false" in text, text)
        assertTrue(text.startsWith("{\"format\":1,\"profile\":\"probe-7\",\"complete\":false,\"started\""), text)
    }

    @Test
    fun aTextThatIsNotASessionOfFormatOneIsNotRead() {
        assertNull(TipCalibrationSessions.decode(null))
        assertNull(TipCalibrationSessions.decode(""))
        assertNull(TipCalibrationSessions.decode("not json"))
        assertNull(TipCalibrationSessions.decode(knownText.replace("\"format\":1", "\"format\":2")))
        assertNull(TipCalibrationSessions.decode(knownText.replace("NINE", "TEN")))
    }

    @Test
    fun applyWritesTheResultIntoTheProfile() {
        val run = completeRun()
        val result = assertIs<TipCalibrationOutcome.Ready>(run.evaluate(2000.0)).result
        val calibratedOn = Instant.parse("2026-10-11T13:58:02Z")
        val profile = ProbeProfile(id = "probe-7", stylusDiameterUm = 2000.0, calibrationResidualUm = null)
        val applied = TipCalibrationSessions.apply(profile, run, result, "2026-10-11 13:58:02", calibratedOn)
        assertEquals(result.effectiveDiameterUm, applied.stylusDiameterUm)
        assertEquals(result.effectiveDiameterUm, applied.effectiveDiameterUm)
        assertEquals(result.repeatabilityUm, applied.calibrationResidualUm)
        assertEquals(calibratedOn, applied.calibratedOn)
        val text = assertNotNull(applied.calibrationSession)
        assertEquals(Sha256.hexOfUtf8(text), applied.sessionDigest)
        assertEquals(64, applied.sessionDigest?.length)
        val session = assertNotNull(TipCalibrationSessions.decode(text))
        assertTrue(session.complete)
        assertEquals("2026-10-11 13:58:02", session.completed)
        assertEquals(4, session.positions.size)
        assertEquals(listOf(ClockPosition.Nine, ClockPosition.Three, ClockPosition.Twelve, ClockPosition.Six), session.positions.map { it.clock })
        assertEquals(result.effectiveDiameterUm, session.result?.effective)
        assertEquals(result.repeatabilityUm, session.result?.repeatability)
        assertEquals(result.residualUm, session.result?.residual)
    }

    @Test
    fun aSaveDuringTheRunKeepsAnAppliedCalibrationUntilTheFirstPosition() {
        val run = completeRun()
        val result = assertIs<TipCalibrationOutcome.Ready>(run.evaluate(2000.0)).result
        val applied = TipCalibrationSessions.apply(
            ProbeProfile(id = "probe-7"), run, result, "2026-10-11 13:58:02", Instant.parse("2026-10-11T13:58:02Z"),
        )
        // A new run with no position does not replace the complete session.
        val newRun = TipCalibrationRun(profileId = "probe-7", started = "2026-10-12 08:00:00", gaugeUm = 25000.0)
        assertEquals(applied, TipCalibrationSessions.withRunSession(applied, newRun))
        // The first position of the new run replaces it.
        val withPosition = newRun.withPosition(position(ClockPosition.Nine, -11505.9, 0.1))
        val saved = TipCalibrationSessions.withRunSession(applied, withPosition)
        val session = assertNotNull(TipCalibrationSessions.decode(saved.calibrationSession))
        assertFalse(session.complete)
        assertEquals(1, session.positions.size)
        assertEquals(Sha256.hexOfUtf8(saved.calibrationSession!!), saved.sessionDigest)
        // The calibration values of the profile do not change during the run.
        assertEquals(applied.effectiveDiameterUm, saved.effectiveDiameterUm)
        // An incomplete stored session, no stored session, or an unreadable one can be replaced.
        val empty = TipCalibrationSessions.sessionOf(newRun)
        assertTrue(TipCalibrationSessions.canReplaceStoredSession(empty, saved.calibrationSession))
        assertTrue(TipCalibrationSessions.canReplaceStoredSession(empty, null))
        assertTrue(TipCalibrationSessions.canReplaceStoredSession(empty, "garbage"))
        assertFalse(TipCalibrationSessions.canReplaceStoredSession(empty, applied.calibrationSession))
    }

    @Test
    fun anIncompleteSessionResumesAndABadSessionIsInterrupted() {
        val run = completeRun().let { value -> value.copy(positions = value.positions.take(2)) }
        val text = TipCalibrationSessions.encode(TipCalibrationSessions.sessionOf(run))
        val resumed = assertIs<ResumeResult.Resumed>(TipCalibrationSessions.resume(text)).run
        assertEquals(run.profileId, resumed.profileId)
        assertEquals(run.started, resumed.started)
        assertEquals(run.gaugeUm, resumed.gaugeUm)
        assertEquals(ClockPosition.Twelve, resumed.nextPosition)
        assertEquals(run.positions.map { it.xUm }, resumed.positions.map { it.xUm })
        assertEquals(3, resumed.positions.first().touches.size)
        assertTrue(resumed.positions.first().touches.first().vyUmPerS.isNaN())
        assertEquals(ResumeResult.Interrupted, TipCalibrationSessions.resume("{\"format\":1,\"positions\":[{\"clock\":\"NINE\"}]}"))
        assertEquals(ResumeResult.Interrupted, TipCalibrationSessions.resume("garbage"))
        assertEquals(ResumeResult.Interrupted, TipCalibrationSessions.resume(null))
        assertEquals(ResumeResult.AlreadyComplete, TipCalibrationSessions.resume(knownText))
    }

    @Test
    fun theCalibrationRecordShowsTheStoredSession() {
        assertEquals(CalibrationRecord.NoCalibration, CalibrationRecords.of(ProbeProfile()))
        assertEquals(CalibrationRecord.Unreadable, CalibrationRecords.of(ProbeProfile(calibrationSession = "garbage")))
        val incompleteText = knownText.replace("\"complete\":true", "\"complete\":false")
        val incomplete = assertIs<CalibrationRecord.Incomplete>(CalibrationRecords.of(ProbeProfile(calibrationSession = incompleteText)))
        assertEquals(listOf(RecordPositionLine(ClockPosition.Nine, 1, 0.7)), incomplete.positions)
        val complete = assertIs<CalibrationRecord.Complete>(
            CalibrationRecords.of(ProbeProfile(calibrationSession = knownText, calibrationResidualUm = 1.1)),
        )
        assertEquals(1987.4, complete.tipDiameterUm)
        assertEquals(1.1, complete.repeatabilityUm)
        assertEquals(25000.0, complete.gaugeUm)
        assertEquals("2026-10-11 13:58:02", complete.completed)
        assertEquals(1987.0, complete.diameterXUm)
        assertEquals(1987.8, complete.diameterYUm)
        assertEquals(listOf(RecordPositionLine(ClockPosition.Nine, 1, 0.7)), complete.positions)
        // The profile has a spread limit (20 µm by default), so the limit does not come from the run.
        assertNull(complete.spreadLimitFromRunUm)
    }

    @Test
    fun theRecordTellsWhenTheSpreadLimitComesFromTheRun() {
        val record = assertIs<CalibrationRecord.Complete>(
            CalibrationRecords.of(ProbeProfile(spreadLimitUm = null, calibrationResidualUm = 5.0, calibrationSession = knownText)),
        )
        assertEquals(15.0, record.spreadLimitFromRunUm)
    }
}
