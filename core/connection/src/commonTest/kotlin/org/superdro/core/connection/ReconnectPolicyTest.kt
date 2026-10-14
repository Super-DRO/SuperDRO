package org.superdro.core.connection

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reconnect policy. */
class ReconnectPolicyTest {

    @Test
    fun theDelaysAre1_2_4_8_16ThenThirtySeconds() {
        val policy = ReconnectPolicy(FixedRandom(0.5))
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (1..8).map { failures -> policy.baseDelayMs(failures) },
        )
        assertEquals((1..8).map { policy.baseDelayMs(it) }, (1..8).map { policy.delayMs(it) })
    }

    @Test
    fun eachDelayHasAVariationOfTwentyPercent() {
        assertEquals(800L, ReconnectPolicy(FixedRandom(0.0)).delayMs(1))
        assertEquals(24_000L, ReconnectPolicy(FixedRandom(0.0)).delayMs(6))
        assertEquals(1_200L, ReconnectPolicy(FixedRandom(1.0)).delayMs(1))
        assertEquals(36_000L, ReconnectPolicy(FixedRandom(1.0)).delayMs(9))
        assertEquals(800L..1_200L, ReconnectPolicy().delayRangeMs(1))
        assertEquals(12_800L..19_200L, ReconnectPolicy().delayRangeMs(5))
        val policy = ReconnectPolicy(Random(42))
        repeat(1_000) { index ->
            val failures = index % 8 + 1
            val delay = policy.delayMs(failures)
            assertTrue(delay in policy.delayRangeMs(failures), "$delay is not in ${policy.delayRangeMs(failures)}")
        }
    }

    @Test
    fun tenSecondsInConnectedStartTheDelaysAtOneSecondAgain() {
        val policy = ReconnectPolicy(FixedRandom(0.5))
        assertEquals(5, policy.failuresAfterConnected(5, 9_999L))
        assertEquals(0, policy.failuresAfterConnected(5, 10_000L))
        assertEquals(ReconnectDecision.Retry(failures = 1, attempt = 2, delayMs = 1_000L), policy.decide(LinkFailure.Lost, 0))
    }

    @Test
    fun thePolicyHasNoLimit() {
        val policy = ReconnectPolicy(FixedRandom(0.5))
        var failures = 0
        repeat(500) {
            val decision = policy.decide(LinkFailure.Refused, failures) as ReconnectDecision.Retry
            failures = decision.failures
            assertEquals(failures + 1, decision.attempt)
        }
        assertEquals(500, failures)
        assertEquals(30_000L, policy.delayMs(failures))
    }

    @Test
    fun aCapabilityFailureWaitsForTheDevice() {
        val policy = ReconnectPolicy(FixedRandom(0.5))
        listOf(LinkFailure.RadioOff, LinkFailure.PermissionMissing, LinkFailure.DeviceGone, LinkFailure.NetworkOff).forEach { failure ->
            assertEquals(ReconnectDecision.WaitForCapability(failure), policy.decide(failure, 3))
        }
        assertTrue(policy.decide(LinkFailure.Refused, 0) is ReconnectDecision.Retry)
        assertTrue(policy.decide(LinkFailure.NoAnswer, 0) is ReconnectDecision.Retry)
        assertFalse(LinkFailure.NoAnswer.waitsForCapability)
        assertTrue(policy.decide(LinkFailure.Lost, 0) is ReconnectDecision.Retry)
    }
}
