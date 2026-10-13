package org.superdro.core.readout.input

import org.superdro.core.model.AxisId

/** The state of the value of one adapter input. */
enum class ChannelStatus {
    /** The input gives a value. */
    Ok,

    /** The input has no encoder profile. The readout shows "Error". */
    NoProfile,

    /** The resolution of the encoder profile is not more than 0. The readout shows "Error". */
    InvalidResolution,

    /** The kind of the encoder profile is "disabled". The axis shows "No input". */
    Disabled,
}

/**
 * The latest value of one adapter input.
 *
 * @property rawCount The count from the adapter. The calibration wizard and the raw view of
 *   the Adapter Details use it.
 * @property correctedCount The count after the filter, the error map, and the backlash, or null.
 * @property value `correctedCount × k`: µm, arc seconds, or revolutions (RPM for `t`), or null
 *   ("no value").
 * @property timeMs The time of the sample (session clock).
 */
data class ChannelValue(
    val input: Char,
    val rawCount: Long,
    val correctedCount: Long?,
    val value: Double?,
    val timeMs: Long,
    val status: ChannelStatus,
)

/** The state of the sum of an axis. */
enum class AxisValueStatus {
    /** The sum has a value. */
    Ok,

    /** An input of the axis sent no data yet. */
    NoData,

    /** An input of the axis is disabled: the axis shows "No input". */
    NoInput,

    /** An input of the axis has no profile or no valid resolution: the axis shows "Error". */
    Error,
}

/**
 * The sum `R` of one axis.
 *
 * @property value `R` in µm, arc seconds, or RPM, or null when [status] is not [AxisValueStatus.Ok].
 * @property timeMs The latest time of the terms, or null.
 * @property projected True when a projection changes the axis (the warning state).
 */
data class AxisValue(
    val axis: AxisId,
    val value: Double?,
    val timeMs: Long?,
    val status: AxisValueStatus,
    val projected: Boolean = false,
)

/**
 * The output of the input stage for the UI: the latest processed value,
 * the raw count, and the time of each channel, the sum of each axis, and the probe states.
 *
 * @property probes The state of each probe port `p` and `q`: true while active.
 * @property probeSequence The sequence number of the last accepted probe activation, 0 before
 *   the first one.
 */
data class InputSnapshot(
    val channels: Map<Char, ChannelValue> = emptyMap(),
    val axes: Map<AxisId, AxisValue> = emptyMap(),
    val probes: Map<Char, Boolean> = emptyMap(),
    val probeSequence: Long = 0L,
) {
    /** Gives the sum `R` of [axis], or null when it has no value. */
    fun axisValue(axis: AxisId): Double? = axes[axis]?.value

    /** Gives the sum `R` of each axis, with null for no value. */
    fun axisValues(): Map<AxisId, Double?> = axes.mapValues { (_, value) -> value.value }

    companion object {
        /** The snapshot before the first sample. */
        val EMPTY: InputSnapshot = InputSnapshot()
    }
}

/**
 * One accepted probe activation. The input stage latches the
 * position of each enabled axis at the time stamp of the probe message. The result
 * depends only on the message times.
 *
 * @property sequence The number of the activation. Both ports use one counter.
 * @property port The probe port `p` or `q`.
 * @property timeMs The time stamp `t` of the probe message.
 * @property inputPositions The value of each latched input at `t` (`count × k`), without offsets
 *   and without the axis sum. NaN only when the input has no sample.
 * @property axisPositions The sum `R` of each enabled axis at `t`, without offsets. NaN only when
 *   an input of the axis has no sample.
 * @property incompleteAxes The axes that are "not complete": an input with no sample, or a gap of
 *   more than 500 ms with a count change. In the second case the position is the last sample at
 *   or before `t`.
 * @property axisVelocities The speed of each axis over the 1000 ms before `t`, in units for each
 *   second: `(pos(t) − pos(t − 1000)) / 1000 × 1000`. NaN when not known.
 */
data class ProbeEvent(
    val sequence: Long,
    val port: Char,
    val timeMs: Long,
    val inputPositions: Map<Char, Double>,
    val axisPositions: Map<AxisId, Double>,
    val incompleteAxes: Set<AxisId>,
    val axisVelocities: Map<AxisId, Double>,
) {
    /** True when each latched axis has a position at `t` ("complete" flag). */
    val complete: Boolean get() = incompleteAxes.isEmpty()
}
