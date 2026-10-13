package org.superdro.core.ui.settings.keys

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import org.superdro.core.model.AxisButtonMode
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.model.MachineProfile
import org.superdro.core.readout.operations.SubDatumOperations
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_keys_no_points
import org.superdro.core.resources.settings_keys_point_active
import org.superdro.core.store.json.AppSettingsStore
import org.superdro.core.ui.navigation.KeypadRoute
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.navigation.TabRoute
import org.superdro.core.ui.rememberInstance
import org.superdro.core.ui.readout.ReadoutPresenter
import org.superdro.core.ui.text.UiText
import androidx.compose.ui.focus.onFocusChanged

/**
 * The receiver of the actions of the hardware keys. The readout of the selected
 * tab implements it with [ReadoutKeyActions].
 */
fun interface KeyActionTarget {

    /**
     * Runs [action] on [axis] (null for an action with no axis). Gives true when the target used
     * the key, and false when the key must go on to the other receivers, for example an action of
     * an axis that is not enabled.
     */
    fun run(action: KeyAction, axis: AxisId?): Boolean
}

/**
 * The repeat filter of the hardware keys: a held key runs its action one time. A held key
 * sends more key downs with no key up (the key repeat of the system). The filter keeps the keys that
 * are down and the answer of their first key down; a repeat gives the same answer and runs nothing.
 * Compose gives no repeat count in common code, so the filter uses the key up.
 */
class KeyRepeatFilter {
    private val down = HashMap<Long, Boolean>()

    /**
     * The key down of [keyCode]. The first key down calls [run] and keeps its answer. A repeat
     * gives the kept answer and does not call [run].
     */
    fun onKeyDown(keyCode: Long, run: () -> Boolean): Boolean {
        down[keyCode]?.let { answer -> return answer }
        val answer = run()
        down[keyCode] = answer
        return answer
    }

    /** The key up of [keyCode]: the next key down runs the action again. */
    fun onKeyUp(keyCode: Long) {
        down.remove(keyCode)
    }

    /** Forgets all keys, for example when the readout loses the focus and gets no key up. */
    fun clear() = down.clear()
}

/** The dispatch of one key: the rule of the hardware key handler, with no Compose type. */
object KeyDispatch {

    /**
     * Finds the mapping of [keyCode] in [map] and runs it on [target]. Gives false for a key with
     * no mapping, so that a text field or a different receiver still gets the key.
     */
    fun dispatch(map: List<KeyMapping>, keyCode: Long, target: KeyActionTarget): Boolean {
        val mapping = KeyMaps.find(map, keyCode) ?: return false
        val axis = if (KeyMaps.needsAxis(mapping.action)) mapping.axis ?: AxisId.X else null
        return target.run(mapping.action, axis)
    }

    /**
     * The key down of [keyCode] through [filter]: the first key down of a held key runs its
     * mapping, and each repeat gives the same answer with no action.
     */
    fun dispatchDown(map: List<KeyMapping>, keyCode: Long, target: KeyActionTarget, filter: KeyRepeatFilter): Boolean =
        filter.onKeyDown(keyCode) { dispatch(map, keyCode, target) }
}

/**
 * The actions of the hardware keys on the readout of one tab. Each action uses a
 * public function of the [presenter], as the buttons of the readout do.
 * Thus the same rules apply: the check of a live position, the undo snackbar, and
 * the hints.
 *
 * | Action | Readout function |
 * |---|---|
 * | Zero of an axis | The tap of the zero button (`AxisButtonMode.SetZero`) |
 * | Set of an axis, the keypad | The keypad sheet of the axis (`KeypadRoute`) |
 * | Hold of an axis | The tap of the hold button (`AxisButtonMode.Hold`) |
 * | Half of an axis | The tap of the half button (`AxisButtonMode.SetCenter`) |
 * | Zero of all axes | The tap of the global Zero Set button |
 * | ABS or INC | The tap of the global Mode button |
 * | Units | The tap of the global Units button |
 * | Next point, previous point | The activation of the next or the previous sub-datum of the workspace |
 *
 * An action of an axis that is not enabled does nothing and gives false (on a lathe the Y axis is
 * the auxiliary readout).
 */
class ReadoutKeyActions(
    private val presenter: ReadoutPresenter,
    private val navigator: TabNavigator,
) : KeyActionTarget {

    override fun run(action: KeyAction, axis: AxisId?): Boolean {
        val frame = presenter.frame.value ?: return false
        val target = axis ?: AxisId.X
        if (KeyMaps.needsAxis(action) && !enabled(frame.profile, target)) return false
        when (action) {
            KeyAction.ZeroAxis -> open(presenter.onActionTap(target, AxisButtonMode.SetZero))
            KeyAction.SetAxis, KeyAction.OpenKeypad -> navigator.push(KeypadRoute(target))
            KeyAction.HoldAxis -> open(presenter.onActionTap(target, AxisButtonMode.Hold))
            KeyAction.HalfAxis -> open(presenter.onActionTap(target, AxisButtonMode.SetCenter))
            KeyAction.ZeroAll -> presenter.onZeroSetTap()
            KeyAction.ToggleAbsoluteIncremental -> presenter.onModeTap()
            KeyAction.ToggleUnits -> presenter.onUnitsTap()
            KeyAction.NextSubDatum -> stepPoint(forward = true)
            KeyAction.PreviousSubDatum -> stepPoint(forward = false)
            KeyAction.None -> return false
        }
        return true
    }

    /**
     * Activates the next ([forward]) or the previous sub-datum of the workspace in the stored
     * order. With no active point, "next" gives the first point and "previous" gives the last
     * point. The list wraps at each end.
     */
    private fun stepPoint(forward: Boolean) {
        val frame = presenter.frame.value ?: return
        val points = frame.workspace.subDatums
        if (points.isEmpty()) {
            presenter.showMessage(UiText.of(Res.string.settings_keys_no_points))
            return
        }
        // A second key can come before the store gives the frame of the first key. Then the
        // point of the first key is the current point.
        val stored = frame.workspace.activeSubDatumId
        val current = pending?.takeIf { (before, _) -> before == stored }?.second ?: stored
        val index = nextIndex(points.indexOfFirst { point -> point.id == current }, points.size, forward)
        val point = points[index]
        pending = stored to point.id
        presenter.apply(SubDatumOperations.activate(frame, point.id), UiText.of(Res.string.settings_keys_point_active, point.name))
    }

    /** The active point before the last step, and the point of the last step. */
    private var pending: Pair<String?, String>? = null

    private fun open(route: TabRoute?) {
        if (route != null) navigator.push(route)
    }

    companion object {
        /**
         * Gives the index of the next point. [current] is the index of the active point, or −1 for
         * no active point. The list wraps.
         */
        fun nextIndex(current: Int, size: Int, forward: Boolean): Int = when {
            current < 0 -> if (forward) 0 else size - 1
            forward -> (current + 1) % size
            else -> (current - 1 + size) % size
        }

        /** True when [axis] of [profile] is enabled. */
        fun enabled(profile: MachineProfile, axis: AxisId): Boolean = when (axis) {
            AxisId.X -> profile.x.enabled
            AxisId.Y -> profile.y.enabled
            AxisId.Z -> profile.z.enabled
            AxisId.Angular -> profile.angular.enabled
            AxisId.Caliper -> profile.caliper.enabled
            AxisId.Tachometer -> profile.tachometer.enabled
        }
    }
}

/**
 * Gives the modifier of the hardware keys: the element gets the focus, and each key down
 * of [map] runs its action on [target]. A held key runs its action one time ([KeyRepeatFilter]).
 * A key with no mapping goes on, so a text field still works. Put it on the root of the readout of a tab: only the root of the selected tab is in the
 * composition, so the action goes to the selected tab. A sheet is a different window and gets its
 * own keys.
 */
@Composable
fun hardwareKeysModifier(map: List<KeyMapping>, target: KeyActionTarget): Modifier {
    val requester = remember { FocusRequester() }
    val filter = remember { KeyRepeatFilter() }
    val currentMap by rememberUpdatedState(map)
    val currentTarget by rememberUpdatedState(target)
    LaunchedEffect(requester) {
        try {
            requester.requestFocus()
        } catch (error: IllegalStateException) {
            // The element is not in the layout yet. A tap on the readout does not need the keys.
        }
    }
    return Modifier
        .onPreviewKeyEvent { event ->
            when (event.type) {
                KeyEventType.KeyDown -> KeyDispatch.dispatchDown(currentMap, event.key.keyCode, currentTarget, filter)
                KeyEventType.KeyUp -> {
                    filter.onKeyUp(event.key.keyCode)
                    false
                }
                else -> false
            }
        }
        .onFocusChanged { state -> if (!state.hasFocus) filter.clear() }
        .focusRequester(requester)
        .focusable()
}

/**
 * The hardware keys of the readout root of a tab: the effective key map of the app settings
 * ([KeyMaps.effective]) on the [ReadoutKeyActions] of [presenter]. The readout entry puts the
 * result on its screen.
 */
@Composable
fun readoutHardwareKeys(presenter: ReadoutPresenter, navigator: TabNavigator): Modifier {
    val store = rememberInstance<AppSettingsStore>()
    val settings by store.settings.collectAsState()
    val map = remember(settings.keyMapCustomized, settings.keyMappings) { KeyMaps.effective(settings) }
    val target = remember(presenter, navigator) { ReadoutKeyActions(presenter, navigator) }
    return hardwareKeysModifier(map, target)
}
