package org.superdro.core.dataoutput

import kotlin.math.PI
import kotlin.math.abs
import org.superdro.core.model.DataOutputFieldId

/**
 * The values of one record: the 25 fields, in internal units. The session code fills this
 * type from the readout model of the display, so that the output and the display show the same
 * values. A null value is a field with no value: the record then has `null` (JSON) or an
 * empty text (Delimited).
 *
 * Units:
 * - Positions (`*Absolute`, `*Incremental`, `probe1Trigger{X,Y,Z}`): µm. Angles: arc seconds.
 * - Feed rates (`*FeedRate`, [probe1TriggerFeed]): µm per minute. [angularFeedRate]: arc seconds
 *   per minute. The readout model gives 0 at rest (the 2000 ms window).
 * - [spindleRpm]: revolutions per minute. [surfaceSpeed]: µm per minute (see [surfaceSpeedUmPerMinute]).
 * - [probe1], [probe2]: 1.0 when the probe input is on, else 0.0. [probe2] is the second probe
 *   port.
 * - `probe1TriggerDir{X,Y,Z}`: −1.0, 0.0, or 1.0. [probe1TriggerTime]: ms since 1970 UTC.
 *   [probe1TriggerSeq]: the number of triggers since the start of the output.
 *
 * @property updatedAtEpochMs The wall clock time of the last value update, in ms since 1970 UTC
 *   (`OutputTime.wallClockTime`). It is the time stamp of the record, not the send time. Null
 *   before the first update: the record then has the current time.
 */
data class OutputValues(
    val xAbsolute: Double? = null,
    val xIncremental: Double? = null,
    val xFeedRate: Double? = null,
    val yAbsolute: Double? = null,
    val yIncremental: Double? = null,
    val yFeedRate: Double? = null,
    val zAbsolute: Double? = null,
    val zIncremental: Double? = null,
    val zFeedRate: Double? = null,
    val angularAbsolute: Double? = null,
    val angularIncremental: Double? = null,
    val angularFeedRate: Double? = null,
    val spindleRpm: Double? = null,
    val surfaceSpeed: Double? = null,
    val probe1: Double? = null,
    val probe2: Double? = null,
    val probe1TriggerX: Double? = null,
    val probe1TriggerY: Double? = null,
    val probe1TriggerZ: Double? = null,
    val probe1TriggerDirX: Double? = null,
    val probe1TriggerDirY: Double? = null,
    val probe1TriggerDirZ: Double? = null,
    val probe1TriggerTime: Double? = null,
    val probe1TriggerSeq: Double? = null,
    val probe1TriggerFeed: Double? = null,
    val updatedAtEpochMs: Long? = null,
) {

    /** Gives the value of the field [id] in internal units, or null. */
    operator fun get(id: DataOutputFieldId): Double? = when (id) {
        DataOutputFieldId.XAbsolute -> xAbsolute
        DataOutputFieldId.XIncremental -> xIncremental
        DataOutputFieldId.XFeedRate -> xFeedRate
        DataOutputFieldId.YAbsolute -> yAbsolute
        DataOutputFieldId.YIncremental -> yIncremental
        DataOutputFieldId.YFeedRate -> yFeedRate
        DataOutputFieldId.ZAbsolute -> zAbsolute
        DataOutputFieldId.ZIncremental -> zIncremental
        DataOutputFieldId.ZFeedRate -> zFeedRate
        DataOutputFieldId.AngularAbsolute -> angularAbsolute
        DataOutputFieldId.AngularIncremental -> angularIncremental
        DataOutputFieldId.AngularFeedRate -> angularFeedRate
        DataOutputFieldId.SpindleRpm -> spindleRpm
        DataOutputFieldId.SurfaceSpeed -> surfaceSpeed
        DataOutputFieldId.Probe1 -> probe1
        DataOutputFieldId.Probe2 -> probe2
        DataOutputFieldId.Probe1TriggerX -> probe1TriggerX
        DataOutputFieldId.Probe1TriggerY -> probe1TriggerY
        DataOutputFieldId.Probe1TriggerZ -> probe1TriggerZ
        DataOutputFieldId.Probe1TriggerDirX -> probe1TriggerDirX
        DataOutputFieldId.Probe1TriggerDirY -> probe1TriggerDirY
        DataOutputFieldId.Probe1TriggerDirZ -> probe1TriggerDirZ
        DataOutputFieldId.Probe1TriggerTime -> probe1TriggerTime
        DataOutputFieldId.Probe1TriggerSeq -> probe1TriggerSeq
        DataOutputFieldId.Probe1TriggerFeed -> probe1TriggerFeed
    }

    companion object {

        /** Makes the values from a map. A field that the map does not have gets null. */
        fun of(values: Map<DataOutputFieldId, Double?>, updatedAtEpochMs: Long? = null): OutputValues = OutputValues(
            xAbsolute = values[DataOutputFieldId.XAbsolute],
            xIncremental = values[DataOutputFieldId.XIncremental],
            xFeedRate = values[DataOutputFieldId.XFeedRate],
            yAbsolute = values[DataOutputFieldId.YAbsolute],
            yIncremental = values[DataOutputFieldId.YIncremental],
            yFeedRate = values[DataOutputFieldId.YFeedRate],
            zAbsolute = values[DataOutputFieldId.ZAbsolute],
            zIncremental = values[DataOutputFieldId.ZIncremental],
            zFeedRate = values[DataOutputFieldId.ZFeedRate],
            angularAbsolute = values[DataOutputFieldId.AngularAbsolute],
            angularIncremental = values[DataOutputFieldId.AngularIncremental],
            angularFeedRate = values[DataOutputFieldId.AngularFeedRate],
            spindleRpm = values[DataOutputFieldId.SpindleRpm],
            surfaceSpeed = values[DataOutputFieldId.SurfaceSpeed],
            probe1 = values[DataOutputFieldId.Probe1],
            probe2 = values[DataOutputFieldId.Probe2],
            probe1TriggerX = values[DataOutputFieldId.Probe1TriggerX],
            probe1TriggerY = values[DataOutputFieldId.Probe1TriggerY],
            probe1TriggerZ = values[DataOutputFieldId.Probe1TriggerZ],
            probe1TriggerDirX = values[DataOutputFieldId.Probe1TriggerDirX],
            probe1TriggerDirY = values[DataOutputFieldId.Probe1TriggerDirY],
            probe1TriggerDirZ = values[DataOutputFieldId.Probe1TriggerDirZ],
            probe1TriggerTime = values[DataOutputFieldId.Probe1TriggerTime],
            probe1TriggerSeq = values[DataOutputFieldId.Probe1TriggerSeq],
            probe1TriggerFeed = values[DataOutputFieldId.Probe1TriggerFeed],
            updatedAtEpochMs = updatedAtEpochMs,
        )

        /**
         * Gives the surface speed `|rpm × D × π|` in µm per minute. On a mill, [diameterUm] is the
         * tool diameter. On a lathe, use
         * [latheSurfaceSpeedUmPerMinute].
         */
        fun surfaceSpeedUmPerMinute(rpm: Double, diameterUm: Double): Double = abs(rpm * diameterUm * PI)

        /** Gives the surface speed of a lathe: the diameter is 2 × |X| ([xUm] in µm, as a radius). */
        fun latheSurfaceSpeedUmPerMinute(rpm: Double, xUm: Double): Double =
            surfaceSpeedUmPerMinute(rpm, 2.0 * abs(xUm))
    }
}
