package org.superdro.core.model

/**
 * The internal units of SuperDRO.
 *
 * - Lengths are micrometers (µm) as `Double`.
 * - Angles are arc seconds (″) as `Double`.
 * - Counts are `Long`.
 * - Drawing coordinates are integer nanometers (`Long`).
 * - Times are milliseconds.
 *
 * Never use `Float` for a position.
 */
object Units {
    /** 1 mm = 1000 µm. */
    const val UM_PER_MM: Double = 1000.0

    /** 1 in = 25400 µm. */
    const val UM_PER_INCH: Double = 25400.0

    /** 1 µm = 1000 nm. */
    const val NM_PER_UM: Long = 1000L

    /** 1° = 3600″. */
    const val ARCSEC_PER_DEGREE: Double = 3600.0

    /** 1 revolution = 1296000″ (360 × 3600). */
    const val ARCSEC_PER_REVOLUTION: Double = 1_296_000.0
}
