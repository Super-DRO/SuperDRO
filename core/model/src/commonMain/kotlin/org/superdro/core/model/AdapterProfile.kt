package org.superdro.core.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The source of the adapter identity. */
@Serializable
enum class IdentitySource {
    /** The Bluetooth address of the link. */
    BtMac,

    /** The value that the adapter reports: the MAC + 2 or the UUID. */
    AdapterReported,
}

/** An input that a legacy adapter declares. */
@Serializable
enum class DeclaredInput { X, Y, Z, W, Probe, Tach }

/** The probe contact type of an adapter. */
@Serializable
enum class ProbeContactType { Auto, NormallyClosed, NormallyOpen }

/** The encoder type of an input. */
@Serializable
enum class EncoderType {
    /** A linear scale on the axis (`SCALE`). */
    Scale,

    /** A rotary encoder on a leadscrew (`ROTARY`). It uses the DEAD_ZONE backlash model. */
    LeadscrewEncoder,

    /** A rotary encoder on the axis or the spindle (`ROTARY_DIRECT`). */
    RotaryEncoder,

    /** A rotary encoder that a rack turns (`RACK_PINION`). */
    RackPinion,
}

/** The measurement kind of an input: disabled, linear (counts per inch), or angular (counts per revolution). */
@Serializable
enum class EncoderKind { Disabled, Linear, Angular }

/** The backlash compensation of an input. `Map` is not permitted on a scale. */
@Serializable
enum class BacklashMode { None, Constant, Map }

/**
 * One point of an error map or a backlash map, in counts. An error map point gives the
 * correction at a raw position. A backlash map point gives the lost motion at a raw position.
 */
@Serializable
data class MapPoint(
    val position: Long = 0L,
    val value: Long = 0L,
)

/** The calibration status of an input. */
@Serializable
enum class CalibrationStatus { NotCalibrated, Calibrated, UserSet, Ignored }

/** The calibration record of an input. */
@Serializable
data class Calibration(
    val status: CalibrationStatus = CalibrationStatus.NotCalibrated,
    val calibratedOn: Instant? = null,
    val machineType: MachineType? = null,
    val axisRole: AxisId? = null,
    val inputMode: String? = null,
)

/**
 * The scale and compensation values of one adapter input.
 *
 * The resolution of an input is only here, not in the axis of the machine profile. A new
 * profile has 25400 counts per inch.
 *
 * @property input The lowercase input letter: `x y z w u v t` ([Channels.encoderInputs]).
 * @property resolution Counts per inch (kind [EncoderKind.Linear]) or counts per revolution
 *   (kind [EncoderKind.Angular]). A value that is not more than 0 gives "no value".
 * @property reversed The direction of the counts (`CanonicalInvert`).
 * @property backlashCounts The constant backlash or hysteresis, in whole counts.
 * @property errorMapCyclic Only for the kind [EncoderKind.Angular]: the position goes modulo one
 *   revolution before the lookup. Default on.
 * @property errorMapComments The comment lines of the map text, in order. The map editor
 *   shows them as the source of the map, and opens a map of the wizard as read only.
 */
@Serializable
data class EncoderProfile(
    val input: Char = Channels.X,
    val label: String? = null,
    val type: EncoderType = EncoderType.Scale,
    val kind: EncoderKind = EncoderKind.Linear,
    val resolution: Double = DEFAULT_LINEAR_RESOLUTION,
    val countsPerScrewRev: Double? = null,
    val reversed: Boolean = false,
    val filter: Boolean = false,
    val backlashMode: BacklashMode = BacklashMode.None,
    val backlashCounts: Long = 0L,
    val errorMap: List<MapPoint> = emptyList(),
    val errorMapCyclic: Boolean = true,
    val backlashMap: List<MapPoint> = emptyList(),
    val calibration: Calibration = Calibration(),
    val errorMapComments: List<String> = emptyList(),
) {
    companion object {
        /**
         * 25400 counts per inch: 1 µm for each count.
         */
        const val DEFAULT_LINEAR_RESOLUTION: Double = 25400.0

        /** 1000 counts per revolution: the default of an angular input. */
        const val DEFAULT_ANGULAR_RESOLUTION: Double = 1000.0

        /** The most points of one map. */
        const val MAX_MAP_POINTS: Int = 20_000

        /**
         * The profile of a new adapter input:
         * - The input `t`: a rotary encoder with 1 count for each revolution.
         * - Each other input with [kind] [EncoderKind.Linear] (the default): a linear scale with
         *   25400 counts per inch.
         * - Each other input with [kind] [EncoderKind.Angular]: a rotary encoder with 1000 counts
         *   for each revolution.
         */
        fun defaultFor(input: Char, kind: EncoderKind = EncoderKind.Linear): EncoderProfile {
            val letter = Channels.normalize(input)
            require(letter in Channels.encoderInputs) { "'$input' is not an encoder input." }
            return when {
                letter == Channels.T ->
                    EncoderProfile(input = letter, type = EncoderType.RotaryEncoder, kind = EncoderKind.Angular, resolution = 1.0)
                kind == EncoderKind.Angular ->
                    EncoderProfile(input = letter, type = EncoderType.RotaryEncoder, kind = EncoderKind.Angular, resolution = DEFAULT_ANGULAR_RESOLUTION)
                else -> EncoderProfile(input = letter, kind = kind)
            }
        }

        /**
         * True when an input with [profile] gives a value. An input with no encoder profile, a
         * profile of the kind [EncoderKind.Disabled], or a resolution that is not more than 0
         * gives "no value", and the readout shows the "Error" state.
         */
        fun givesValue(profile: EncoderProfile?): Boolean =
            profile != null && profile.kind != EncoderKind.Disabled && profile.resolution > 0.0

        /**
         * True when the setup reminder banner shows for an enabled axis input with
         * [profile]: the input has no encoder profile, or its profile has the calibration status
         * [CalibrationStatus.NotCalibrated].
         */
        fun needsSetupReminder(profile: EncoderProfile?): Boolean =
            profile == null || profile.calibration.status == CalibrationStatus.NotCalibrated
    }
}

/**
 * The record of one physical adapter.
 *
 * @property alias 40 characters or less, unique.
 * @property encoders The encoder profile of each input letter.
 * @property probeDebounceMs The probe debounce. The default is 200 ms (0.2 s).
 */
@Serializable
data class AdapterProfile(
    val id: String = newId(),
    val alias: String = "",
    val model: String? = null,
    val identity: String? = null,
    val identitySource: IdentitySource? = null,
    val legacy: Boolean = false,
    val lastLink: AdapterAddress? = null,
    val declaredInputs: Set<DeclaredInput> = emptySet(),
    val probeContactType: ProbeContactType = ProbeContactType.Auto,
    val probeDebounceMs: Long = DEFAULT_PROBE_DEBOUNCE_MS,
    val encoders: Map<Char, EncoderProfile> = Channels.encoderInputs.associateWith { input -> EncoderProfile.defaultFor(input) },
    val lastSeenOn: Instant? = null,
    val createdOn: Instant = Clock.System.now(),
) {
    companion object {
        const val MAX_ALIAS_LENGTH: Int = 40

        /** `probe_debounce_seconds` 0.2. */
        const val DEFAULT_PROBE_DEBOUNCE_MS: Long = 200L
    }
}

/** The type of a probe profile. */
@Serializable
enum class ProbeType { TouchProbe, EdgeFinder2D, ToolHeightSetter, Camera }

/**
 * The stored values of one probe, edge finder, tool height setter, or camera. Lengths are µm.
 *
 * @property profileId The machine profile, or null for all profiles.
 * @property port The probe port `p` or `q`, or null.
 * @property spreadLimitUm The spread limit. Null gives the default spread limit rule.
 * @property standardTip True for one of the six standard tips. The user cannot change a standard
 *   tip, but can delete it.
 */
@Serializable
data class ProbeProfile(
    val id: String = newId(),
    val profileId: String? = null,
    val type: ProbeType = ProbeType.TouchProbe,
    val name: String = "",
    val port: Char? = Channels.P,
    val stylusDiameterUm: Double = 0.0,
    val effectiveDiameterUm: Double? = null,
    val xOffsetUm: Double = 0.0,
    val yOffsetUm: Double = 0.0,
    val zOffsetUm: Double = 0.0,
    val metric: Boolean = true,
    val sampleCount: Int = DEFAULT_SAMPLE_COUNT,
    val spreadLimitUm: Double? = DEFAULT_SPREAD_LIMIT_UM,
    val calibrationResidualUm: Double? = null,
    val calibratedOn: Instant? = null,
    val calibrationSession: String? = null,
    val sessionDigest: String? = null,
    val lastUsedOn: Instant? = null,
    val standardTip: Boolean = false,
) {
    companion object {
        /** `probe_edge_finder_sample_count`: 1 (1 to 10). */
        const val DEFAULT_SAMPLE_COUNT: Int = 1
        const val MIN_SAMPLE_COUNT: Int = 1
        const val MAX_SAMPLE_COUNT: Int = 10

        /** `probe_edge_finder_sample_spread_microns`: 20 µm. */
        const val DEFAULT_SPREAD_LIMIT_UM: Double = 20.0
    }
}
