package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * One result of a measurement. A length is µm, an angle is
 * degrees. A position has [x] and [y] in µm and no [value].
 *
 * @property key The wire name, for example `diameter`, `center`, or `distance`.
 */
@Serializable
data class LoggedResult(
    val key: String = "",
    val value: Double? = null,
    val x: Double? = null,
    val y: Double? = null,
)

/** The source of a measurement point. */
@Serializable
enum class PointSource { Latch, Probe, SubDatum, Camera }

/**
 * One slot point of a measurement. Lengths are µm.
 *
 * @property approachX The approach sign of X: −1, 0, or 1. The same for Y and Z.
 */
@Serializable
data class CollectedPoint(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double? = null,
    val source: PointSource = PointSource.Latch,
    val tipRadiusUm: Double? = null,
    val approachX: Int = 0,
    val approachY: Int = 0,
    val approachZ: Int? = null,
    val sourceId: String? = null,
    val instrumentId: String? = null,
    val instrumentXOffsetUm: Double = 0.0,
    val instrumentYOffsetUm: Double = 0.0,
    val notes: List<String> = emptyList(),
)

/** The display format of a log entry: the unit and the decimals of a length. */
@Serializable
data class LogDisplayFormat(
    val metric: Boolean = true,
    val precision: Int = 3,
)

/**
 * One entry of the inspection log of a workspace. The sequence
 * starts at 1 in each workspace.
 *
 * @property verb The wire ID of the function, for example `circle_from_3_points`.
 * @property notes The system notes, for example `UNCOMPENSATED` or `FRAME_CHANGED`.
 * @property note The note of the user, or null.
 * @property axisAngle The axis alignment angle in degrees.
 */
@Serializable
data class MeasurementLogEntry(
    val id: String = newId(),
    val workspaceId: String = "",
    val sequence: Int = 0,
    val verb: String = "",
    val title: String = "",
    val results: List<LoggedResult> = emptyList(),
    val points: List<CollectedPoint> = emptyList(),
    val notes: List<String> = emptyList(),
    val note: String? = null,
    val workspaceName: String = "",
    val display: LogDisplayFormat = LogDisplayFormat(),
    val axisAngle: Double = 0.0,
    val createdOn: Instant = Clock.System.now(),
)
