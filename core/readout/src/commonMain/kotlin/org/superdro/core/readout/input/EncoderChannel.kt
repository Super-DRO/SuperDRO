package org.superdro.core.readout.input

import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.readout.encoder.EncoderStages
import org.superdro.core.readout.encoder.ScaleFactor
import org.superdro.core.readout.encoder.StageConfig

/**
 * The encoder profile pipeline of one adapter input (`../error- compensation.md`, 6.2).
 *
 * ```text
 * on sample(raw, t):
 *   if t < lastTime: drop the sample
 *   if no value (no profile, disabled, resolution ≤ 0): prevRaw = raw; value = none
 *   else:
 *     d = prevRaw − raw; prevRaw = raw          (d = 0 for the first sample after a connection)
 *     if d != 0 or the configuration changed or the filter holds a spike:
 *         C = backlash(map(filter(raw)), d); value = C × k
 *     store (t, C) in the input history
 * ```
 *
 * Each connection starts all stages again ([restart]). A configuration change starts a new
 * filter state for this input only ([reconfigure]).
 */
class EncoderChannel internal constructor(
    val input: Char,
    profile: EncoderProfile?,
    private val history: ChannelHistory,
) {

    /** The encoder profile of the input, or null. */
    var profile: EncoderProfile? = profile
        private set

    private var config: StageConfig? = StageConfig.of(profile)
    private var stages: EncoderStages? = null

    /** The scale factor `k` with the direction sign, or null for "no value". */
    var scale: Double? = ScaleFactor.of(input, profile)
        private set

    private var lastTime: Long? = null
    private var previousRaw = 0L
    private var restartPending = true
    private var force = true

    /** The last raw count, or null before the first sample. */
    var rawCount: Long? = null
        private set

    /** The last corrected count `C`, or null. */
    var correctedCount: Long? = null
        private set

    /** The last value `C × k`, or null. */
    var value: Double? = null
        private set

    /** The time of the last sample, or null. */
    var timeMs: Long? = null
        private set

    init {
        buildStages()
    }

    /** The state of the value. */
    val status: ChannelStatus
        get() {
            val current = profile
            return when {
                current == null -> ChannelStatus.NoProfile
                current.kind == EncoderKind.Disabled -> ChannelStatus.Disabled
                current.resolution <= 0.0 -> ChannelStatus.InvalidResolution
                else -> ChannelStatus.Ok
            }
        }

    /**
     * Processes the raw count [raw] at the time [timeMs]. Gives false when the sample is dropped:
     * its time is earlier than the time of the last sample.
     */
    fun process(raw: Long, timeMs: Long): Boolean {
        val last = lastTime
        if (last != null && timeMs < last) return false
        lastTime = timeMs
        rawCount = raw
        this.timeMs = timeMs
        val currentStages = stages
        val k = scale
        if (currentStages == null || k == null) {
            previousRaw = raw
            restartPending = false
            correctedCount = null
            value = null
            return true
        }
        if (restartPending) {
            previousRaw = raw
            restartPending = false
        }
        val d = previousRaw - raw
        previousRaw = raw
        if (d != 0L || force || currentStages.pending || correctedCount == null) {
            force = false
            val corrected = currentStages.run(raw, d)
            correctedCount = corrected
            value = corrected * k
        }
        correctedCount?.let { corrected -> history.add(timeMs, corrected) }
        return true
    }

    /**
     * Starts all stages again at the start of a connection. The first count after the
     * connection has `d` = 0. The history is cleared. The last value stays until the next sample.
     */
    fun restart() {
        buildStages()
        history.clear()
        lastTime = null
        restartPending = true
        force = true
    }

    /**
     * Uses [newProfile]. When the stage values changed, the channel makes new stages, clears its
     * history, and sends the last raw count through all stages again at once, so that the change
     * takes effect at once. Gives true when the stage values changed.
     */
    fun reconfigure(newProfile: EncoderProfile?): Boolean {
        val newConfig = StageConfig.of(newProfile)
        profile = newProfile
        if (newConfig == config) return false
        config = newConfig
        scale = ScaleFactor.of(input, newProfile)
        buildStages()
        history.clear()
        force = true
        val raw = rawCount
        val time = lastTime
        if (raw != null && time != null) {
            correctedCount = null
            process(raw, time)
        } else {
            correctedCount = null
            value = null
        }
        return true
    }

    /** Gives the latest value as a [ChannelValue], or null before the first sample. */
    fun snapshot(): ChannelValue? {
        val raw = rawCount ?: return null
        val time = timeMs ?: return null
        return ChannelValue(input, raw, correctedCount, value, time, status)
    }

    private fun buildStages() {
        val current = profile
        stages = if (current != null && EncoderProfile.givesValue(current)) EncoderStages(current) else null
    }
}
