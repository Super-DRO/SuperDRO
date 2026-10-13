package org.superdro.core.dataoutput

import org.superdro.core.model.DataOutputFieldId

/** The value kind of a field. It selects the unit conversion and the number of decimals. */
enum class FieldKind {
    /** A length in µm. It uses the distance unit and the precision. */
    Distance,

    /** A length per minute (a feed rate) in µm/min. It uses the distance unit and the precision. */
    DistanceRate,

    /** An angle in arc seconds. It uses the angle unit and the precision. */
    Angle,

    /** An angle per minute in arc seconds/min. It uses the angle unit and the precision. */
    AngleRate,

    /** Revolutions per minute, with 0 decimals. */
    Rpm,

    /** A surface speed in µm/min: ft/min for inch and mil, else m/min. It uses the precision. */
    SurfaceSpeed,

    /** A probe state: `1` or `0`. */
    Flag,

    /** A direction of motion: `-1`, `0`, or `1`. */
    Direction,

    /** A wall clock time in ms since 1970 UTC, as an integer. */
    EpochMs,

    /** A count, as an integer. */
    Count,
}

/** The value kind of each field (column "Unit"). */
val DataOutputFieldId.kind: FieldKind
    get() = when (this) {
        DataOutputFieldId.XAbsolute, DataOutputFieldId.XIncremental,
        DataOutputFieldId.YAbsolute, DataOutputFieldId.YIncremental,
        DataOutputFieldId.ZAbsolute, DataOutputFieldId.ZIncremental,
        DataOutputFieldId.Probe1TriggerX, DataOutputFieldId.Probe1TriggerY, DataOutputFieldId.Probe1TriggerZ,
        -> FieldKind.Distance

        DataOutputFieldId.XFeedRate, DataOutputFieldId.YFeedRate, DataOutputFieldId.ZFeedRate,
        DataOutputFieldId.Probe1TriggerFeed,
        -> FieldKind.DistanceRate

        DataOutputFieldId.AngularAbsolute, DataOutputFieldId.AngularIncremental -> FieldKind.Angle
        DataOutputFieldId.AngularFeedRate -> FieldKind.AngleRate
        DataOutputFieldId.SpindleRpm -> FieldKind.Rpm
        DataOutputFieldId.SurfaceSpeed -> FieldKind.SurfaceSpeed
        DataOutputFieldId.Probe1, DataOutputFieldId.Probe2 -> FieldKind.Flag
        DataOutputFieldId.Probe1TriggerDirX, DataOutputFieldId.Probe1TriggerDirY, DataOutputFieldId.Probe1TriggerDirZ,
        -> FieldKind.Direction

        DataOutputFieldId.Probe1TriggerTime -> FieldKind.EpochMs
        DataOutputFieldId.Probe1TriggerSeq -> FieldKind.Count
    }

/**
 * The key of the field name in the string file `strings_data_output.xml` of `core:resources`,
 * for example `data_output_field_x_absolute`. The settings screen shows this name.
 */
val DataOutputFieldId.nameKey: String
    get() = "data_output_field_$wireId"
