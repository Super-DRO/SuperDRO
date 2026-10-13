package org.superdro.core.ui.dataoutput

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.superdro.core.dataoutput.DataOutputStatus
import org.superdro.core.dataoutput.OutputRunRule
import org.superdro.core.model.DataOutputActivation
import org.superdro.core.model.DataOutputConfig
import org.superdro.core.store.repository.MachineRepository
import org.superdro.core.ui.presenter.PresenterContext
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.tabs.RequestSender

/**
 * The state of the status chip of the data output in the status strip of the readout.
 *
 * @property visible True when the user turned on the output of the machine of the tab.
 * @property openDetails True after a tap that opens the diagnostics, until the entry opens the data
 *   output screen with the diagnostics ([DataOutputChipPresenter.detailsOpened]).
 */
@Immutable
data class DataOutputChipUiState(
    val visible: Boolean = false,
    val status: DataOutputStatus = DataOutputStatus.Stopped,
    val running: Boolean = false,
    val activation: DataOutputActivation = DataOutputActivation.OnDemand,
    val openDetails: Boolean = false,
)

/**
 * The presenter of the status chip of the data output of one tab. The tab owns it.
 *
 * - The chip shows only when the output of the machine is on.
 * - A tap with the activation "On demand" starts or stops the output. The start asks for the local
 *   network permission.
 * - A tap in the warning state, or with the activation "Always on", opens the diagnostics: the
 *   data output screen with the diagnostics open in its status section. The diagnostics are
 *   general information, so they do not show in a dialog. The switch and the start
 *   and stop button of that section stop the output.
 */
class DataOutputChipPresenter(
    private val profileId: String,
    private val runner: DataOutputRunner,
    private val machines: MachineRepository,
    private val requests: RequestSender,
    context: PresenterContext,
) : ScopedPresenter(context) {

    private val _uiState = MutableStateFlow(DataOutputChipUiState())

    /** The state of the chip. */
    val uiState: StateFlow<DataOutputChipUiState> = _uiState.asStateFlow()

    private var config: DataOutputConfig = DataOutputConfig()

    init {
        scope.launch {
            combine(runner.state(profileId), machines.profile(profileId)) { state, profile -> state to profile?.dataOutput }
                .collect { (state, stored) ->
                    config = stored ?: DataOutputConfig()
                    _uiState.update { current ->
                        current.copy(
                            visible = config.enabled,
                            status = state?.status ?: DataOutputStatus.Stopped,
                            running = state?.running == true,
                            activation = config.activation,
                        )
                    }
                }
        }
    }

    /** A tap on the chip. */
    fun onTap() {
        val state = _uiState.value
        when {
            state.status == DataOutputStatus.Warning || state.activation == DataOutputActivation.AlwaysOn ->
                _uiState.update { current -> current.copy(openDetails = true) }
            state.running -> runner.requestStop(profileId)
            else -> {
                OutputRunRule.localNetworkRequest(config)?.let(requests::send)
                runner.requestStart(profileId)
            }
        }
    }

    /** The entry opened the data output screen with the diagnostics. */
    fun detailsOpened() = _uiState.update { state -> state.copy(openDetails = false) }
}
