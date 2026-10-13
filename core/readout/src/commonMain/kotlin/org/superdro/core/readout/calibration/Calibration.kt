package org.superdro.core.readout.calibration

import kotlin.math.abs
import kotlin.time.Instant
import org.superdro.core.model.AxisId
import org.superdro.core.model.BacklashMode
import org.superdro.core.model.CalibrationStatus
import org.superdro.core.model.Channels
import org.superdro.core.model.EncoderKind
import org.superdro.core.model.EncoderProfile
import org.superdro.core.model.EncoderType
import org.superdro.core.model.InputConfig
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.Units
import org.superdro.core.readout.NumberPattern
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.encoder.ErrorCorrection
import org.superdro.core.readout.encoder.MapTable

/** The result of the block size step. */
sealed interface BlockCheck {
    /** The block size [blockUm] is 50000 µm or more. */
    data class Ok(val blockUm: Double) : BlockCheck

    /** The block size is 0: error "Block Size Required". */
    data object Required : BlockCheck {
        val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.BlockSizeRequired)
    }

    /**
     * The block size is less than 50000 µm: warning "Block Too Small" ("at least 50.000 mm /
     * 2.0000 in"), with Retry and Continue.
     */
    data class TooSmall(val blockUm: Double) : BlockCheck {
        val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.BlockTooSmall)
        val detail: ReadoutMessage get() = ReadoutMessage(ReadoutText.BlockTooSmallDetail)
    }
}

/** The checks of one position sample. */
sealed interface SampleCheck {

    /** The text of a failed check, or null for [Ok]. */
    val message: ReadoutMessage?

    /** N = 0: error "No Reading Received". */
    data object NoReading : SampleCheck {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.NoReadingReceived)
    }

    /** N < 10: error "Reading Sample Too Small". */
    data class TooFew(val count: Int) : SampleCheck {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.ReadingSampleTooSmall)
    }

    /** `max − min` of the trimmed list is more than 1 count: warning "Excessive Jitter Detected", Retry or Cancel. */
    data class Jitter(val mean: Double, val spread: Double) : SampleCheck {
        override val message: ReadoutMessage get() = ReadoutMessage(ReadoutText.ExcessiveJitter)
    }

    /** The sample is good. [mean] is the average of the trimmed list. */
    data class Ok(val mean: Double) : SampleCheck {
        override val message: ReadoutMessage? get() = null
    }
}

/**
 * The result of a calibration.
 *
 * @property delta `Δ = mean₂ − mean₁` in counts.
 * @property ppu The calculated resolution: counts per inch (linear) or counts per revolution
 *   (angular), before the round-off.
 * @property resolution `round(ppu × 1000) / 1000`: the value that "Apply" writes.
 * @property reversed `Δ < 0`: the invert flag that "Apply" writes.
 * @property umPerCount `25400 / ppu` for a linear input, else null.
 * @property degreesPerCount `360 / ppu` for an angular input, else null.
 */
data class CalibrationResult(
    val delta: Double,
    val ppu: Double,
    val resolution: Double,
    val reversed: Boolean,
    val umPerCount: Double?,
    val degreesPerCount: Double?,
) {
    /** The resolution with 3 decimals, for example "5080.000". */
    val resolutionText: String get() = RESOLUTION.format(resolution)

    /** The µm for each count with 2 decimals, for example "5.00", or null. */
    val umPerCountText: String? get() = umPerCount?.let { value -> UM_PER_COUNT.format(value) }

    /** The degrees for each count with 4 decimals, for example "0.0500", or null. */
    val degreesPerCountText: String? get() = degreesPerCount?.let { value -> DEGREES_PER_COUNT.format(value) }

    private companion object {
        val RESOLUTION = NumberPattern("###0.000")
        val UM_PER_COUNT = NumberPattern("###0.00")
        val DEGREES_PER_COUNT = NumberPattern("##0.0000")
    }
}

/**
 * The calibration math.
 *
 * ```text
 * linear:   ppu (counts per inch) = |Δ| / block_um × 25400;  µm per count = 25400 / ppu
 * angular:  ppu (counts per rev)  = |Δ| / 5;                 degrees per count = 360 / ppu
 * apply:    ppu = round(ppu × 1000) / 1000;  invert = reversed
 * ```
 *
 * Worked examples: 2 in (50800 µm), means 1250.0 and 11410.0 give ppu 5080.000 and 5.00 µm for
 * each count. 75 mm, means 20000.0 and 4250.4 give ppu 5333.865, 4.76 µm for each count, and the
 * invert flag. Angular means 120.0 and −35880.0 give ppu 7200.000, 0.05° for each count, and the
 * invert flag.
 */
object CalibrationMath {

    /** The smallest block that gives no warning: 50000 µm (50.000 mm, about 2.0000 in). */
    const val MIN_BLOCK_UM: Double = 50_000.0

    /** The smallest number of values of one sample. */
    const val MIN_SAMPLES: Int = 10

    /** The largest spread of the trimmed list with no jitter warning: 1 count. */
    const val MAX_JITTER_COUNTS: Double = 1.0

    /** The smallest distance between the two means: 10 counts. */
    const val MIN_DELTA_COUNTS: Double = 10.0

    /** The time of one sample: 15000 ms. The text says "up to 15 seconds". */
    const val SAMPLE_TIME_MS: Long = 15_000L

    /** The progress interval of one sample: 1000 ms. */
    const val PROGRESS_INTERVAL_MS: Long = 1_000L

    /** The revolutions between the two angular positions. */
    const val ANGULAR_REVOLUTIONS: Int = 5

    /** Gives the block size in µm: `value × 1000` (mm) or `value × 25400` (inch). */
    fun blockUm(value: Double, metric: Boolean): Double = value * if (metric) Units.UM_PER_MM else Units.UM_PER_INCH

    /** Checks the block size [value] in mm ([metric]) or inch. */
    fun checkBlock(value: Double, metric: Boolean): BlockCheck {
        val block = blockUm(value, metric)
        return when {
            block == 0.0 || !block.isFinite() -> BlockCheck.Required
            block < MIN_BLOCK_UM -> BlockCheck.TooSmall(block)
            else -> BlockCheck.Ok(block)
        }
    }

    /**
     * Checks one sample of counts: N = 0, N < 10, then the trim (sort, remove one minimum and
     * one maximum), the mean of the trimmed list, and the jitter (`max − min > 1` count).
     */
    fun checkSample(values: List<Double>): SampleCheck {
        if (values.isEmpty()) return SampleCheck.NoReading
        if (values.size < MIN_SAMPLES) return SampleCheck.TooFew(values.size)
        val trimmed = values.sorted().drop(1).dropLast(1)
        val mean = trimmed.average()
        val spread = trimmed.last() - trimmed.first()
        return if (spread > MAX_JITTER_COUNTS) SampleCheck.Jitter(mean, spread) else SampleCheck.Ok(mean)
    }

    /** True when the second position is too close to the first: `|mean₂ − mean₁| < 10` counts ("Readings Too Close"). */
    fun tooClose(firstMean: Double, secondMean: Double): Boolean = abs(secondMean - firstMean) < MIN_DELTA_COUNTS

    /** The text of the warning "Readings Too Close". */
    val tooCloseMessage: ReadoutMessage get() = ReadoutMessage(ReadoutText.ReadingsTooClose)

    /** The linear result for the means [firstMean] and [secondMean] and the block [blockUm]. */
    fun linear(firstMean: Double, secondMean: Double, blockUm: Double): CalibrationResult {
        val delta = secondMean - firstMean
        val ppu = abs(delta) / blockUm * Units.UM_PER_INCH
        val resolution = roundResolution(ppu)
        return CalibrationResult(delta, ppu, resolution, delta < 0.0, Units.UM_PER_INCH / resolution, null)
    }

    /** The angular result for the means [firstMean] and [secondMean] (5 revolutions). */
    fun angular(firstMean: Double, secondMean: Double): CalibrationResult {
        val delta = secondMean - firstMean
        val ppu = abs(delta) / ANGULAR_REVOLUTIONS
        val resolution = roundResolution(ppu)
        return CalibrationResult(delta, ppu, resolution, delta < 0.0, null, 360.0 / resolution)
    }

    /** `round(ppu × 1000) / 1000` with the round-off half up (Java `Math.round`). */
    fun roundResolution(ppu: Double): Double = NumberPattern.roundHalfUp(ppu * 1000.0) / 1000.0

    /**
     * "Apply": gives [profile] with the resolution (3 decimals), the direction, the status
     * CALIBRATED, the date [now], the machine type, and the axis.
     */
    fun apply(profile: EncoderProfile, result: CalibrationResult, machineType: MachineType, axis: AxisId?, now: Instant): EncoderProfile =
        profile.copy(
            resolution = result.resolution,
            reversed = result.reversed,
            calibration = profile.calibration.copy(
                status = CalibrationStatus.Calibrated,
                calibratedOn = now,
                machineType = machineType,
                axisRole = axis,
            ),
        )
}

/**
 * The value source of the calibration wizard.
 *
 * The wizard reads the raw counts of the input from the session. With an error map, the map
 * corrects each sample. The sampler adds the hysteresis of the calibrated input when the
 * count goes up: the constant of the LAG backlash ("Hysteresis") of the profile.
 *
 * Give each raw count of the input to [observe] while the wizard runs, so that the sampler knows
 * the direction. Give the counts of a sample to [sample].
 */
class CalibrationSampler(profile: EncoderProfile) {

    private val correction: ErrorCorrection = run {
        val table = MapTable.of(profile.errorMap)
        val cyclic = profile.kind == EncoderKind.Angular && profile.errorMapCyclic && profile.resolution > 0.0
        if (table.isEmpty) ErrorCorrection.NONE else ErrorCorrection(table, if (cyclic) profile.resolution else null)
    }

    /** The hysteresis of the input in counts: the LAG constant, else 0. */
    val hysteresis: Long =
        if (profile.type != EncoderType.LeadscrewEncoder && profile.backlashMode != BacklashMode.None && profile.backlashCounts > 0L) {
            profile.backlashCounts
        } else {
            0L
        }

    private var previous: Long? = null

    /** True while the count goes up. */
    var up: Boolean = false
        private set

    private val values = ArrayList<Double>()

    /** The values of the current sample. */
    val sampleValues: List<Double> get() = values.toList()

    /** Updates the direction with the raw count [raw]. */
    fun observe(raw: Long) {
        val last = previous
        if (last != null) {
            val d = last - raw
            if (d < 0L) up = true
            if (d > 0L) up = false
        }
        previous = raw
    }

    /** Gives the sample value of the raw count [raw]: `raw + map correction + (H if up)`. */
    fun valueOf(raw: Long): Double = (correction.apply(raw) + if (up) hysteresis else 0L).toDouble()

    /** Adds the raw count [raw] to the current sample. */
    fun sample(raw: Long) {
        observe(raw)
        values.add(valueOf(raw))
    }

    /** Starts a new sample. The direction stays. */
    fun startSample() {
        values.clear()
    }

    /** Gives the checks of the current sample. */
    fun check(): SampleCheck = CalibrationMath.checkSample(values)
}

/** An edge name of the calibration wizard. */
enum class CalibrationEdge(val text: ReadoutText) {
    Left(ReadoutText.EdgeLeft),
    Right(ReadoutText.EdgeRight),
    Front(ReadoutText.EdgeFront),
    Back(ReadoutText.EdgeBack),
    Bottom(ReadoutText.EdgeBottom),
    Top(ReadoutText.EdgeTop),
    First(ReadoutText.EdgeFirst),
    Second(ReadoutText.EdgeSecond),
}

/**
 * The start of the calibration wizard and the edge names.
 *
 * | Machine type | Axis | First edge | Second edge |
 * |---|---|---|---|
 * | Vertical mill | X | left | right |
 * | Vertical mill | Y | front | back |
 * | Vertical mill | Z | bottom | top |
 * | Horizontal mill | X | left | right |
 * | Horizontal mill | Y | bottom | top |
 * | Horizontal mill | Z | front | back |
 * | Lathe | X | front | back |
 * | Lathe | Z | left | right |
 * | Lathe | auxiliary, caliper | first | second |
 *
 * An axis that the table does not name (for example the caliper on a mill, or no axis) gets
 * "first" and "second".
 */
object CalibrationStart {

    /** Gives the first and the second edge of [axis] on [machineType]. */
    fun edges(machineType: MachineType, axis: AxisId?): Pair<CalibrationEdge, CalibrationEdge> = when (machineType) {
        MachineType.VerticalMill -> when (axis) {
            AxisId.X -> CalibrationEdge.Left to CalibrationEdge.Right
            AxisId.Y -> CalibrationEdge.Front to CalibrationEdge.Back
            AxisId.Z -> CalibrationEdge.Bottom to CalibrationEdge.Top
            else -> CalibrationEdge.First to CalibrationEdge.Second
        }
        MachineType.HorizontalMill -> when (axis) {
            AxisId.X -> CalibrationEdge.Left to CalibrationEdge.Right
            AxisId.Y -> CalibrationEdge.Bottom to CalibrationEdge.Top
            AxisId.Z -> CalibrationEdge.Front to CalibrationEdge.Back
            else -> CalibrationEdge.First to CalibrationEdge.Second
        }
        MachineType.Lathe -> when (axis) {
            AxisId.X -> CalibrationEdge.Front to CalibrationEdge.Back
            AxisId.Z -> CalibrationEdge.Left to CalibrationEdge.Right
            else -> CalibrationEdge.First to CalibrationEdge.Second
        }
    }

    /**
     * Gives the axis of the input [input]: the first of X, Y, Z, and the caliper whose primary
     * input or secondary input uses the letter. Else the angular axis when its input uses the
     * letter. Else null.
     */
    fun axisForInput(profile: MachineProfile, input: Char): AxisId? {
        val letter = Channels.normalize(input)
        val linear = listOf(
            AxisId.X to listOfNotNull(profile.x.primary, profile.x.secondary),
            AxisId.Y to listOfNotNull(profile.y.primary, profile.y.secondary),
            AxisId.Z to listOfNotNull(profile.z.primary, profile.z.secondary),
            AxisId.Caliper to listOf(profile.caliper.input),
        )
        linear.firstOrNull { (_, inputs) -> inputs.any { config -> Channels.normalize(config.channel) == letter } }?.let { (axis, _) -> return axis }
        return if (Channels.normalize(profile.angular.primary.channel) == letter) AxisId.Angular else null
    }

    /**
     * Checks the start for the input of an axis: a secondary input needs its switch on
     * ("Secondary Input Disabled"), and each input needs a channel ("Input Prefix Missing").
     * Gives null when the wizard can start.
     *
     * @param input The input to calibrate, or null when the secondary input is off.
     */
    fun check(input: InputConfig?, secondary: Boolean): ReadoutMessage? = when {
        input == null && secondary -> ReadoutMessage(ReadoutText.SecondaryInputDisabled)
        input == null || Channels.normalize(input.channel) !in Channels.encoderInputs -> ReadoutMessage(ReadoutText.InputPrefixMissing)
        else -> null
    }

    /** True when the input uses the angular procedure (5 revolutions): the kind of its profile is angular. */
    fun angular(profile: EncoderProfile): Boolean = profile.kind == EncoderKind.Angular
}
