package org.superdro.core.connection

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The reconnect policy of a session. The class
 * is pure: it has no clock and no coroutine. The session gives it the failures and the
 * times. Tests give a fixed [random] source.
 *
 * Rules:
 * - The delays are 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s for each attempt.
 * - Each delay has a random variation of plus or minus 20 %.
 * - After the link stays `Connected` for 10 s, the next failure starts at 1 s again.
 * - There is no limit while the machine is remembered and not paused.
 * - [LinkFailure.RadioOff], [LinkFailure.PermissionMissing], [LinkFailure.DeviceGone], and
 *   [LinkFailure.NetworkOff] go to `WaitingForDevice`. The session starts again at once when the
 *   [CapabilitySource] reports the capability.
 *
 * The count of failures sets the delay: a session starts with 0 failures, and each failure adds 1.
 * The session numbers the attempts itself:
 * `Reconnecting` holds the number of the attempt that failed, and the next state is
 * `Connecting(attempt + 1)`.
 *
 * @param random The random source of the variation.
 * @param baseDelaysMs The delays without the variation. The last value repeats.
 * @param variation The variation, as a fraction of the delay.
 * @param resetAfterConnectedMs The time in `Connected` that sets the count to 0.
 */
class ReconnectPolicy(
    private val random: Random = Random.Default,
    val baseDelaysMs: List<Long> = DEFAULT_DELAYS_MS,
    val variation: Double = DEFAULT_VARIATION,
    val resetAfterConnectedMs: Long = RESET_AFTER_CONNECTED_MS,
) {

    init {
        require(baseDelaysMs.isNotEmpty()) { "The policy needs one or more delays." }
        require(variation in 0.0..1.0) { "The variation must be in 0 to 1, but it is $variation." }
    }

    /**
     * Gives the delay before the next attempt after [failures] failures, without the variation.
     * 1 failure gives 1000 ms, 5 failures give 16000 ms, and 6 or more give 30000 ms.
     */
    fun baseDelayMs(failures: Int): Long {
        require(failures >= 1) { "The count of failures must be 1 or more, but it is $failures." }
        return baseDelaysMs[minOf(failures, baseDelaysMs.size) - 1]
    }

    /** Gives the range of the delay after [failures] failures: the base delay ± 20 %. */
    fun delayRangeMs(failures: Int): LongRange {
        val base = baseDelayMs(failures).toDouble()
        return (base * (1.0 - variation)).roundToLong()..(base * (1.0 + variation)).roundToLong()
    }

    /** Gives the delay after [failures] failures with a random variation in [delayRangeMs]. */
    fun delayMs(failures: Int): Long {
        val base = baseDelayMs(failures).toDouble()
        val factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * variation
        return (base * factor).roundToLong()
    }

    /**
     * Gives the decision after [failure] when the session had [failures] failures before it.
     * A capability failure gives [ReconnectDecision.WaitForCapability]. Each other failure gives
     * [ReconnectDecision.Retry] with the new count and its delay.
     */
    fun decide(failure: LinkFailure, failures: Int): ReconnectDecision =
        if (failure.waitsForCapability) {
            ReconnectDecision.WaitForCapability(failure)
        } else {
            val count = failures + 1
            ReconnectDecision.Retry(failures = count, attempt = count + 1, delayMs = delayMs(count))
        }

    /**
     * Gives the count of failures at the end of a `Connected` period of [connectedForMs]: 0 after
     * 10 s or more, else [failures].
     */
    fun failuresAfterConnected(failures: Int, connectedForMs: Long): Int =
        if (connectedForMs >= resetAfterConnectedMs) 0 else failures

    companion object {
        /** 1, 2, 4, 8, 16, then 30 s. */
        val DEFAULT_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        /** Plus or minus 20 %. */
        const val DEFAULT_VARIATION: Double = 0.2

        /** 10 s in `Connected` start the delays at 1 s again. */
        const val RESET_AFTER_CONNECTED_MS: Long = 10_000L
    }
}

/** A decision of the [ReconnectPolicy]. */
sealed interface ReconnectDecision {

    /**
     * Wait [delayMs], then open again.
     *
     * @property failures The new count of failures.
     * @property attempt [failures] + 1: the number of the next attempt when each attempt of the
     *   series failed. The session uses its own attempt numbers.
     */
    data class Retry(val failures: Int, val attempt: Int, val delayMs: Long) : ReconnectDecision

    /** Go to `WaitingForDevice` and wait for the capability event, not for a timer. */
    data class WaitForCapability(val cause: LinkFailure) : ReconnectDecision
}
