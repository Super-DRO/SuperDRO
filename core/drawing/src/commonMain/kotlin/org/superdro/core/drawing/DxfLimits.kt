package org.superdro.core.drawing

import org.superdro.core.model.DxfImportLimits

/**
 * The limits of the DXF import. Each limit is a setting in SuperDRO.
 */
data class DxfLimits(
    val maxSourceBytes: Long = 2_000_000L,
    val maxEntities: Int = 10_000,
    val maxBezierSegments: Int = 10_000,
    val maxBlockLevels: Int = 8,
    val maxParseTimeMs: Long = 5000L,
    /** The curve tolerance: 1 µm. */
    val curveToleranceNm: Long = 1000L,
    /** A polyline with more points gives a warning. */
    val polylineWarningPoints: Int = 2000,
    /** A spline with more control points goes away. */
    val maxSplineControlPoints: Int = 1000,
    /** A polyline with more points stops the import. */
    val maxPolylinePoints: Int = 10_000,
    /** An ellipse that needs more reference points goes away. */
    val maxEllipseReferencePoints: Int = 20_000,
) {
    /** Gives the stored settings of these limits. */
    fun toSettings(): DxfImportLimits = DxfImportLimits(
        maxSourceBytes = maxSourceBytes,
        maxEntities = maxEntities,
        maxBezierSegments = maxBezierSegments,
        maxBlockLevels = maxBlockLevels,
        maxParseTimeMs = maxParseTimeMs,
        curveToleranceNm = curveToleranceNm,
        polylineWarningPoints = polylineWarningPoints,
        maxSplineControlPoints = maxSplineControlPoints,
    )

    companion object {
        /** Gives the limits of the stored [settings]. The wireframe limits keep their defaults. */
        fun from(settings: DxfImportLimits): DxfLimits = DxfLimits(
            maxSourceBytes = settings.maxSourceBytes,
            maxEntities = settings.maxEntities,
            maxBezierSegments = settings.maxBezierSegments,
            maxBlockLevels = settings.maxBlockLevels,
            maxParseTimeMs = settings.maxParseTimeMs,
            curveToleranceNm = settings.curveToleranceNm,
            polylineWarningPoints = settings.polylineWarningPoints,
            maxSplineControlPoints = settings.maxSplineControlPoints,
        )

        /**
         * The 22-byte sentinel at the start of a binary DXF file: `AutoCAD Binary DXF`, CR, LF,
         * 0x1A, and 0x00.
         */
        val BINARY_SENTINEL: ByteArray =
            "AutoCAD Binary DXF\r\n".encodeToByteArray() + byteArrayOf(0x1A, 0x00)

        /** True when [bytes] start with [BINARY_SENTINEL]. */
        fun isBinaryDxf(bytes: ByteArray): Boolean =
            bytes.size >= BINARY_SENTINEL.size && BINARY_SENTINEL.indices.all { index -> bytes[index] == BINARY_SENTINEL[index] }
    }
}
