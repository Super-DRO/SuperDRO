package org.superdro.core.drawing

import kotlin.time.Instant

/** The test files of `src/jvmTest/resources`. `fixtures/make_fixtures.py` makes them with ezdxf. */
internal object Fixtures {
    /** The fixed time of `createdAt` in the tests. */
    val NOW: Instant = Instant.parse("2026-10-12T08:30:00Z")

    fun bytes(path: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/$path")) { "No test file $path" }.use { stream -> stream.readBytes() }

    fun text(path: String): String = bytes(path).decodeToString()

    /** The names of the drawings that have an ASCII file and a binary twin. */
    val TWINS: List<String> = listOf(
        "entities_r2000_mm",
        "curves_r2000_mm",
        "fit_spline_r2000_mm",
        "blocks_r2000_inch",
        "nesting_8_levels",
        "nesting_9_levels",
        "r12_mm",
        "r2018_utf8_m",
        "codepage_1251_r2000",
        "codepage_932_r2000",
        "spline_1001_points",
    )
}
