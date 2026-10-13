package org.superdro.core.model

import kotlinx.serialization.Serializable

/**
 * The stored settings of the DXF import limits. `core:drawing` makes its `DxfLimits` from this
 * record.
 *
 * @property curveToleranceNm The chord error of the ellipse and spline approximation: 1000 nm (1 µm).
 * @property polylineWarningPoints A polyline with more points gives a warning.
 * @property maxSplineControlPoints A spline with more control points or fit points goes away.
 */
@Serializable
data class DxfImportLimits(
    val maxSourceBytes: Long = 2_000_000L,
    val maxEntities: Int = 10_000,
    val maxBezierSegments: Int = 10_000,
    val maxBlockLevels: Int = 8,
    val maxParseTimeMs: Long = 5000L,
    val curveToleranceNm: Long = 1000L,
    val polylineWarningPoints: Int = 2000,
    val maxSplineControlPoints: Int = 1000,
)
