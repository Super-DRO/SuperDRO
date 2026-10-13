package org.superdro.core.ui.functions.sheets

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.superdro.core.model.AuxiliaryType
import org.superdro.core.model.Channels
import org.superdro.core.model.CompoundProjection
import org.superdro.core.model.HeadTilt
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.input.CompoundProjectionWeights
import org.superdro.core.readout.input.HeadTiltWeights
import org.superdro.core.resources.Res
import org.superdro.core.resources.fns_angle_range
import org.superdro.core.resources.fns_angle_required
import org.superdro.core.resources.fns_aux_compound
import org.superdro.core.resources.fns_aux_other
import org.superdro.core.resources.fns_aux_tailstock
import org.superdro.core.resources.fns_compound_no_vectoring
import org.superdro.core.resources.fns_compound_not_compound
import org.superdro.core.resources.fns_compound_reference_0
import org.superdro.core.resources.fns_compound_reference_180
import org.superdro.core.resources.fns_compound_reference_90
import org.superdro.core.resources.fns_compound_reference_other
import org.superdro.core.resources.fns_compound_shared_input
import org.superdro.core.resources.fns_head_tilt_no_quill
import org.superdro.core.resources.fns_head_tilt_not_mill
import org.superdro.core.resources.fns_head_tilt_shared_input
import org.superdro.core.resources.fns_head_tilt_too_far
import org.superdro.core.resources.fns_undo_compound
import org.superdro.core.resources.fns_undo_compound_clear
import org.superdro.core.resources.fns_undo_head_tilt
import org.superdro.core.resources.fns_undo_head_tilt_clear
import org.superdro.core.ui.functions.common.FieldMath
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.NumberFieldState
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText

/** The rules of the projection sheets. */
object ProjectionRules {

    /** The compound angle limit: −360° to 360°. */
    const val COMPOUND_LIMIT_DEGREES: Double = 360.0

    /** The tilt and nod angle limit: −90° to 90°. */
    const val HEAD_LIMIT_DEGREES: Double = 90.0

    /**
     * Gives the reason that the compound projection has no effect, or null: the auxiliary readout
     * type must be the compound slide, "Enable Vectoring"
     * must be on, and X and Z must not use the compound input.
     */
    fun compoundProblem(profile: MachineProfile): UiText? {
        if (profile.auxiliary.type != AuxiliaryType.CompoundSlide) return UiText.of(Res.string.fns_compound_not_compound)
        if (!profile.auxiliary.vectoring) return UiText.of(Res.string.fns_compound_no_vectoring)
        val letter = Channels.normalize(profile.y.primary.channel)
        val shared = listOfNotNull(profile.x.primary, profile.x.secondary, profile.z.primary, profile.z.secondary)
            .any { input -> Channels.normalize(input.channel) == letter }
        return if (shared) UiText.of(Res.string.fns_compound_shared_input, letter.toString()) else null
    }

    /**
     * Gives the reason that the head tilt projection has no effect, or null: a vertical mill, a
     * quill (the secondary input of Z), and a quill input that X, Y,
     * and the Z primary input do not use.
     */
    fun headTiltProblem(profile: MachineProfile): UiText? {
        if (profile.machineType != MachineType.VerticalMill) return UiText.of(Res.string.fns_head_tilt_not_mill)
        val quill = profile.z.secondary ?: return UiText.of(Res.string.fns_head_tilt_no_quill)
        val letter = Channels.normalize(quill.channel)
        val shared = listOfNotNull(profile.x.primary, profile.x.secondary, profile.y.primary, profile.y.secondary, profile.z.primary)
            .any { input -> Channels.normalize(input.channel) == letter }
        return if (shared) UiText.of(Res.string.fns_head_tilt_shared_input, letter.toString()) else null
    }

    /** The text of the auxiliary readout type. */
    fun auxiliaryType(type: AuxiliaryType): UiText = UiText.of(
        when (type) {
            AuxiliaryType.Tailstock -> Res.string.fns_aux_tailstock
            AuxiliaryType.CompoundSlide -> Res.string.fns_aux_compound
            AuxiliaryType.Other -> Res.string.fns_aux_other
        },
    )

    /** The text of the compound zero position `y_angle_reference`. */
    fun reference(degrees: Int): UiText = when (degrees) {
        0 -> UiText.of(Res.string.fns_compound_reference_0)
        90 -> UiText.of(Res.string.fns_compound_reference_90)
        180 -> UiText.of(Res.string.fns_compound_reference_180)
        else -> UiText.of(Res.string.fns_compound_reference_other, degrees)
    }

    /** Checks an angle field with the limit ±[limit] degrees. Gives the error, or null. */
    fun angleError(field: NumberFieldState, limit: Double): UiText? {
        val value = field.value
        return when {
            field.error != null -> field.error
            value == null -> UiText.of(Res.string.fns_angle_required)
            abs(value) > limit -> UiText.of(Res.string.fns_angle_range, FieldMath.format(limit))
            else -> null
        }
    }

    /** The text of a weight with 5 decimals, for example 0.86603. */
    fun weight(value: Double?): String = if (value == null) "—" else FieldMath.format(value, 5).let { text ->
        val dot = text.indexOf('.')
        if (dot < 0) "$text.00000" else text.padEnd(dot + 6, '0')
    }
}

/**
 * The state of the compound slide sheet.
 *
 * @property problem The reason that the projection has no effect, or null. "Apply" is then disabled.
 * @property xWeight The weight of the compound input on X with the current angle: `sin(a_eff)`.
 * @property zWeight The weight on Z: `cos(a_eff)`.
 */
@Immutable
data class CompoundUiState(
    val loaded: Boolean = false,
    val angle: NumberFieldState = NumberFieldState(),
    val applyX: Boolean = true,
    val applyZ: Boolean = true,
    val active: Boolean = false,
    val auxiliaryType: UiText? = null,
    val reference: UiText? = null,
    val problem: UiText? = null,
    val xWeight: String = "—",
    val zWeight: String = "—",
    val error: UiText? = null,
)

/**
 * The presenter of the compound slide sheet "Compound Slide Angle Compensation": the angle of the
 * compound slide (−360° to 360°, each angle
 * has its effect), "Apply to Cross Slide", and "Apply to Carriage". The workspace keeps the
 * projection. The readout shows the warning state while the projection is on.
 */
class CompoundPresenter(
    private val readout: TabReadout,
    services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(CompoundUiState())
    private var frame: ReadoutFrame? = null
    private var initialized = false

    /** The UI state of the sheet. */
    val uiState: StateFlow<CompoundUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            readout.frame.collect { current ->
                frame = current
                if (current == null) return@collect
                if (!initialized) {
                    initialized = true
                    val stored = current.workspace.compoundProjection
                    mutable.value = mutable.value.copy(
                        angle = NumberFieldState.ofArcSec(stored.angleArcSec),
                        applyX = stored.applyX,
                        applyZ = stored.applyZ,
                    )
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        val current = frame ?: return
        val state = mutable.value
        val profile = current.profile
        val reference = profile.auxiliary.angleReferenceDegrees.toDouble()
        val trial = CompoundProjection(active = true, angleArcSec = state.angle.arcSec ?: 0.0, applyX = state.applyX, applyZ = state.applyZ)
        val valid = ProjectionRules.angleError(state.angle, ProjectionRules.COMPOUND_LIMIT_DEGREES) == null
        mutable.value = state.copy(
            loaded = true,
            active = current.workspace.compoundProjection.active,
            auxiliaryType = ProjectionRules.auxiliaryType(profile.auxiliary.type),
            reference = ProjectionRules.reference(profile.auxiliary.angleReferenceDegrees),
            problem = ProjectionRules.compoundProblem(profile),
            xWeight = if (valid) ProjectionRules.weight(CompoundProjectionWeights.xWeight(trial, reference)) else "—",
            zWeight = if (valid) ProjectionRules.weight(CompoundProjectionWeights.zWeight(trial, reference)) else "—",
        )
    }

    fun onAngle(text: String) {
        mutable.value = mutable.value.copy(angle = mutable.value.angle.withText(text), error = null)
        refresh()
    }

    fun onApplyX(on: Boolean) {
        mutable.value = mutable.value.copy(applyX = on)
        refresh()
    }

    fun onApplyZ(on: Boolean) {
        mutable.value = mutable.value.copy(applyZ = on)
        refresh()
    }

    /** "Apply Compensation". Gives true when the sheet can close. */
    fun onApply(): Boolean {
        val state = mutable.value
        if (frame == null || state.problem != null) return false
        ProjectionRules.angleError(state.angle, ProjectionRules.COMPOUND_LIMIT_DEGREES)?.let { error ->
            mutable.value = state.copy(error = error)
            return false
        }
        val projection = CompoundProjection(active = true, angleArcSec = state.angle.arcSec ?: 0.0, applyX = state.applyX, applyZ = state.applyZ)
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_compound)) { workspace -> workspace.copy(compoundProjection = projection) }
        return true
    }

    /** "Clear Compensation": the projection goes off. The angle and the switches stay stored. */
    fun onClear(): Boolean {
        if (frame == null) return false
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_compound_clear)) { workspace ->
            workspace.copy(compoundProjection = workspace.compoundProjection.copy(active = false))
        }
        return true
    }
}

/**
 * The state of the head tilt sheet.
 *
 * @property xWeight `−sin α`, [yWeight] `−sin β`, [zWeight] `sqrt(max(0, 1 − sin²α − sin²β))`.
 */
@Immutable
data class HeadTiltUiState(
    val loaded: Boolean = false,
    val tilt: NumberFieldState = NumberFieldState(),
    val nod: NumberFieldState = NumberFieldState(),
    val applyX: Boolean = true,
    val applyY: Boolean = true,
    val active: Boolean = false,
    val problem: UiText? = null,
    val xWeight: String = "—",
    val yWeight: String = "—",
    val zWeight: String = "—",
    val error: UiText? = null,
)

/**
 * The presenter of the head tilt sheet "Head Angle Compensation": the tilt angle α toward X and the nod angle β toward Y (−90° to 90°),
 * "Apply to X", and "Apply to Y". The quill input adds to X with `−sin α`, to Y with `−sin β`, and
 * to Z with `sqrt(max(0, 1 − sin²α − sin²β))`. The sheet refuses `sin²α + sin²β > 1`.
 */
class HeadTiltPresenter(
    private val readout: TabReadout,
    services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(HeadTiltUiState())
    private var frame: ReadoutFrame? = null
    private var initialized = false

    /** The UI state of the sheet. */
    val uiState: StateFlow<HeadTiltUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            readout.frame.collect { current ->
                frame = current
                if (current == null) return@collect
                if (!initialized) {
                    initialized = true
                    val stored = current.workspace.headTilt
                    mutable.value = mutable.value.copy(
                        tilt = NumberFieldState.ofArcSec(stored.tiltArcSec),
                        nod = NumberFieldState.ofArcSec(stored.nodArcSec),
                        applyX = stored.applyX,
                        applyY = stored.applyY,
                    )
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        val current = frame ?: return
        val state = mutable.value
        val valid = ProjectionRules.angleError(state.tilt, ProjectionRules.HEAD_LIMIT_DEGREES) == null &&
            ProjectionRules.angleError(state.nod, ProjectionRules.HEAD_LIMIT_DEGREES) == null
        val trial = HeadTilt(active = true, tiltArcSec = state.tilt.arcSec ?: 0.0, nodArcSec = state.nod.arcSec ?: 0.0, applyX = state.applyX, applyY = state.applyY)
        val tooFar = valid && HeadTiltWeights.tooFar(trial.tiltArcSec, trial.nodArcSec)
        mutable.value = state.copy(
            loaded = true,
            active = current.workspace.headTilt.active,
            problem = ProjectionRules.headTiltProblem(current.profile),
            xWeight = if (valid && !tooFar) ProjectionRules.weight(HeadTiltWeights.xWeight(trial)) else "—",
            yWeight = if (valid && !tooFar) ProjectionRules.weight(HeadTiltWeights.yWeight(trial)) else "—",
            zWeight = if (valid && !tooFar) ProjectionRules.weight(HeadTiltWeights.quillWeight(trial)) else "—",
        )
    }

    fun onTilt(text: String) {
        mutable.value = mutable.value.copy(tilt = mutable.value.tilt.withText(text), error = null)
        refresh()
    }

    fun onNod(text: String) {
        mutable.value = mutable.value.copy(nod = mutable.value.nod.withText(text), error = null)
        refresh()
    }

    fun onApplyX(on: Boolean) {
        mutable.value = mutable.value.copy(applyX = on)
        refresh()
    }

    fun onApplyY(on: Boolean) {
        mutable.value = mutable.value.copy(applyY = on)
        refresh()
    }

    /** "Apply Compensation". Gives true when the sheet can close. */
    fun onApply(): Boolean {
        val state = mutable.value
        if (frame == null || state.problem != null) return false
        val error = ProjectionRules.angleError(state.tilt, ProjectionRules.HEAD_LIMIT_DEGREES)
            ?: ProjectionRules.angleError(state.nod, ProjectionRules.HEAD_LIMIT_DEGREES)
        if (error != null) {
            mutable.value = state.copy(error = error)
            return false
        }
        val tilt = state.tilt.arcSec ?: 0.0
        val nod = state.nod.arcSec ?: 0.0
        if (HeadTiltWeights.tooFar(tilt, nod)) {
            mutable.value = state.copy(error = UiText.of(Res.string.fns_head_tilt_too_far))
            return false
        }
        val projection = HeadTilt(active = true, tiltArcSec = tilt, nodArcSec = nod, applyX = state.applyX, applyY = state.applyY)
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_head_tilt)) { workspace -> workspace.copy(headTilt = projection) }
        return true
    }

    /** "Clear Compensation": the projection goes off; the quill weight on Z goes back to 1.0. */
    fun onClear(): Boolean {
        if (frame == null) return false
        readout.applyWorkspace(UiText.of(Res.string.fns_undo_head_tilt_clear)) { workspace ->
            workspace.copy(headTilt = workspace.headTilt.copy(active = false))
        }
        return true
    }
}
