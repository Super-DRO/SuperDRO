package org.superdro.core.ui.readout.details

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.readout.FeedValues
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.ReadoutPatterns
import org.superdro.core.readout.UnitConverter
import org.superdro.core.ui.graphics.proximity.ProximityTargets
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.ReadoutPresenter

/**
 * The feed values of the linear axis details as texts in
 * the unit of the axis.
 *
 * @property rate The feed rate in the axis unit for each minute, for example "300.000".
 * @property chipLoad The chip load in the axis unit for each tooth, for example "0.063".
 * @property thread The thread pitch in mm ("0.25") or the threads per inch ("101.6"), or null
 *   when the axis shows no thread (only the Z axis of a lathe).
 * @property metric True for mm, false for inch.
 */
@Immutable
data class FeedTexts(
    val rate: String = "",
    val chipLoad: String = "",
    val thread: String? = null,
    val metric: Boolean = true,
) {
    companion object {
        /**
         * Gives the texts of the feed rate [rateUmPerMinute] (µm/min) at [rpm] with [teeth] teeth.
         *
         * Example: 300000 µm/min at 1200 RPM with 4 teeth gives "300.000" mm/min and "0.063"
         * mm/tooth, or "11.8110" in/min and "0.0025" in/tooth. The thread is "0.25" mm or "101.6" TPI.
         */
        fun of(
            rateUmPerMinute: Double,
            rpm: Double,
            teeth: Int,
            metric: Boolean,
            format: ReadoutFormat,
            showsThread: Boolean,
        ): FeedTexts {
            val pattern = ReadoutPatterns.linear(format, metric)
            val converter = UnitConverter.linear(metric)
            val fpr = FeedValues.feedPerRevolution(rateUmPerMinute, rpm)
            val chip = FeedValues.chipLoad(fpr, teeth)
            val thread = if (!showsThread) null else if (metric) FeedValues.threadPitchText(fpr) else FeedValues.threadsPerInchText(fpr)
            return FeedTexts(
                rate = pattern.format(converter.toDisplay(rateUmPerMinute)),
                chipLoad = pattern.format(converter.toDisplay(chip)),
                thread = thread,
                metric = metric,
            )
        }

        /** Gives the texts of [axis] of [frame] for the feed rate [rateUmPerMinute]. */
        fun of(frame: ReadoutFrame, axis: AxisId, rateUmPerMinute: Double): FeedTexts {
            val profile = frame.profile
            val rpm = if (profile.tachometer.enabled) frame.reading(AxisId.Tachometer).shown else 0.0
            val teeth = FeedValues.teeth(profile.machineType, frame.workspace.toothCount)
            return of(
                rateUmPerMinute = rateUmPerMinute,
                rpm = if (rpm.isFinite()) rpm else 0.0,
                teeth = teeth,
                metric = frame.metric(axis),
                format = profile.display.readoutFormat,
                showsThread = FeedValues.showsThread(profile.machineType, axis),
            )
        }
    }
}

/**
 * The presenter of the axis details sheet of one axis. The entry of the sheet owns
 * it. While the sheet is open, it reads the feed rate from the input history of the session each
 * [REFRESH_MS] (`DeviceSession.readHistory`). The buttons of the sheet call the readout presenter.
 */
class AxisDetailsPresenter(val axis: AxisId, private val readout: ReadoutPresenter) : ScopedPresenter(readout.context) {

    private val mutable = MutableStateFlow(FeedTexts())

    /** The feed values of a linear axis. */
    val feed: StateFlow<FeedTexts> = mutable.asStateFlow()

    init {
        if (axis == AxisId.X || axis == AxisId.Y || axis == AxisId.Z) {
            scope.launch {
                while (isActive) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    /**
     * The proximity warning button: starts the warning of this axis with the target 0 on
     * the shown value, or stops it when it is on for this axis. The target is workspace state.
     */
    fun onProximityToggle() {
        readout.applyWorkspace(null) { workspace -> ProximityTargets.toggle(workspace, axis) }
    }

    private suspend fun refresh() {
        val frame = readout.frame.value ?: return
        mutable.value = FeedTexts.of(frame, axis, readout.feedRate(axis))
    }

    companion object {
        /** The time between two reads of the feed rate. */
        const val REFRESH_MS: Long = 500L

        /** True when the machine type and the axis show the thread values. */
        fun showsThread(machineType: MachineType, axis: AxisId): Boolean = FeedValues.showsThread(machineType, axis)
    }
}
