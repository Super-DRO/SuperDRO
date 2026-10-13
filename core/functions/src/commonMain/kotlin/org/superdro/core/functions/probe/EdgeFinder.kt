package org.superdro.core.functions.probe

import org.superdro.core.functions.FunctionMessage
import org.superdro.core.functions.FunctionSound
import org.superdro.core.functions.patterns.AxisPoint
import org.superdro.core.functions.tipcal.SpreadLimits
import org.superdro.core.model.AxisId
import org.superdro.core.model.ProbeConfig
import org.superdro.core.model.ProbeProfile
import org.superdro.core.model.ReadoutFormat

/**
 * The four edges of the edge finder. Edge A of an axis is Left (X) or Near (Y). Edge B is
 * Right (X) or Far (Y).
 */
enum class EdgeSide(val axis: AxisId, val isSideA: Boolean) {
    Left(AxisId.X, true),
    Right(AxisId.X, false),
    Near(AxisId.Y, true),
    Far(AxisId.Y, false),
}

/** The state of one edge. */
enum class EdgeStatus { Unknown, Scanning, Located }

/** One edge: the state and the value (µm, absolute). Only a [EdgeStatus.Located] edge has a value. */
data class Edge(val status: EdgeStatus = EdgeStatus.Unknown, val valueUm: Double? = null)

/** The feature type of an axis: `s = +1` for "External", `s = −1` for "Internal". */
enum class FeatureType(val sign: Int) { External(1), Internal(-1) }

/** The origin options of an axis. */
enum class OriginOption {
    /** Left (X) or Near (Y): `A + s × r`. */
    SideA,

    /** `A + (B − A) / 2`. */
    Center,

    /** Right (X) or Far (Y): `B − s × r`. */
    SideB,
}

/** The coordinate system of "Set Origin". */
enum class CoordinateMode { Absolute, Incremental }

/** The approach of the probe at a trigger: the axis and the sign of the motion (AUTO). */
enum class Approach(val axis: AxisId, val sign: Int) {
    XPositive(AxisId.X, 1),
    XNegative(AxisId.X, -1),
    YPositive(AxisId.Y, 1),
    YNegative(AxisId.Y, -1),
    ZPositive(AxisId.Z, 1),
    ZNegative(AxisId.Z, -1);

    /**
     * The edge that the approach finds:
     *
     * | Motion | External feature | Internal feature |
     * |---|---|---|
     * | `X_POSITIVE` | Left | Right |
     * | `X_NEGATIVE` | Right | Left |
     * | `Y_POSITIVE` | Near | Far |
     * | `Y_NEGATIVE` | Far | Near |
     *
     * A Z approach has no edge.
     */
    fun edge(feature: FeatureType): EdgeSide? {
        val external = feature == FeatureType.External
        return when (this) {
            XPositive -> if (external) EdgeSide.Left else EdgeSide.Right
            XNegative -> if (external) EdgeSide.Right else EdgeSide.Left
            YPositive -> if (external) EdgeSide.Near else EdgeSide.Far
            YNegative -> if (external) EdgeSide.Far else EdgeSide.Near
            ZPositive, ZNegative -> null
        }
    }

    companion object {
        /** Gives the approach of [axis] with the sign [sign] (+1 or −1). */
        fun of(axis: AxisId, sign: Int): Approach? = entries.firstOrNull { approach ->
            approach.axis == axis && approach.sign == sign
        }
    }
}

/** The origin formulas of the edge finder. Values are µm. */
object EdgeOrigins {

    /**
     * Gives the origin `O` of an axis, or null when the option needs an edge that has no value.
     *
     * | Option | Origin `O` |
     * |---|---|
     * | A side (Left, Near) | `A + s × r` |
     * | Center | `A + (B − A) / 2` |
     * | B side (Right, Far) | `B − s × r` |
     *
     * @param stylusRadiusUm `r` = stylus diameter / 2.
     */
    fun origin(option: OriginOption, aUm: Double?, bUm: Double?, feature: FeatureType, stylusRadiusUm: Double): Double? =
        when (option) {
            OriginOption.SideA -> aUm?.let { a -> a + feature.sign * stylusRadiusUm }
            OriginOption.Center -> if (aUm != null && bUm != null) aUm + (bUm - aUm) / 2.0 else null
            OriginOption.SideB -> bUm?.let { b -> b - feature.sign * stylusRadiusUm }
        }

    /** One edge enables its side. Two edges enable all three options. */
    fun enabledOptions(aUm: Double?, bUm: Double?): Set<OriginOption> = when {
        aUm != null && bUm != null -> setOf(OriginOption.SideA, OriginOption.Center, OriginOption.SideB)
        aUm != null -> setOf(OriginOption.SideA)
        bUm != null -> setOf(OriginOption.SideB)
        else -> emptySet()
    }

    /** The option that the dialog selects: the side of one edge, or "Center" for two edges. */
    fun autoOption(aUm: Double?, bUm: Double?): OriginOption? = when {
        aUm != null && bUm != null -> OriginOption.Center
        aUm != null -> OriginOption.SideA
        bUm != null -> OriginOption.SideB
        else -> null
    }
}

/**
 * The values of the edge finder that come from the probe profile and the settings.
 *
 * @property xOffsetUm The offset that a probe sample of X gets ([ProbeTechnique.sampleOffsetUm]).
 * @property yOffsetUm The offset that a probe sample of Y gets.
 * @property zOffsetUm The `ZOffset` of the profile. The Z origin is the Z value + this offset.
 * @property probeOn True when the probe edge finder is on. Else a tap captures the current position.
 * @property zCapture True when the Z row shows: Z is enabled and the profile is a touch probe.
 * @property metricDisplay The unit of the spread message: mm (true) or inch (false).
 */
data class EdgeFinderSettings(
    val stylusDiameterUm: Double = 0.0,
    val sampleCount: Int = ProbeProfile.DEFAULT_SAMPLE_COUNT,
    val spreadLimitUm: Double = SpreadLimits.DEFAULT_UM,
    val xOffsetUm: Double = 0.0,
    val yOffsetUm: Double = 0.0,
    val zOffsetUm: Double = 0.0,
    val probeOn: Boolean = false,
    val zCapture: Boolean = false,
    val metricDisplay: Boolean = true,
    val readoutFormat: ReadoutFormat = ReadoutFormat.Format3,
) {
    /** `r` = stylus diameter / 2. */
    val stylusRadiusUm: Double get() = stylusDiameterUm / 2.0

    /** AUTO shows when the probe and the probe edge finder are on. */
    val autoVisible: Boolean get() = probeOn

    /** The sample offset of [axis]. Z has the offset 0. */
    fun sampleOffsetUm(axis: AxisId): Double = when (axis) {
        AxisId.X -> xOffsetUm
        AxisId.Y -> yOffsetUm
        else -> 0.0
    }

    companion object {
        /**
         * Gives the settings for the selected edge finder [profile].
         *
         * @param instrumentOffsetXUm The measurement instrument offset `H` of X, or null when it is
         *   not active.
         * @param instrumentOffsetYUm The `H` of Y, or null.
         */
        fun from(
            probe: ProbeConfig,
            profile: ProbeProfile?,
            zEnabled: Boolean,
            metricDisplay: Boolean,
            readoutFormat: ReadoutFormat = ReadoutFormat.Format3,
            instrumentOffsetXUm: Double? = null,
            instrumentOffsetYUm: Double? = null,
        ): EdgeFinderSettings {
            val probeOn = ProbeTechnique.probeEdgeFinderOn(probe, profile)
            return EdgeFinderSettings(
                stylusDiameterUm = ProbeTechnique.stylusDiameterUm(profile),
                sampleCount = ProbeTechnique.sampleCount(profile),
                spreadLimitUm = SpreadLimits.of(profile).valueUm,
                xOffsetUm = ProbeTechnique.sampleOffsetUm(AxisId.X, profile, instrumentOffsetXUm),
                yOffsetUm = ProbeTechnique.sampleOffsetUm(AxisId.Y, profile, instrumentOffsetYUm),
                zOffsetUm = profile?.zOffsetUm ?: 0.0,
                probeOn = probeOn,
                zCapture = zEnabled && probeOn && profile != null && ProbeTechnique.zCaptureAccepts(profile.type),
                metricDisplay = metricDisplay,
                readoutFormat = readoutFormat,
            )
        }
    }
}

/** The target of the single-edge sampler: one edge, or the Z position. */
sealed interface SamplerTarget {
    data class EdgeTarget(val side: EdgeSide) : SamplerTarget
    data object ZTarget : SamplerTarget
}

/** The single-edge sampler while it runs. */
data class SamplerState(val target: SamplerTarget, val samples: SampleSet = SampleSet())

/** AUTO while it runs: the locked approach and the samples of the current edge. */
data class AutoState(val locked: Approach? = null, val samples: SampleSet = SampleSet())

/**
 * One accepted probe closure (a `ProbeEvent` of the input stage).
 *
 * @property timeMs The time stamp `t` of the probe message (session clock).
 * @property latchedUm The latched absolute position of each axis at `t`. An axis that is not in
 *   the map has no position at `t`.
 * @property histories The input history of each axis near `t`, for the AUTO direction rule.
 */
data class ProbeTrigger(
    val timeMs: Long,
    val latchedUm: Map<AxisId, Double>,
    val histories: Map<AxisId, AxisHistory> = emptyMap(),
)

/**
 * The state of the dialog "Indicate Workpiece".
 *
 * @property zValueUm The captured Z position (the Z field), or null.
 * @property message The text of the message line, or null.
 */
data class EdgeFinderState(
    val settings: EdgeFinderSettings = EdgeFinderSettings(),
    val edges: Map<EdgeSide, Edge> = EdgeSide.entries.associateWith { Edge() },
    val xFeature: FeatureType = FeatureType.External,
    val yFeature: FeatureType = FeatureType.External,
    val xOption: OriginOption? = null,
    val yOption: OriginOption? = null,
    val mode: CoordinateMode = CoordinateMode.Absolute,
    val sampler: SamplerState? = null,
    val auto: AutoState? = null,
    val zValueUm: Double? = null,
    val message: FunctionMessage? = null,
) {
    /** Gives the edge [side]. */
    fun edge(side: EdgeSide): Edge = edges[side] ?: Edge()

    /** True while AUTO runs. */
    val autoOn: Boolean get() = auto != null

    /** The feature type of [axis] (X or Y). */
    fun feature(axis: AxisId): FeatureType = if (axis == AxisId.Y) yFeature else xFeature

    /** The selected origin option of [axis] (X or Y). */
    fun option(axis: AxisId): OriginOption? = if (axis == AxisId.Y) yOption else xOption

    /** The options that [axis] enables. */
    fun enabledOptions(axis: AxisId): Set<OriginOption> {
        val (a, b) = edgeValues(axis)
        return EdgeOrigins.enabledOptions(a, b)
    }

    /** The origin `O` of [axis] (X or Y), or null. */
    fun origin(axis: AxisId): Double? {
        val option = option(axis) ?: return null
        val (a, b) = edgeValues(axis)
        return EdgeOrigins.origin(option, a, b, feature(axis), settings.stylusRadiusUm)
    }

    /** The Z origin `O_z` = the Z value + the `ZOffset` of the profile, or null. */
    val zOriginUm: Double? get() = zValueUm?.plus(settings.zOffsetUm)

    /** "Set Origin" and "Add Sub-Datum" are enabled when one axis has an origin, or Z has a value. */
    val canApply: Boolean get() = origin(AxisId.X) != null || origin(AxisId.Y) != null || zValueUm != null

    internal fun edgeValues(axis: AxisId): Pair<Double?, Double?> {
        val sides = EdgeSide.entries.filter { side -> side.axis == axis }
        val a = sides.first { side -> side.isSideA }
        val b = sides.first { side -> !side.isSideA }
        return edge(a).valueUm to edge(b).valueUm
    }
}

/**
 * The result of one action of the edge finder: the new state, the sounds to play, and true in
 * [close] when the dialog closes.
 */
data class EdgeFinderStep(
    val state: EdgeFinderState,
    val sounds: List<FunctionSound> = emptyList(),
    val close: Boolean = false,
)

/**
 * One command of "Set Origin" for the readout of [axis].
 *
 * - [CoordinateMode.Absolute]: `h(valueUm)` sets the absolute value, and the axis changes to the
 *   absolute display (`rel = false`).
 * - [CoordinateMode.Incremental]: `i(valueUm)` sets the incremental value, and the axis changes
 *   to the incremental display.
 *
 * The value is a shown value. The readout applies its X converter one time. On a mill the
 * converter has no effect.
 */
data class AxisCommand(val axis: AxisId, val valueUm: Double, val mode: CoordinateMode)

/** The result of "Set Origin": the commands, and the state with the sampler and AUTO stopped. */
data class SetOriginResult(val commands: List<AxisCommand>, val state: EdgeFinderState) {
    /** "Set Origin" closes the dialog. */
    val close: Boolean get() = true
}

/** The result of "Add Sub-Datum": the point, and the new state. The dialog stays open. */
data class AddSubDatumResult(val point: AxisPoint, val state: EdgeFinderState)

/**
 * The edge finder "Indicate Workpiece". Each function is pure: it takes a state and gives a new state.
 *
 * AUTO uses the direction rule of the input history ([AxisHistory.direction]). It has no motion
 * trackers. Thus AUTO always uses X and Y, keeps no observer after a stop, and uses only the time
 * stamps of the session clock.
 */
object EdgeFinder {

    /**
     * A tap on an edge. A tap stops AUTO and clears the message. Then:
     *
     * - `UNKNOWN`: each other `SCANNING` edge changes to `UNKNOWN`, and the sampler stops. With the
     *   probe edge finder off, the tap captures [currentAbsUm]. With the probe closed, the message
     *   is "Probe is in triggered state. Please move the probe away from the workpiece.". Else the
     *   sampler starts and the edge changes to `SCANNING`.
     * - `SCANNING`: the edge changes to `UNKNOWN`, and the sampler stops.
     * - `LOCATED`: the message is "Long press this button to clear current value".
     *
     * @param currentAbsUm The current absolute position of the axis of [side], or null when it is
     *   not known.
     */
    fun tapEdge(state: EdgeFinderState, side: EdgeSide, currentAbsUm: Double?, probeClosed: Boolean): EdgeFinderStep {
        val start = state.copy(auto = null, message = null)
        return when (start.edge(side).status) {
            EdgeStatus.Unknown -> {
                val cleared = start.resetScanning(except = side).copy(sampler = null)
                when {
                    !cleared.settings.probeOn -> EdgeFinderStep(cleared.capture(side, currentAbsUm))
                    probeClosed -> EdgeFinderStep(cleared.copy(message = EdgeFinderTexts.PROBE_TRIGGERED.message()))
                    else -> EdgeFinderStep(
                        cleared.withEdge(side, Edge(EdgeStatus.Scanning))
                            .copy(sampler = SamplerState(SamplerTarget.EdgeTarget(side))),
                    )
                }
            }
            EdgeStatus.Scanning -> EdgeFinderStep(start.withEdge(side, Edge()).copy(sampler = null))
            EdgeStatus.Located -> EdgeFinderStep(start.copy(message = EdgeFinderTexts.LONG_PRESS_TO_CLEAR.message()))
        }
    }

    /**
     * A long press on an edge: `UNKNOWN` captures [currentAbsUm] (a manual capture adds no
     * offset), `LOCATED` clears the edge, `SCANNING` does nothing.
     */
    fun longPressEdge(state: EdgeFinderState, side: EdgeSide, currentAbsUm: Double?): EdgeFinderStep =
        when (state.edge(side).status) {
            EdgeStatus.Unknown -> EdgeFinderStep(state.capture(side, currentAbsUm))
            EdgeStatus.Located -> EdgeFinderStep(state.withEdge(side, Edge()))
            EdgeStatus.Scanning -> EdgeFinderStep(state)
        }

    /**
     * Starts or stops AUTO. A start needs the probe edge finder on. With the probe closed, a start
     * gives the "Probe is in triggered state" message. A start sets each `SCANNING` edge to
     * `UNKNOWN`, stops the sampler, and clears the samples and the locked approach. A stop clears
     * them too, and sets the `SCANNING` edge of AUTO to `UNKNOWN`.
     */
    fun toggleAuto(state: EdgeFinderState, probeClosed: Boolean): EdgeFinderStep {
        if (state.autoOn) return EdgeFinderStep(state.resetScanning(except = null).copy(auto = null))
        if (!state.settings.autoVisible) return EdgeFinderStep(state)
        if (probeClosed) return EdgeFinderStep(state.copy(message = EdgeFinderTexts.PROBE_TRIGGERED.message()))
        val started = state.resetScanning(except = null).copy(sampler = null, auto = AutoState(), message = null)
        return EdgeFinderStep(started)
    }

    /**
     * Arms or disarms the Z capture. The Z capture uses
     * the single-edge sampler on Z with the offset 0. A start stops AUTO and sets the `SCANNING`
     * edges to `UNKNOWN`.
     */
    fun toggleZCapture(state: EdgeFinderState, probeClosed: Boolean): EdgeFinderStep {
        if (state.sampler?.target == SamplerTarget.ZTarget) return EdgeFinderStep(state.copy(sampler = null))
        if (!state.settings.zCapture) return EdgeFinderStep(state)
        if (probeClosed) return EdgeFinderStep(state.copy(message = EdgeFinderTexts.PROBE_TRIGGERED.message()))
        val armed = state.resetScanning(except = null)
            .copy(auto = null, message = null, sampler = SamplerState(SamplerTarget.ZTarget))
        return EdgeFinderStep(armed)
    }

    /** One accepted probe closure. It goes to AUTO or to the single-edge sampler, if one runs. */
    fun onTrigger(state: EdgeFinderState, trigger: ProbeTrigger): EdgeFinderStep {
        val auto = state.auto
        val sampler = state.sampler
        return when {
            auto != null -> autoSample(state, auto, trigger)
            sampler != null -> singleSample(state, sampler, trigger)
            else -> EdgeFinderStep(state)
        }
    }

    /** Changes the feature type of [axis]. The change is not possible while AUTO runs. */
    fun setFeature(state: EdgeFinderState, axis: AxisId, feature: FeatureType): EdgeFinderState {
        if (state.autoOn) return state
        val changed = if (axis == AxisId.Y) state.copy(yFeature = feature) else state.copy(xFeature = feature)
        return changed.reselect(axis)
    }

    /** Selects the origin option of [axis]. An option that is not enabled is ignored. */
    fun selectOption(state: EdgeFinderState, axis: AxisId, option: OriginOption): EdgeFinderState {
        if (option !in state.enabledOptions(axis)) return state
        return if (axis == AxisId.Y) state.copy(yOption = option) else state.copy(xOption = option)
    }

    /**
     * "Set Origin". For each axis with an origin `O`, and `P` the current absolute
     * position of that axis: absolute `h(P − O)` and `rel = false`; incremental `i(P − O)`. With a
     * Z value, Z gets `P_z − O_z`. Then the sampler and AUTO stop, and the dialog closes.
     *
     * @param currentAbsUm The current absolute position of each axis. An axis with no position
     *   gets no command.
     */
    fun setOrigin(state: EdgeFinderState, currentAbsUm: Map<AxisId, Double>): SetOriginResult {
        val commands = buildList {
            for (axis in listOf(AxisId.X, AxisId.Y)) {
                val origin = state.origin(axis) ?: continue
                val position = currentAbsUm[axis] ?: continue
                add(AxisCommand(axis, position - origin, state.mode))
            }
            val zOrigin = state.zOriginUm
            val zPosition = currentAbsUm[AxisId.Z]
            if (zOrigin != null && zPosition != null) add(AxisCommand(AxisId.Z, zPosition - zOrigin, state.mode))
        }
        return SetOriginResult(commands, state.copy(sampler = null, auto = null))
    }

    /**
     * "Add Sub-Datum". X and Y are the origins, or the current positions when an axis has
     * no origin. Z is the Z origin when Z has a value. Else Z is the current Z, if Z is enabled and
     * 3D sub-datums are on. A tap ([keepEdges] false) clears the edges. A long press keeps them.
     * The dialog stays open.
     */
    fun addSubDatum(
        state: EdgeFinderState,
        currentAbsUm: Map<AxisId, Double>,
        zEnabled: Boolean,
        use3dSubDatums: Boolean,
        keepEdges: Boolean,
    ): AddSubDatumResult {
        val z = state.zOriginUm ?: currentAbsUm[AxisId.Z].takeIf { zEnabled && use3dSubDatums }
        val point = AxisPoint(
            x = state.origin(AxisId.X) ?: currentAbsUm[AxisId.X],
            y = state.origin(AxisId.Y) ?: currentAbsUm[AxisId.Y],
            z = z,
        )
        val next = if (keepEdges) {
            state
        } else {
            state.copy(
                edges = EdgeSide.entries.associateWith { Edge() },
                xOption = null,
                yOption = null,
                sampler = null,
            )
        }
        return AddSubDatumResult(point, next.copy(message = null))
    }

    /** "Cancel": stops all probe logic, then the dialog closes. */
    fun cancel(state: EdgeFinderState): EdgeFinderStep =
        EdgeFinderStep(state.resetScanning(except = null).copy(sampler = null, auto = null), close = true)

    private fun singleSample(state: EdgeFinderState, sampler: SamplerState, trigger: ProbeTrigger): EdgeFinderStep {
        val axis = when (val target = sampler.target) {
            is SamplerTarget.EdgeTarget -> target.side.axis
            SamplerTarget.ZTarget -> AxisId.Z
        }
        val position = trigger.latchedUm[axis] ?: return refused(state, EdgeFinderTexts.POSITION_UNKNOWN.message())
        val samples = sampler.samples.add(position + state.settings.sampleOffsetUm(axis))
        if (samples.count < state.settings.sampleCount) {
            return EdgeFinderStep(state.copy(sampler = sampler.copy(samples = samples), message = null), listOf(FunctionSound.Short))
        }
        val mean = samples.meanUm!!
        val message = spreadMessage(state.settings, samples)
        val done = when (val target = sampler.target) {
            is SamplerTarget.EdgeTarget -> state.withEdge(target.side, Edge(EdgeStatus.Located, mean))
            SamplerTarget.ZTarget -> state.copy(zValueUm = mean)
        }
        return EdgeFinderStep(done.copy(sampler = null, message = message), listOf(FunctionSound.Long))
    }

    /**
     * The AUTO trigger rule. The checks occur in this
     * sequence. Each failed check ignores the trigger, plays the error sound, and AUTO continues
     * with its samples and its locked approach.
     *
     * 1. No axis with a direction: "No axis was moving when the probe triggered."
     * 2. Two or more axes: "Please move one axis at a time".
     * 3. Only Z: "Z axis probing is not supported yet".
     * 4. Not the locked direction: "Change in direction detected during probe sampling".
     * 5. No position at `t`: "The axis position at the trigger is unknown. Try again."
     */
    private fun autoSample(state: EdgeFinderState, auto: AutoState, trigger: ProbeTrigger): EdgeFinderStep {
        val moving = listOf(AxisId.X, AxisId.Y, AxisId.Z).mapNotNull { axis ->
            val sign = trigger.histories[axis]?.direction(trigger.timeMs) ?: 0
            if (sign == 0) null else Approach.of(axis, sign)
        }
        if (moving.isEmpty()) return refused(state, EdgeFinderTexts.NO_AXIS_MOVING.message())
        if (moving.size > 1) return refused(state, EdgeFinderTexts.ONE_AXIS_AT_A_TIME.message())
        val approach = moving.single()
        if (approach.axis == AxisId.Z) return refused(state, EdgeFinderTexts.Z_NOT_SUPPORTED.message())
        if (auto.locked != null && auto.locked != approach) {
            return refused(state, EdgeFinderTexts.CHANGE_IN_DIRECTION.message())
        }
        val position = trigger.latchedUm[approach.axis] ?: return refused(state, EdgeFinderTexts.POSITION_UNKNOWN.message())
        val side = approach.edge(state.feature(approach.axis))!!
        val samples = auto.samples.add(position + state.settings.sampleOffsetUm(approach.axis))
        if (samples.count < state.settings.sampleCount) {
            var next = state.copy(auto = AutoState(approach, samples), message = null)
            if (samples.count == 1) next = next.resetScanning(except = side).withEdge(side, Edge(EdgeStatus.Scanning))
            return EdgeFinderStep(next, listOf(FunctionSound.Short))
        }
        val located = state.withEdge(side, Edge(EdgeStatus.Located, samples.meanUm!!))
        val allLocated = EdgeSide.entries.all { edge -> located.edge(edge).status == EdgeStatus.Located }
        val next = located.copy(
            auto = if (allLocated) null else AutoState(),
            message = spreadMessage(state.settings, samples),
        )
        return EdgeFinderStep(next, listOf(FunctionSound.Long))
    }

    private fun refused(state: EdgeFinderState, message: FunctionMessage): EdgeFinderStep =
        EdgeFinderStep(state.copy(message = message), listOf(FunctionSound.Error))

    private fun spreadMessage(settings: EdgeFinderSettings, samples: SampleSet): FunctionMessage? {
        val spread = samples.spreadUm
        if (spread <= settings.spreadLimitUm) return null
        return EdgeFinderTexts.spreadWarning(spread, settings.metricDisplay, settings.readoutFormat)
    }
}

/** A manual capture: the edge is `LOCATED` at [currentAbsUm], with no offset. */
private fun EdgeFinderState.capture(side: EdgeSide, currentAbsUm: Double?): EdgeFinderState =
    if (currentAbsUm == null) this else withEdge(side, Edge(EdgeStatus.Located, currentAbsUm))

/** Sets each `SCANNING` edge except [except] to `UNKNOWN`. */
private fun EdgeFinderState.resetScanning(except: EdgeSide?): EdgeFinderState {
    var next = this
    for (side in EdgeSide.entries) {
        if (side != except && edge(side).status == EdgeStatus.Scanning) next = next.withEdge(side, Edge())
    }
    return next
}

/** Changes one edge and selects the origin option of its axis again. */
private fun EdgeFinderState.withEdge(side: EdgeSide, edge: Edge): EdgeFinderState =
    copy(edges = edges + (side to edge)).reselect(side.axis)

/** Selects the origin option of [axis] from the edges: the side of one edge, "Center" for two. */
private fun EdgeFinderState.reselect(axis: AxisId): EdgeFinderState {
    val (a, b) = edgeValues(axis)
    val option = EdgeOrigins.autoOption(a, b)
    return if (axis == AxisId.Y) copy(yOption = option) else copy(xOption = option)
}
