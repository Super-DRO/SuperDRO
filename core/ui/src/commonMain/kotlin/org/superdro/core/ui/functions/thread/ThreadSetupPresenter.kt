package org.superdro.core.ui.functions.thread

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.superdro.core.functions.thread.DrillSeries
import org.superdro.core.functions.thread.PassKind
import org.superdro.core.functions.thread.PassSchedule
import org.superdro.core.functions.thread.ReliefLabel
import org.superdro.core.functions.thread.StandardDrill
import org.superdro.core.functions.thread.StandardDrillTables
import org.superdro.core.functions.thread.TapAndClearanceCalculator
import org.superdro.core.functions.thread.TapAndHolePrepSummary
import org.superdro.core.functions.thread.ThreadDialRule
import org.superdro.core.functions.thread.ThreadGeometry
import org.superdro.core.functions.thread.ThreadPassSchedule
import org.superdro.core.functions.thread.ThreadPoints
import org.superdro.core.functions.thread.ThreadSetup
import org.superdro.core.functions.thread.ThreadSetupInput
import org.superdro.core.functions.thread.ThreadSetupProblem
import org.superdro.core.functions.thread.ThreadSyncRules
import org.superdro.core.functions.thread.ThreadTables
import org.superdro.core.functions.thread.ThreadText
import org.superdro.core.functions.thread.ThreadUnit
import org.superdro.core.functions.thread.classesFor
import org.superdro.core.functions.thread.includedAngleDeg
import org.superdro.core.functions.thread.isCustom
import org.superdro.core.functions.thread.tipRadiusUm
import org.superdro.core.functions.thread.unit
import org.superdro.core.functions.thread.validForPitch
import org.superdro.core.model.AxisId
import org.superdro.core.model.PartialInsertPreset
import org.superdro.core.model.ThreadClass
import org.superdro.core.model.ThreadCutter
import org.superdro.core.model.ThreadForm
import org.superdro.core.model.ThreadInfeedMode
import org.superdro.core.model.ThreadSpec
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.resources.Res
import org.superdro.core.resources.thrs_created
import org.superdro.core.resources.thrs_join
import org.superdro.core.resources.thrs_label_bore_to
import org.superdro.core.resources.thrs_label_cut_to_major
import org.superdro.core.resources.thrs_label_cut_to_minor
import org.superdro.core.resources.thrs_label_turn_to
import org.superdro.core.resources.thrs_problem_length
import org.superdro.core.resources.thrs_problem_major
import org.superdro.core.resources.thrs_problem_minor
import org.superdro.core.resources.thrs_problem_minor_major
import org.superdro.core.resources.thrs_problem_no_points
import org.superdro.core.resources.thrs_problem_no_size
import org.superdro.core.resources.thrs_problem_no_workspace
import org.superdro.core.resources.thrs_problem_pitch
import org.superdro.core.resources.thrs_problem_relief
import org.superdro.core.resources.thrs_problem_reliefs_too_long
import org.superdro.core.resources.thrs_problem_start_z_nan
import org.superdro.core.resources.thrs_problem_start_z_required
import org.superdro.core.resources.thrs_problem_tpi
import org.superdro.core.resources.thrs_range
import org.superdro.core.resources.thrs_relief_bottom
import org.superdro.core.resources.thrs_relief_face
import org.superdro.core.resources.thrs_relief_mouth
import org.superdro.core.resources.thrs_relief_shoulder
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.text.UiText

/** The tool mode of the thread setup screen. */
enum class ThreadToolMode {
    SinglePoint,
    TapAndDrill,
}

/** The text fields of the thread setup. */
enum class ThreadField {
    Tag,
    Pitch,
    Tpi,
    Major,
    Minor,
    TipRadius,
    StartZ,
    Length,
    LeftRelief,
    RightRelief,
    RoughingPasses,
    CustomWireDiameter,
    MeasuredOverWires,
    TapEngagement,
    BlindHoleDepth,
}

/** One stored thread of the restore list. */
@Immutable
data class ThreadChoice(val id: String, val tag: String, val size: String)

/** One formatted pass row of the pass schedule preview table. */
@Immutable
data class PassRowUiState(
    val number: Int,
    val kind: PassKind,
    val stepRadial: String,
    val cumulativeRadial: String,
    val targetDiameter: String,
    val compoundAdvance: String,
    val zShift: String,
)

/** One formatted standard drill row in the Tap & Drill results. */
@Immutable
data class DrillSeriesRowUiState(
    val series: DrillSeries,
    val label: String,
    val diameter: String,
    val actualEngagementPercent: String,
)

/** Formatted results of the Tap, Drill & Clearance mode. */
@Immutable
data class TapDrillResultsUiState(
    val cutTheoreticalDiameter: String,
    val cutClosestDrill: DrillSeriesRowUiState,
    val cutSeriesDrills: List<DrillSeriesRowUiState>,
    val cutWithinMinorLimits: Boolean?,
    val formTheoreticalDiameter: String?,
    val formClosestDrill: DrillSeriesRowUiState?,
    val closeClearance: String,
    val normalClearance: String,
    val looseClearance: String,
    val countersink90: String,
    val blindBottomingDepth: String?,
    val blindPlugDepth: String?,
    val blindTaperDepth: String?,
)

/**
 * The results of the thread setup, as texts in the thread unit.
 *
 * @property preOpLabel "Bore to (D₁)" (internal) or "Turn to (D)" (external).
 * @property cutToLabel "Cut to (D)" (internal) or "Cut to (D₁)" (external).
 * @property noTolerance True when the class has no tabulated values: the diameters are basic.
 * @property wireDiameter The wire diameter of the three-wire check, or null (internal).
 * @property wireMeasurement The measurement over three wires, or null.
 * @property wireRange The permitted range of the measurement over three wires, or null.
 */
@Immutable
data class ThreadResults(
    val preOpLabel: UiText,
    val preOp: String,
    val preOpRange: UiText,
    val cutToLabel: UiText,
    val cutTo: String,
    val minor: String,
    val major: String,
    val pitchDiameter: String,
    val height: String,
    val noTolerance: Boolean,
    val wireDiameter: String?,
    val wireMeasurement: String?,
    val wireRange: UiText?,
    val starts: Int = 1,
    val lead: String = "",
    val leadAngleDeg: String = "",
    val needsAnvilTilt: Boolean = false,
    val reliefDiameter: String = "",
    val entryChamferDiameter: String = "",
    val actualPitchDiameter: String? = null,
    val remainingRadialDepth: String? = null,
    val wireValid: Boolean = true,
    val insertPresetPitchWarning: Boolean = false,
    val minorGo: String? = null,
    val minorNoGo: String? = null,
    val pitchGo: String? = null,
    val pitchNoGo: String? = null,
    val internalBarHolder: String? = null,
    val internalBarMinBore: String? = null,
    val internalBarMaxDepth: String? = null,
    val internalBarTooSmall: Boolean = false,
    val internalBarDeflectionRisk: Boolean = false,
    val dialRule: ThreadDialRule = ThreadDialRule.KeepHalfNutClosed,
    val safeMaxRpm: Int = 1,
    val scheduleInfeedAngleDeg: String = "",
    val scheduleTotalRadialDepth: String = "",
    val schedulePasses: List<PassRowUiState> = emptyList(),
)

/**
 * The UI state of the thread setup screen.
 *
 * @property unit The unit of the series: mm for ISO and custom metric, inch for UN and custom
 *   imperial. The length fields use it.
 * @property defaultTag The tag of an empty tag field: "Thread N".
 * @property results The live diameters, or null when an input is missing.
 * @property error The problems of the last "Create", joined with a space.
 */
@Immutable
data class ThreadSetupUiState(
    val loaded: Boolean = false,
    val isLathe: Boolean = true,
    val mode: ThreadToolMode = ThreadToolMode.SinglePoint,
    val threads: List<ThreadChoice> = emptyList(),
    val restoredId: String? = null,
    val tag: String = "",
    val defaultTag: String = "Thread 1",
    val form: ThreadForm = ThreadForm.IsoMetricCoarse,
    val internal: Boolean = true,
    val leftHand: Boolean = false,
    val starts: Int = 1,
    val sizes: List<String> = emptyList(),
    val size: String? = null,
    val classes: List<ThreadClass> = emptyList(),
    val threadClass: ThreadClass? = null,
    val cutter: ThreadCutter = ThreadCutter.SharpV,
    val insertPreset: PartialInsertPreset = PartialInsertPreset.Custom,
    val infeedMode: ThreadInfeedMode = ThreadInfeedMode.ModifiedFlank,
    val roughingPasses: NumberFieldState = NumberFieldState(),
    val unit: ThreadUnit = ThreadUnit.Millimeter,
    val pitch: LengthFieldState = LengthFieldState(metric = true),
    val tpi: NumberFieldState = NumberFieldState(),
    val major: LengthFieldState = LengthFieldState(metric = true),
    val minor: LengthFieldState = LengthFieldState(metric = true),
    val tipRadius: LengthFieldState = LengthFieldState(metric = true),
    val wireDiameter: LengthFieldState = LengthFieldState(metric = true),
    val measuredOverWires: LengthFieldState = LengthFieldState(metric = true),
    val tapEngagement: NumberFieldState = NumberFieldState.of(TapAndClearanceCalculator.DEFAULT_CUT_ENGAGEMENT_PERCENT),
    val blindHoleDepth: LengthFieldState = LengthFieldState(metric = true),
    val startZ: LengthFieldState = LengthFieldState(metric = true),
    val length: LengthFieldState = LengthFieldState(metric = true),
    val leftRelief: LengthFieldState = LengthFieldState(metric = true),
    val rightRelief: LengthFieldState = LengthFieldState(metric = true),
    val leftReliefLabel: UiText = UiText.of(Res.string.thrs_relief_bottom),
    val rightReliefLabel: UiText = UiText.of(Res.string.thrs_relief_mouth),
    val results: ThreadResults? = null,
    val passSchedule: PassSchedule? = null,
    val tapSummary: TapAndHolePrepSummary? = null,
    val tapResults: TapDrillResultsUiState? = null,
    val error: UiText? = null,
) {
    /** True for the custom forms: the pitch and the diameters are typed. */
    val custom: Boolean get() = form.isCustom

    /** The text of the custom wire diameter field. */
    val wireDiameterText: String get() = wireDiameter.text

    /** The text of the measured-over-wires field. */
    val measuredOverWiresText: String get() = measuredOverWires.text

    /** The text of the tap engagement percentage field. */
    val tapEngagementPercent: String get() = tapEngagement.text

    /** The text of the blind-hole full thread depth field. */
    val blindHoleThreadDepthText: String get() = blindHoleDepth.text
}

/**
 * The presenter of the thread setup screen:
 *
 * - The inputs: the tool mode (single-point or tap & drill), the form, internal or external,
 *   thread hand, starts, the size and the class (standard forms), the pitch or the TPI and the
 *   diameters (custom forms), the cutter (Sharp V, Full Form, Partial with insert presets and tip
 *   radius), the infeed mode and roughing pass count, Start (Z), the length L, the reliefs W₁ and
 *   W₂, and the tag.
 * - The live results: the pre-op and cut-to diameters with the class range, the three-wire check
 *   and live pitch diameter calculator, internal Go/No-Go gauge limits and boring bar
 *   recommendation, synchronization and safe spindle speed, the pass schedule table, and the tap,
 *   drill, clearance, and blind-hole calculator.
 * - All lengths are µm. The fields show the thread unit; a change of the unit shows the
 *   same lengths in the new unit.
 * - W₁ is 3 × the pitch until the user types a value.
 * - "Create" stores the thread (`Thread` of the workspace) and its points as sub-datums with the
 *   group kind `Thread`.
 * - All fields come back from the last thread of the workspace, or from a thread that the user
 *   selects: also Start (Z), the length, and the reliefs.
 *
 * The state uses no live position: only a change of the profile or of the workspace makes it
 * again. The `++x` text and the sync of Start (Z) read the live Z of the readout.
 */
class ThreadSetupPresenter(
    private val readout: TabReadout,
    private val services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(ThreadSetupUiState())

    /** The last frame with a new structure. Its readings can be old: read the live Z from [readout]. */
    private var frame: ReadoutFrame? = null
    private var threads: List<ThreadSpec> = emptyList()
    private var workspaceId: String? = null
    private var leftReliefTyped = false
    private var modeChosenByUser = false
    private var initialized = false

    /** The UI state of the screen. */
    val uiState: StateFlow<ThreadSetupUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            readout.frame.structure().collect { current ->
                frame = current
                if (current != null) {
                    val lathe = current.profile.machineType.isLathe
                    val nextMode = if (modeChosenByUser) {
                        if (lathe) mutable.value.mode else ThreadToolMode.TapAndDrill
                    } else {
                        if (lathe) ThreadToolMode.SinglePoint else ThreadToolMode.TapAndDrill
                    }
                    mutable.value = mutable.value.copy(isLathe = lathe, mode = nextMode)
                    refresh()
                }
            }
        }
        scope.launch {
            readout.frame.structure().map { current -> current?.workspace?.id }.distinctUntilChanged().collectLatest { id ->
                workspaceId = id
                if (id == null) return@collectLatest
                services.machines.threads(id).collect { stored ->
                    threads = stored.sortedBy { it.createdOn }
                    if (!initialized) {
                        initialized = true
                        val last = threads.lastOrNull()
                        if (last != null) restoreFrom(last) else start()
                    }
                    refresh()
                }
            }
        }
    }

    private fun start() {
        val form = ThreadForm.IsoMetricCoarse
        mutable.value = mutable.value.copy(
            form = form,
            internal = true,
            cutter = ThreadCutter.SharpV,
            startZ = LengthFieldState.of(0.0, true),
        )
        selectForm(form)
        updateAutoRelief()
    }

    // -----------------------------------------------------------------------------------------
    // The events

    /** Restores all fields from the stored thread [id]. */
    fun onRestore(id: String) {
        threads.firstOrNull { it.id == id }?.let(::restoreFrom)
        refresh()
    }

    fun onMode(mode: ThreadToolMode) {
        modeChosenByUser = true
        edit { copy(mode = mode) }
    }

    fun onTag(text: String) = edit { copy(tag = text) }

    fun onInternal(internal: Boolean) {
        edit { copy(internal = internal) }
        val state = mutable.value
        val classes = classesFor(state.form, internal)
        edit { copy(classes = classes, threadClass = classes.firstOrNull()) }
    }

    fun onLeftHand(leftHand: Boolean) = edit { copy(leftHand = leftHand) }

    fun onStarts(starts: Int) = edit { copy(starts = starts.coerceIn(1, 6)) }

    /** Selects the thread form. The fields change to the unit of the series. */
    fun onForm(form: ThreadForm) {
        val old = mutable.value
        selectForm(form)
        // The custom pitch changes between the pitch and the TPI: TPI = 25.4 / pitch (mm).
        if (old.form.isCustom && form.isCustom && old.form != form) {
            if (form == ThreadForm.CustomImperial) {
                val pitch = old.pitch.valueUm?.takeIf { it.isFinite() && it > 0.0 }
                edit { copy(tpi = NumberFieldState.of(pitch?.let { 25_400.0 / it })) }
            } else {
                val tpi = old.tpi.value?.takeIf { it.isFinite() && it > 0.0 }
                edit { copy(pitch = LengthFieldState.of(tpi?.let { 25_400.0 / it }, true)) }
            }
        }
        updateAutoRelief()
        refresh()
    }

    fun onSize(size: String) {
        edit { copy(size = size) }
        updateAutoRelief()
        refresh()
    }

    fun onClass(threadClass: ThreadClass) = edit { copy(threadClass = threadClass) }

    fun onCutter(cutter: ThreadCutter) = edit { copy(cutter = cutter) }

    fun onInsertPreset(preset: PartialInsertPreset) {
        val metric = mutable.value.unit == ThreadUnit.Millimeter
        edit {
            val updatedRadius = preset.tipRadiusUm?.let { LengthFieldState.of(it, metric) } ?: tipRadius
            copy(insertPreset = preset, tipRadius = updatedRadius)
        }
    }

    fun onInfeedMode(mode: ThreadInfeedMode) = edit { copy(infeedMode = mode) }

    /** A text of the field [field]. Start (Z) takes `++x` and `--x` with the current Z as the base. */
    fun onText(field: ThreadField, text: String) {
        edit {
            when (field) {
                ThreadField.Tag -> copy(tag = text)
                ThreadField.Pitch -> copy(pitch = pitch.withText(text))
                ThreadField.Tpi -> copy(tpi = tpi.withText(text))
                ThreadField.Major -> copy(major = major.withText(text))
                ThreadField.Minor -> copy(minor = minor.withText(text))
                ThreadField.TipRadius -> copy(
                    tipRadius = tipRadius.withText(text),
                    insertPreset = PartialInsertPreset.Custom,
                )
                ThreadField.StartZ -> copy(startZ = startZ.withText(text, currentZ()))
                ThreadField.Length -> copy(length = length.withText(text))
                ThreadField.LeftRelief -> copy(leftRelief = leftRelief.withText(text))
                ThreadField.RightRelief -> copy(rightRelief = rightRelief.withText(text))
                ThreadField.RoughingPasses -> copy(roughingPasses = roughingPasses.withText(text))
                ThreadField.CustomWireDiameter -> copy(wireDiameter = wireDiameter.withText(text))
                ThreadField.MeasuredOverWires -> copy(measuredOverWires = measuredOverWires.withText(text))
                ThreadField.TapEngagement -> copy(tapEngagement = tapEngagement.withText(text))
                ThreadField.BlindHoleDepth -> copy(blindHoleDepth = blindHoleDepth.withText(text))
            }
        }
        if (field == ThreadField.LeftRelief) leftReliefTyped = true
        if (field == ThreadField.Pitch || field == ThreadField.Tpi) updateAutoRelief()
        refresh()
    }

    /** The shown Z with the newest inputs, or 0 when Z has no value. */
    private fun currentZ(): Double = readout.latestFrame()?.reading(AxisId.Z)?.shown?.takeIf { it.isFinite() } ?: 0.0

    /**
     * The sync button of Start (Z): the field gets the current Z position. The sync needs a
     * `Connected` session: the function gives the refusal text, or null.
     */
    fun onSyncStartZ(): UiText? {
        readout.positionUnavailable()?.let { return it }
        val z = readout.latestFrame()?.reading(AxisId.Z)?.shown?.takeIf { it.isFinite() } ?: return null
        edit { copy(startZ = startZ.withValue(z)) }
        refresh()
        return null
    }

    /**
     * "Create": checks the inputs, stores the thread, and stores its
     * points as sub-datums with the group kind `Thread`. Gives the message of the result, or null
     * when the screen stays open with the problems.
     */
    suspend fun create(): UiText? {
        val state = mutable.value
        val workspace = workspaceId
        val input = input(state)
        val problems = ThreadSetup.validate(input, hasWorkspace = workspace != null)
        if (problems.isNotEmpty() || workspace == null) {
            fail(join(problems.map(::problemText)))
            return null
        }
        val now = services.wallClock.now()
        val tags = existingTags()
        val spec = ThreadSetup.toSpec(input, workspace, tags, now) ?: run {
            fail(UiText.of(Res.string.thrs_problem_no_points))
            return null
        }
        val stored = when (val result = services.machines.saveThread(workspace, spec)) {
            is StoreResult.Ok -> result.value
            is StoreResult.Refused -> {
                fail(UiText.of(Res.string.thrs_problem_no_workspace))
                return null
            }
        }
        val points = ThreadPoints.toSubDatums(stored, now)
        if (points.isEmpty() || services.machines.insertSubDatums(workspace, points) is StoreResult.Refused) {
            services.machines.deleteThread(stored.id)
            fail(UiText.of(Res.string.thrs_problem_no_points))
            return null
        }
        return UiText.of(Res.string.thrs_created, stored.tag, points.size)
    }

    // -----------------------------------------------------------------------------------------
    // Internal

    private fun edit(change: ThreadSetupUiState.() -> ThreadSetupUiState) {
        mutable.value = mutable.value.change().copy(error = null)
        refresh()
    }

    private fun fail(error: UiText?) {
        mutable.value = mutable.value.copy(error = error)
    }

    private fun selectForm(form: ThreadForm) {
        val unit = form.unit
        val metric = unit == ThreadUnit.Millimeter
        val state = mutable.value
        val sizes = ThreadTables.sizes(form).map { it.designation }
        val classes = classesFor(form, state.internal)
        mutable.value = state.copy(
            form = form,
            unit = unit,
            sizes = sizes,
            size = state.size?.takeIf { it in sizes } ?: sizes.firstOrNull(),
            classes = classes,
            threadClass = state.threadClass?.takeIf { it in classes } ?: classes.firstOrNull(),
            pitch = state.pitch.withUnit(metric),
            major = state.major.withUnit(metric),
            minor = state.minor.withUnit(metric),
            tipRadius = state.tipRadius.withUnit(metric),
            wireDiameter = state.wireDiameter.withUnit(metric),
            measuredOverWires = state.measuredOverWires.withUnit(metric),
            blindHoleDepth = state.blindHoleDepth.withUnit(metric),
            startZ = state.startZ.withUnit(metric),
            length = state.length.withUnit(metric),
            leftRelief = state.leftRelief.withUnit(metric),
            rightRelief = state.rightRelief.withUnit(metric),
        )
    }

    /** W₁ is 3 × the pitch until the user types a value. */
    private fun updateAutoRelief() {
        if (leftReliefTyped) return
        val pitch = ThreadSetup.pitchUm(input(mutable.value))?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val state = mutable.value
        mutable.value = state.copy(leftRelief = state.leftRelief.withValue(ThreadSetup.defaultLeftReliefUm(pitch)))
    }

    private fun restoreFrom(spec: ThreadSpec) {
        val restored = ThreadSetup.restore(spec)
        val unit = restored.form.unit
        val metric = unit == ThreadUnit.Millimeter
        leftReliefTyped = true
        val state = mutable.value.copy(form = restored.form, internal = restored.internal)
        mutable.value = state
        selectForm(restored.form)
        val classes = classesFor(restored.form, restored.internal)
        mutable.value = mutable.value.copy(
            restoredId = spec.id,
            tag = "",
            leftHand = restored.leftHand,
            starts = restored.starts,
            size = restored.size ?: mutable.value.size,
            classes = classes,
            threadClass = restored.threadClass?.takeIf { it in classes } ?: classes.firstOrNull(),
            cutter = restored.cutter,
            insertPreset = restored.insertPreset,
            infeedMode = restored.infeedMode,
            roughingPasses = NumberFieldState.of(restored.roughingPasses?.toDouble()),
            pitch = LengthFieldState.of(restored.customPitchUm.takeIf { restored.form == ThreadForm.CustomMetric }, metric),
            tpi = NumberFieldState.of(restored.customPitchUm?.takeIf { restored.form == ThreadForm.CustomImperial }?.let { 25_400.0 / it }),
            major = LengthFieldState.of(restored.customMajorDiameterUm, metric),
            minor = LengthFieldState.of(restored.customMinorDiameterUm, metric),
            tipRadius = LengthFieldState.of(restored.cutterTipRadiusUm, metric),
            wireDiameter = LengthFieldState.of(restored.wireDiameterUm, metric),
            measuredOverWires = LengthFieldState(metric = metric),
            startZ = LengthFieldState.of(restored.startZUm, metric),
            length = LengthFieldState.of(restored.lengthUm, metric),
            leftRelief = LengthFieldState.of(restored.leftReliefUm, metric),
            rightRelief = LengthFieldState.of(restored.rightReliefUm, metric),
            error = null,
        )
    }

    private fun existingTags(): List<String> =
        threads.map { it.tag } + frame?.workspace?.subDatums.orEmpty().flatMap { it.tags }

    /** The input of `core:functions` from the fields. An empty field is null; a refused text is NaN. */
    private fun input(state: ThreadSetupUiState): ThreadSetupInput = ThreadSetupInput(
        form = state.form,
        internal = state.internal,
        size = if (state.form.isCustom) null else state.size,
        threadClass = if (state.form.isCustom) null else state.threadClass,
        cutter = state.cutter,
        customPitchUm = when (state.form) {
            ThreadForm.CustomImperial -> state.tpi.value?.let { tpi -> if (tpi.isFinite() && tpi > 0.0) 25_400.0 / tpi else Double.NaN }
            ThreadForm.CustomMetric -> state.pitch.valueUm
            else -> null
        },
        customMajorDiameterUm = state.major.valueUm.takeIf { state.form.isCustom },
        customMinorDiameterUm = state.minor.valueUm.takeIf { state.form.isCustom },
        cutterTipRadiusUm = state.tipRadius.valueUm?.takeIf { state.cutter == ThreadCutter.Partial && it.isFinite() && it >= 0.0 },
        startZUm = state.startZ.valueUm,
        lengthUm = state.length.valueUm,
        leftReliefUm = state.leftRelief.valueUm,
        rightReliefUm = state.rightRelief.valueUm,
        tag = state.tag,
        leftHand = state.leftHand,
        starts = state.starts,
        infeedMode = state.infeedMode,
        roughingPasses = state.roughingPasses.value?.takeIf { it.isFinite() && it >= 1.0 }?.toInt(),
        wireDiameterUm = state.wireDiameter.valueUm?.takeIf { it.isFinite() && it > 0.0 },
        measuredOverWiresUm = state.measuredOverWires.valueUm?.takeIf { it.isFinite() && it > 0.0 },
        insertPreset = if (state.cutter == ThreadCutter.Partial) state.insertPreset else PartialInsertPreset.Custom,
    )

    private fun refresh() {
        val state = mutable.value
        val currentInput = input(state)
        val geometry = ThreadSetup.calculate(currentInput)
        val schedule = geometry?.let { geom ->
            ThreadPassSchedule.generate(
                form = geom.form,
                cutter = geom.cutter,
                internal = geom.internal,
                pitchUm = geom.pitchUm,
                preOpDiameterUm = geom.preOp.valueUm,
                cutToDiameterUm = geom.cutToDiameterUm,
                infeedMode = state.infeedMode,
                roughingPasses = currentInput.roughingPasses,
            )
        }
        val tapSummary = geometry?.let { geom ->
            val internalGeom = if (geom.internal) {
                geom
            } else {
                ThreadSetup.calculate(
                    currentInput.copy(
                        internal = true,
                        threadClass = classesFor(state.form, internal = true).firstOrNull(),
                    ),
                )
            }
            val minorLimits = internalGeom?.limits?.let { it.crestMinUm to it.crestMaxUm }
            val engagementPct = state.tapEngagement.value?.takeIf { it.isFinite() && it > 0.0 }
                ?: TapAndClearanceCalculator.DEFAULT_CUT_ENGAGEMENT_PERCENT
            val blindDepthUm = state.blindHoleDepth.valueUm?.takeIf { it.isFinite() && it > 0.0 }
            TapAndClearanceCalculator.summary(
                majorDiameterUm = geom.nominalDiameterUm,
                pitchUm = geom.pitchUm,
                includedAngleDeg = geom.form.includedAngleDeg,
                cutEngagementPercent = engagementPct,
                metric = state.unit == ThreadUnit.Millimeter,
                minorLimitsUm = minorLimits,
                fullThreadDepthUm = blindDepthUm,
            )
        }
        mutable.value = state.copy(
            loaded = frame != null && initialized,
            threads = threads.reversed().map { ThreadChoice(it.id, it.tag, it.size) },
            defaultTag = ThreadSetup.defaultTag(existingTags()),
            leftReliefLabel = reliefText(ThreadSetup.reliefLabel(state.internal, w1 = true)),
            rightReliefLabel = reliefText(ThreadSetup.reliefLabel(state.internal, w1 = false)),
            results = geometry?.let { results(it, schedule, state) },
            passSchedule = schedule,
            tapSummary = tapSummary,
            tapResults = if (geometry != null && tapSummary != null) {
                tapResults(geometry, tapSummary, state.unit)
            } else {
                null
            },
        )
    }

    private fun results(
        geometry: ThreadGeometry,
        schedule: PassSchedule?,
        state: ThreadSetupUiState,
    ): ThreadResults {
        val unit = state.unit
        fun text(um: Double) = ThreadText.length(um, unit)
        val wire = geometry.threeWire
        val gauges = geometry.internalGaugeLimits
        val bar = geometry.internalBarRecommendation
        val profile = frame?.profile
        val dialRule = ThreadSyncRules.dialRule(
            leadUm = geometry.leadUm,
            threadMetric = unit == ThreadUnit.Millimeter,
            leadscrewPitchUm = profile?.leadscrewPitchUm,
            leadscrewMetric = profile?.metricDefault ?: false,
        )
        val exitReliefUm = if (geometry.leftHand) state.rightRelief.valueUm else state.leftRelief.valueUm
        val safeSpeed = ThreadSyncRules.safeSpeed(
            leadUm = geometry.leadUm,
            pitchUm = geometry.pitchUm,
            exitReliefUm = exitReliefUm,
            internal = geometry.internal,
        )
        val presetWarning = state.cutter == ThreadCutter.Partial &&
            state.insertPreset != PartialInsertPreset.Custom &&
            !state.insertPreset.validForPitch(geometry.pitchUm)
        val passRows = schedule?.passes.orEmpty().map { pass ->
            PassRowUiState(
                number = pass.number,
                kind = pass.kind,
                stepRadial = text(pass.stepRadialUm),
                cumulativeRadial = text(pass.cumulativeRadialUm),
                targetDiameter = text(pass.targetDiameterUm),
                compoundAdvance = text(pass.compoundStepUm),
                zShift = text(pass.compoundZShiftUm),
            )
        }
        return ThreadResults(
            preOpLabel = UiText.of(if (geometry.internal) Res.string.thrs_label_bore_to else Res.string.thrs_label_turn_to),
            preOp = text(geometry.preOp.valueUm),
            preOpRange = UiText.of(Res.string.thrs_range, text(geometry.preOp.minUm), text(geometry.preOp.maxUm)),
            cutToLabel = UiText.of(if (geometry.internal) Res.string.thrs_label_cut_to_major else Res.string.thrs_label_cut_to_minor),
            cutTo = text(geometry.cutToDiameterUm),
            minor = text(geometry.minorDiameterUm),
            major = text(geometry.majorDiameterUm),
            pitchDiameter = text(geometry.targetPitchDiameterUm),
            height = text(geometry.heightUm),
            noTolerance = geometry.showsNoToleranceNotice,
            wireDiameter = wire?.let { text(it.wireDiameterUm) },
            wireMeasurement = wire?.let { text(it.measurementUm) },
            wireRange = wire?.let { values ->
                val min = values.measurementMinUm
                val max = values.measurementMaxUm
                if (min != null && max != null) UiText.of(Res.string.thrs_range, text(min), text(max)) else null
            },
            starts = geometry.starts,
            lead = text(geometry.leadUm),
            leadAngleDeg = "${formatTrimmed(geometry.leadAngleArcSec / 3600.0, 2)}°",
            needsAnvilTilt = geometry.needsAnvilTilt,
            reliefDiameter = text(geometry.reliefDiameterUm),
            entryChamferDiameter = text(geometry.entryChamferDiameterUm),
            actualPitchDiameter = wire?.actualPitchDiameterUm?.let(::text),
            remainingRadialDepth = wire?.remainingRadialDepthUm?.let(::text),
            wireValid = wire?.wireValid ?: true,
            insertPresetPitchWarning = presetWarning,
            minorGo = gauges?.let { text(it.minorGoUm) },
            minorNoGo = gauges?.let { text(it.minorNoGoUm) },
            pitchGo = gauges?.let { text(it.pitchGoUm) },
            pitchNoGo = gauges?.let { text(it.pitchNoGoUm) },
            internalBarHolder = bar?.holder?.designation,
            internalBarMinBore = bar?.holder?.let { text(it.minBoreUm) },
            internalBarMaxDepth = bar?.takeIf { it.holder != null }?.let { text(it.maxSafeDepthUm) },
            internalBarTooSmall = geometry.internal && bar != null && bar.holder == null,
            internalBarDeflectionRisk = bar?.deflectionRisk ?: false,
            dialRule = dialRule,
            safeMaxRpm = safeSpeed.maxRpm,
            scheduleInfeedAngleDeg = schedule?.let { formatTrimmed(it.infeedAngleDeg, 1) }.orEmpty(),
            scheduleTotalRadialDepth = schedule?.let { text(it.totalRadialDepthUm) }.orEmpty(),
            schedulePasses = passRows,
        )
    }

    private fun tapResults(
        geometry: ThreadGeometry,
        summary: TapAndHolePrepSummary,
        unit: ThreadUnit,
    ): TapDrillResultsUiState {
        fun text(um: Double) = ThreadText.length(um, unit)
        fun drillRow(drill: StandardDrill, engagementPct: Double): DrillSeriesRowUiState =
            DrillSeriesRowUiState(
                series = drill.series,
                label = drill.label,
                diameter = text(drill.diameterUm),
                actualEngagementPercent = "${formatTrimmed(engagementPct, 0)}%",
            )

        val cutClosest = drillRow(summary.cutTap.nearestDrills.closest, summary.cutTap.closestActualEngagementPercent)
        val seriesDrills = DrillSeries.entries.mapNotNull { series ->
            val nearest = StandardDrillTables.nearestInSeries(summary.cutTap.theoreticalDiameterUm, series) ?: return@mapNotNull null
            val drill = nearest.closest
            val pct = TapAndClearanceCalculator.actualCutTapEngagementPercent(
                majorDiameterUm = geometry.nominalDiameterUm,
                pitchUm = geometry.pitchUm,
                drillDiameterUm = drill.diameterUm,
                includedAngleDeg = geometry.form.includedAngleDeg,
            )
            drillRow(drill, pct)
        }
        val formTap = summary.formTap
        val blind = summary.blindHole
        return TapDrillResultsUiState(
            cutTheoreticalDiameter = text(summary.cutTap.theoreticalDiameterUm),
            cutClosestDrill = cutClosest,
            cutSeriesDrills = seriesDrills,
            cutWithinMinorLimits = summary.cutTap.closestWithinMinorLimits,
            formTheoreticalDiameter = formTap?.let { text(it.theoreticalDiameterUm) },
            formClosestDrill = formTap?.let { drillRow(it.nearestDrills.closest, it.closestActualEngagementPercent) },
            closeClearance = text(summary.clearance.closeFitUm),
            normalClearance = text(summary.clearance.normalFitUm),
            looseClearance = text(summary.clearance.looseFitUm),
            countersink90 = text(summary.clearance.countersink90DiameterUm),
            blindBottomingDepth = blind?.let { text(it.bottomingMinDrillDepthUm) },
            blindPlugDepth = blind?.let { text(it.plugMinDrillDepthUm) },
            blindTaperDepth = blind?.let { text(it.taperMinDrillDepthUm) },
        )
    }

    companion object {
        /** Gives the text of a relief label. */
        fun reliefText(label: ReliefLabel): UiText = UiText.of(
            when (label) {
                ReliefLabel.Bottom -> Res.string.thrs_relief_bottom
                ReliefLabel.Mouth -> Res.string.thrs_relief_mouth
                ReliefLabel.Shoulder -> Res.string.thrs_relief_shoulder
                ReliefLabel.Face -> Res.string.thrs_relief_face
            },
        )

        /** Gives the text of a problem of "Create". */
        fun problemText(problem: ThreadSetupProblem): UiText = when (problem) {
            ThreadSetupProblem.NoWorkspace -> UiText.of(Res.string.thrs_problem_no_workspace)
            ThreadSetupProblem.StartZRequired -> UiText.of(Res.string.thrs_problem_start_z_required)
            ThreadSetupProblem.StartZNotANumber -> UiText.of(Res.string.thrs_problem_start_z_nan)
            ThreadSetupProblem.LengthNotPositive -> UiText.of(Res.string.thrs_problem_length)
            ThreadSetupProblem.PitchNotPositive -> UiText.of(Res.string.thrs_problem_pitch)
            ThreadSetupProblem.TpiNotPositive -> UiText.of(Res.string.thrs_problem_tpi)
            ThreadSetupProblem.MinorNotPositive -> UiText.of(Res.string.thrs_problem_minor)
            ThreadSetupProblem.MajorNotPositive -> UiText.of(Res.string.thrs_problem_major)
            ThreadSetupProblem.MinorNotSmallerThanMajor -> UiText.of(Res.string.thrs_problem_minor_major)
            is ThreadSetupProblem.ReliefInvalid -> UiText.of(Res.string.thrs_problem_relief, reliefText(problem.label))
            ThreadSetupProblem.ReliefsTooLong -> UiText.of(Res.string.thrs_problem_reliefs_too_long)
            ThreadSetupProblem.NoSize -> UiText.of(Res.string.thrs_problem_no_size)
        }

        /** Joins [texts] with a space, or gives null for no text. */
        fun join(texts: List<UiText>): UiText? = texts.reduceOrNull { joined, next -> UiText.of(Res.string.thrs_join, joined, next) }

        private fun formatFixed(value: Double, decimals: Int): String {
            if (value.isNaN()) return "NaN"
            val scale = 10.0.pow(decimals)
            val scaled = floor(abs(value) * scale + 0.5).toLong()
            val negative = value < 0 && scaled != 0L
            val whole = scaled / scale.toLong()
            val fraction = scaled % scale.toLong()
            val text = if (decimals == 0) {
                whole.toString()
            } else {
                whole.toString() + "." + fraction.toString().padStart(decimals, '0')
            }
            return if (negative) "-$text" else text
        }

        private fun formatTrimmed(value: Double, maxDecimals: Int): String {
            val text = formatFixed(value, maxDecimals)
            return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
        }
    }
}
