package org.superdro.core.store.repository

import org.superdro.core.model.MachineType

/**
 * A kind of stored data that does not fit a new machine type.
 */
enum class MismatchKind {
    /** Tools of the profile library with the other tool kind: lathe tools on a mill, mill tools on a lathe. */
    ProfileTools,

    /** Sub-datums with a Y coordinate on a lathe. A point with only Y goes to the archive in full. */
    SubDatumYCoordinates,

    /** Guides on the Y axis on a lathe. */
    YGuides,

    /** Machine datums with a Y coordinate on a lathe. A datum with only Y goes to the archive in full. */
    MachineDatumYCoordinates,

    /** Lathe threads on a mill. */
    LatheThreads,
}

/** One kind of data that does not fit, and the number of items. */
data class Mismatch(val kind: MismatchKind, val count: Int)

/** A change that SuperDRO makes to the data so that it fits the new machine type. */
enum class ConversionKind {
    /** A mill and a horizontal mill share the mill tools: the tools stay. */
    SharedMillTools,

    /** The plane of each workspace: `ZX` on a lathe, `XY` on a mill. */
    WorkspacePlane,

    /** The X diameter mode of each workspace: on for a lathe, off for a mill. */
    DiameterMode,

    /** The measurement mode is not available on a lathe: the mode changes to cutting. */
    MachineMode,

    /** A lathe uses the pattern angle convention `Legacy`, a new mill `Standard`. */
    PatternAngleConvention,

    /** Archived data that fits the new type comes back from the archive. */
    RestoreFromArchive,
}

/**
 * The confirmation of a machine type change: the list of the data that does not fit the
 * new type, and the conversions. An empty [mismatches] list means that all data fits.
 */
data class MachineTypeChangePlan(
    val profileId: String,
    val from: MachineType,
    val to: MachineType,
    val mismatches: List<Mismatch>,
    val conversions: List<ConversionKind>,
)

/** What the store does with the data that does not fit the new machine type. */
enum class MismatchDisposal {
    /** Keep the data in the database and hide it. A later change back puts it back. */
    Archive,

    /** Delete the data. */
    Delete,
}

/**
 * One item of the archive of a profile. The archive keeps the data in the database, and
 * the profile does not show it.
 *
 * @property kind The kind of the item: `Tool`, `SubDatum`, `SubDatumY`, `Guide`, `MachineDatum`,
 *   `MachineDatumY`, or `ThreadSpec`. A `...Y` item keeps the full item before the store removed
 *   its Y coordinate.
 * @property machineType The machine type of the profile when the store archived the item.
 * @property itemJson The typed item as JSON text.
 */
data class ArchivedItem(
    val id: String,
    val profileId: String,
    val workspaceId: String?,
    val kind: String,
    val itemId: String,
    val machineType: MachineType,
    val itemJson: String,
)
