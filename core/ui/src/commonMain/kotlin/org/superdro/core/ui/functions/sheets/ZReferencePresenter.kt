package org.superdro.core.ui.functions.sheets

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.superdro.core.connection.SessionState
import org.superdro.core.functions.zreference.ZReference
import org.superdro.core.functions.zreference.ZReferenceResult
import org.superdro.core.model.AxisId
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.resources.Res
import org.superdro.core.resources.fns_undo_z_reference
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.LengthFieldState
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.text.UiText
import org.superdro.core.ui.text.toUiText

/**
 * The state of the Z reference sheet.
 *
 * @property setterHeight A: the height of the tool setter, more than 0.
 * @property desiredZ B: the Z value of the reference surface. An empty field is 0.
 * @property block The reason that "Apply" is disabled: "Z readout must be enabled" or "The
 *   adapter must be connected", or null.
 * @property error The error of the last "Apply", or null.
 */
@Immutable
data class ZReferenceUiState(
    val loaded: Boolean = false,
    val zLabel: String = "Z",
    val setterHeight: LengthFieldState = LengthFieldState(),
    val desiredZ: LengthFieldState = LengthFieldState(),
    val block: UiText? = null,
    val error: UiText? = null,
)

/**
 * The presenter of the Z reference sheet in the manual mode:
 *
 * 1. A is required and must be more than 0 ("Tool setter height must be a positive number").
 * 2. B is 0 when empty.
 * 3. `A_µm = A × (1000 if mm, else 25400)`, `B_µm` the same (each field has its own unit).
 * 4. `h(A_µm + B_µm)` on Z, and Z changes to the absolute mode.
 * 5. Store A (µm) and its unit in the profile.
 *
 * "Apply" is disabled when Z is not enabled or the session is not `Connected`. The automatic mode
 * is a part of the graphics area.
 *
 * The state uses no live position: only a change of the profile, the workspace, or the session
 * makes it again. "Apply" reads the live frame of the readout.
 */
class ZReferencePresenter(
    private val readout: TabReadout,
    private val services: FunctionServices,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(ZReferenceUiState())

    /** The last frame with a new structure. Its readings can be old. */
    private var frame: ReadoutFrame? = null
    private var session: SessionState? = null
    private var initialized = false

    /** The UI state of the sheet. */
    val uiState: StateFlow<ZReferenceUiState> = mutable.asStateFlow()

    init {
        scope.launch {
            combine(readout.frame.structure(), readout.sessionState) { current, state -> current to state }.collect { (current, state) ->
                frame = current
                session = state
                if (current == null) return@collect
                if (!initialized) {
                    initialized = true
                    val profile = current.profile
                    val metric = profile.metricDefault
                    mutable.value = mutable.value.copy(
                        setterHeight = LengthFieldState.of(profile.zSetterHeightUm.takeIf { it > 0.0 }, profile.zSetterMetric ?: metric),
                        desiredZ = LengthFieldState(metric = metric),
                    )
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        val current = frame ?: return
        mutable.value = mutable.value.copy(
            loaded = true,
            zLabel = ReadoutPresenter.labelOf(current.profile, AxisId.Z),
            block = ZReference.block(current.profile.z.enabled, session is SessionState.Connected)?.toUiText(),
        )
    }

    fun onSetterHeight(text: String) {
        mutable.value = mutable.value.copy(setterHeight = mutable.value.setterHeight.withText(text), error = null)
    }

    fun onSetterHeightUnit(metric: Boolean) {
        mutable.value = mutable.value.copy(setterHeight = mutable.value.setterHeight.withUnit(metric))
    }

    fun onDesiredZ(text: String) {
        mutable.value = mutable.value.copy(desiredZ = mutable.value.desiredZ.withText(text), error = null)
    }

    fun onDesiredZUnit(metric: Boolean) {
        mutable.value = mutable.value.copy(desiredZ = mutable.value.desiredZ.withUnit(metric))
    }

    /**
     * "Apply": sets Z to A + B in the absolute mode, with the undo, and stores A and its
     * unit in the profile. Gives true when the sheet can close. The function returns after the
     * write of the profile, so the write ends before the sheet closes.
     */
    suspend fun onApply(): Boolean {
        if (frame == null) return false
        val current = readout.frame.value ?: return false
        val state = mutable.value
        val desired = state.desiredZ.valueUm
        if (desired != null && desired.isNaN()) {
            mutable.value = state.copy(error = state.desiredZ.error)
            return false
        }
        val result = ZReference.apply(
            setterHeightUm = state.setterHeight.valueUm,
            desiredZUm = desired,
            zEnabled = current.profile.z.enabled,
            connected = session is SessionState.Connected,
        )
        when (result) {
            is ZReferenceResult.Failure -> {
                mutable.value = state.copy(error = result.message.toUiText())
                return false
            }
            is ZReferenceResult.Applied -> {
                readout.apply(
                    AxisOperations.setAxis(current, AxisId.Z, result.zAbsoluteUm),
                    UiText.of(Res.string.fns_undo_z_reference, ReadoutPresenter.labelOf(current.profile, AxisId.Z)),
                )
                val height = result.setterHeightUm
                if (height != null) {
                    val metric = state.setterHeight.metric
                    services.machines.updateProfile(current.profile.id) { profile -> ZReference.storeSetterHeight(profile, height, metric) }
                }
                return true
            }
        }
    }
}
