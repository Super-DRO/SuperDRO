package org.superdro.core.ui.settings.keys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.resources.Res
import org.superdro.core.resources.settings_keys_action_half
import org.superdro.core.resources.settings_keys_action_hold
import org.superdro.core.resources.settings_keys_action_keypad
import org.superdro.core.resources.settings_keys_action_mode
import org.superdro.core.resources.settings_keys_action_next_point
import org.superdro.core.resources.settings_keys_action_previous_point
import org.superdro.core.resources.settings_keys_action_set
import org.superdro.core.resources.settings_keys_action_units
import org.superdro.core.resources.settings_keys_action_zero
import org.superdro.core.resources.settings_keys_action_zero_all
import org.superdro.core.resources.settings_keys_axis_angular
import org.superdro.core.resources.settings_keys_code
import org.superdro.core.resources.settings_keys_pick_half
import org.superdro.core.resources.settings_keys_pick_hold
import org.superdro.core.resources.settings_keys_pick_keypad
import org.superdro.core.resources.settings_keys_pick_set
import org.superdro.core.resources.settings_keys_pick_zero
import org.superdro.core.ui.text.UiText

/**
 * The key map of the hardware keys. A key of an
 * HID device (a keyboard, a numeric keypad, a foot pedal, or a remote) starts an action of the
 * selected tab.
 *
 * The key code of a mapping is the Compose `Key.keyCode` of the platform. Thus `core:model` has no
 * default map, and this object makes it. The default map:
 *
 * | Key | Action |
 * |---|---|
 * | Num 1, Num 2, Num 3 | Zero X, Y, Z |
 * | Num 4, Num 5, Num 6 | Set X, Y, Z (the keypad sheet of the axis) |
 * | Num 7, Num 8, Num 9 | Hold X, Y, Z |
 * | Num 0 | Zero all axes (the global Zero Set button) |
 * | Num + | Next point |
 * | Num − | Previous point |
 * | Num * | Units (inch or mm) |
 * | Num / | ABS or INC (the global Mode button) |
 * | Page Down | Next point (a foot pedal or a presenter remote sends it) |
 * | Page Up | Previous point |
 *
 * On a lathe the Y axis is the auxiliary readout. An action of an axis that is not enabled does
 * nothing.
 */
object KeyMaps {

    /** The actions that need an axis. */
    val AXIS_ACTIONS: Set<KeyAction> = setOf(KeyAction.ZeroAxis, KeyAction.SetAxis, KeyAction.OpenKeypad, KeyAction.HoldAxis, KeyAction.HalfAxis)

    /** The axes that an axis action can use. */
    val AXES: List<AxisId> = listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular)

    /** The actions that the user can map, in the order of the screen. [KeyAction.None] is not one. */
    val SELECTABLE: List<KeyAction> = listOf(
        KeyAction.ZeroAxis,
        KeyAction.SetAxis,
        KeyAction.HoldAxis,
        KeyAction.HalfAxis,
        KeyAction.ZeroAll,
        KeyAction.ToggleAbsoluteIncremental,
        KeyAction.ToggleUnits,
        KeyAction.NextSubDatum,
        KeyAction.PreviousSubDatum,
        KeyAction.OpenKeypad,
    )

    /** The keys that cancel the capture of a key: they are never mapped. */
    val CANCEL_KEYS: Set<Long> = setOf(Key.Escape.keyCode, Key.Back.keyCode)

    /** True when [action] needs an axis. */
    fun needsAxis(action: KeyAction): Boolean = action in AXIS_ACTIONS

    /** The default map of the table of [KeyMaps]. */
    fun default(): List<KeyMapping> = listOf(
        KeyMapping(Key.NumPad1.keyCode, action = KeyAction.ZeroAxis, axis = AxisId.X),
        KeyMapping(Key.NumPad2.keyCode, action = KeyAction.ZeroAxis, axis = AxisId.Y),
        KeyMapping(Key.NumPad3.keyCode, action = KeyAction.ZeroAxis, axis = AxisId.Z),
        KeyMapping(Key.NumPad4.keyCode, action = KeyAction.SetAxis, axis = AxisId.X),
        KeyMapping(Key.NumPad5.keyCode, action = KeyAction.SetAxis, axis = AxisId.Y),
        KeyMapping(Key.NumPad6.keyCode, action = KeyAction.SetAxis, axis = AxisId.Z),
        KeyMapping(Key.NumPad7.keyCode, action = KeyAction.HoldAxis, axis = AxisId.X),
        KeyMapping(Key.NumPad8.keyCode, action = KeyAction.HoldAxis, axis = AxisId.Y),
        KeyMapping(Key.NumPad9.keyCode, action = KeyAction.HoldAxis, axis = AxisId.Z),
        KeyMapping(Key.NumPad0.keyCode, action = KeyAction.ZeroAll),
        KeyMapping(Key.NumPadAdd.keyCode, action = KeyAction.NextSubDatum),
        KeyMapping(Key.NumPadSubtract.keyCode, action = KeyAction.PreviousSubDatum),
        KeyMapping(Key.NumPadMultiply.keyCode, action = KeyAction.ToggleUnits),
        KeyMapping(Key.NumPadDivide.keyCode, action = KeyAction.ToggleAbsoluteIncremental),
        KeyMapping(Key.PageDown.keyCode, action = KeyAction.NextSubDatum),
        KeyMapping(Key.PageUp.keyCode, action = KeyAction.PreviousSubDatum),
    )

    /**
     * The map that applies: the map of the user when [AppSettings.keyMapCustomized] is true, else
     * the [default] map.
     */
    fun effective(settings: AppSettings): List<KeyMapping> = if (settings.keyMapCustomized) settings.keyMappings else default()

    /**
     * Gives the mapping of [keyCode] in [map], or null. A mapping with the action
     * [KeyAction.None] (for example an action that a later version wrote) is not a mapping. The
     * device of a key event is not known in common code, so a mapping with a device descriptor
     * also matches.
     */
    fun find(map: List<KeyMapping>, keyCode: Long): KeyMapping? =
        map.lastOrNull { mapping -> mapping.keyCode == keyCode && mapping.action != KeyAction.None }

    /** Gives the text of the action of [mapping], with its axis. */
    fun actionText(mapping: KeyMapping): UiText = actionText(mapping.action, mapping.axis)

    /** Gives the text of [action] on [axis], for example "Zero X". */
    fun actionText(action: KeyAction, axis: AxisId?): UiText {
        val axisText = axisText(axis ?: AxisId.X)
        return when (action) {
            KeyAction.ZeroAxis -> UiText.of(Res.string.settings_keys_action_zero, axisText)
            KeyAction.SetAxis -> UiText.of(Res.string.settings_keys_action_set, axisText)
            KeyAction.OpenKeypad -> UiText.of(Res.string.settings_keys_action_keypad, axisText)
            KeyAction.HoldAxis -> UiText.of(Res.string.settings_keys_action_hold, axisText)
            KeyAction.HalfAxis -> UiText.of(Res.string.settings_keys_action_half, axisText)
            KeyAction.ZeroAll -> UiText.of(Res.string.settings_keys_action_zero_all)
            KeyAction.ToggleAbsoluteIncremental -> UiText.of(Res.string.settings_keys_action_mode)
            KeyAction.ToggleUnits -> UiText.of(Res.string.settings_keys_action_units)
            KeyAction.NextSubDatum -> UiText.of(Res.string.settings_keys_action_next_point)
            KeyAction.PreviousSubDatum -> UiText.of(Res.string.settings_keys_action_previous_point)
            KeyAction.None -> UiText.Plain("")
        }
    }

    /** Gives the text of [action] in the action list of the add dialog, with no axis. */
    fun pickText(action: KeyAction): UiText = when (action) {
        KeyAction.ZeroAxis -> UiText.of(Res.string.settings_keys_pick_zero)
        KeyAction.SetAxis -> UiText.of(Res.string.settings_keys_pick_set)
        KeyAction.OpenKeypad -> UiText.of(Res.string.settings_keys_pick_keypad)
        KeyAction.HoldAxis -> UiText.of(Res.string.settings_keys_pick_hold)
        KeyAction.HalfAxis -> UiText.of(Res.string.settings_keys_pick_half)
        else -> actionText(action, null)
    }

    /** Gives the name of [axis]: the letter of a linear axis, or "angular axis". */
    fun axisText(axis: AxisId): UiText = when (axis) {
        AxisId.Angular -> UiText.of(Res.string.settings_keys_axis_angular)
        else -> UiText.Plain(axis.name)
    }

    /** Gives the name of the key [keyCode]: a key cap name, else "Key code <n>" with the platform code. */
    fun keyText(keyCode: Long): UiText {
        val name = KeyNames.nameOf(keyCode)
        return if (name != null) UiText.Plain(name) else UiText.of(Res.string.settings_keys_code, Key(keyCode).nativeKeyCode)
    }
}

/**
 * The names of the common keys. A key cap name is a technical name: it has no translation. A key
 * that has no name here shows "Key code <n>" ([KeyMaps.keyText]).
 */
object KeyNames {

    private val names: Map<Long, String> by lazy {
        buildMap {
            val numPad = listOf(
                Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
                Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
            )
            numPad.forEachIndexed { digit, key -> put(key.keyCode, "Num $digit") }
            put(Key.NumPadAdd.keyCode, "Num +")
            put(Key.NumPadSubtract.keyCode, "Num −")
            put(Key.NumPadMultiply.keyCode, "Num *")
            put(Key.NumPadDivide.keyCode, "Num /")
            put(Key.NumPadEnter.keyCode, "Num Enter")
            put(Key.NumPadDot.keyCode, "Num .")
            val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
            digits.forEachIndexed { digit, key -> put(key.keyCode, "$digit") }
            val letters = listOf(
                Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
                Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
            )
            letters.forEachIndexed { index, key -> put(key.keyCode, ('A' + index).toString()) }
            val functions = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
            functions.forEachIndexed { index, key -> put(key.keyCode, "F${index + 1}") }
            put(Key.PageUp.keyCode, "Page Up")
            put(Key.PageDown.keyCode, "Page Down")
            put(Key.MoveHome.keyCode, "Home")
            put(Key.MoveEnd.keyCode, "End")
            put(Key.Insert.keyCode, "Insert")
            put(Key.Delete.keyCode, "Delete")
            put(Key.Backspace.keyCode, "Backspace")
            put(Key.Enter.keyCode, "Enter")
            put(Key.Spacebar.keyCode, "Space")
            put(Key.Tab.keyCode, "Tab")
            put(Key.DirectionUp.keyCode, "↑")
            put(Key.DirectionDown.keyCode, "↓")
            put(Key.DirectionLeft.keyCode, "←")
            put(Key.DirectionRight.keyCode, "→")
            put(Key.MediaPlayPause.keyCode, "Play/Pause")
            put(Key.MediaNext.keyCode, "Next track")
            put(Key.MediaPrevious.keyCode, "Previous track")
            put(Key.VolumeUp.keyCode, "Volume +")
            put(Key.VolumeDown.keyCode, "Volume −")
        }
    }

    /** Gives the name of [keyCode], or null. */
    fun nameOf(keyCode: Long): String? = names[keyCode]
}
