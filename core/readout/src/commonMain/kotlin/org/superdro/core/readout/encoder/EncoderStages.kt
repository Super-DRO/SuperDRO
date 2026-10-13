package org.superdro.core.readout.encoder

import org.superdro.core.model.BacklashMode
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.MapPoint

/**
 * The values of an encoder profile that change the stages of the input. A
 * change of a different field (for example the label or the calibration date) does not start a
 * new filter state.
 */
data class StageConfig(
    val kind: EncoderKind,
    val resolution: Double,
    val type: EncoderType,
    val reversed: Boolean,
    val filter: Boolean,
    val backlashMode: BacklashMode,
    val backlashCounts: Long,
    val errorMap: List<MapPoint>,
    val errorMapCyclic: Boolean,
    val backlashMap: List<MapPoint>,
) {
    companion object {
        /** Gives the stage values of [profile], or null for no profile. */
        fun of(profile: EncoderProfile?): StageConfig? = profile?.let {
            StageConfig(
                kind = it.kind,
                resolution = it.resolution,
                type = it.type,
                reversed = it.reversed,
                filter = it.filter,
                backlashMode = it.backlashMode,
                backlashCounts = it.backlashCounts,
                errorMap = it.errorMap,
                errorMapCyclic = it.errorMapCyclic,
                backlashMap = it.backlashMap,
            )
        }
    }
}

/**
 * The count stages of one input in this order: the spike filter, the error map, and the
 * backlash. The scale with the direction sign comes after these stages.
 *
 * ```text
 * F = filter(raw)                 (raw when the filter is off)
 * E = F + mapCorrection(F)        (0 with no map)
 * C = backlash(E, d)
 * ```
 */
class EncoderStages(profile: EncoderProfile) {

    /** The spike filter, or null when the filter is off. */
    val filter: SpikeFilter? = if (profile.filter) SpikeFilter(SpikeFilter.thresholdFor(profile.resolution)) else null

    /**
     * The error map stage. An angular input with the "cyclic" switch on wraps the position.
     * A map that fails the check of [MapValidation] gives no correction.
     */
    val errorCorrection: ErrorCorrection = run {
        val valid = MapValidation.errorMapProblems(profile.errorMap).isEmpty()
        val table = if (valid) MapTable.of(profile.errorMap) else MapTable.EMPTY
        if (table.isEmpty) {
            ErrorCorrection.NONE
        } else {
            val cyclic = profile.kind == EncoderKind.Angular && profile.errorMapCyclic && profile.resolution > 0.0
            ErrorCorrection(table, if (cyclic) profile.resolution else null)
        }
    }

    /** The backlash stage. A backlash map that fails the check gives no backlash map. */
    val backlash: BacklashStage = BacklashStage.of(
        if (MapValidation.backlashMapProblems(profile.backlashMap, profile.countsPerScrewRev).isEmpty()) {
            profile
        } else {
            profile.copy(backlashMap = emptyList())
        },
    )

    /** True while the filter holds a spike. */
    val pending: Boolean get() = filter?.pending == true

    /** Gives the output count `C` of the raw count [raw] with the raw delta [d]. */
    fun run(raw: Long, d: Long): Long {
        val filtered = filter?.filter(raw) ?: raw
        val corrected = errorCorrection.apply(filtered)
        return backlash.apply(corrected, d)
    }
}
