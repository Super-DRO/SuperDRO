package org.superdro.core.ui.settings.keys

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.superdro.core.model.AppSettings
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.ui.tabs.english

/** The default key map, the effective map, and the texts of the key map. */
class KeyMapsTest {

    private fun entry(key: Key): KeyMapping? = KeyMaps.default().singleOrNull { mapping -> mapping.keyCode == key.keyCode }

    @Test
    fun theDefaultMapHasTheNumericKeypadKeysAndPageUpAndPageDown() {
        val expected = listOf(
            Key.NumPad1 to (KeyAction.ZeroAxis to AxisId.X),
            Key.NumPad2 to (KeyAction.ZeroAxis to AxisId.Y),
            Key.NumPad3 to (KeyAction.ZeroAxis to AxisId.Z),
            Key.NumPad4 to (KeyAction.SetAxis to AxisId.X),
            Key.NumPad5 to (KeyAction.SetAxis to AxisId.Y),
            Key.NumPad6 to (KeyAction.SetAxis to AxisId.Z),
            Key.NumPad7 to (KeyAction.HoldAxis to AxisId.X),
            Key.NumPad8 to (KeyAction.HoldAxis to AxisId.Y),
            Key.NumPad9 to (KeyAction.HoldAxis to AxisId.Z),
            Key.NumPad0 to (KeyAction.ZeroAll to null),
            Key.NumPadAdd to (KeyAction.NextSubDatum to null),
            Key.NumPadSubtract to (KeyAction.PreviousSubDatum to null),
            Key.NumPadMultiply to (KeyAction.ToggleUnits to null),
            Key.NumPadDivide to (KeyAction.ToggleAbsoluteIncremental to null),
            Key.PageDown to (KeyAction.NextSubDatum to null),
            Key.PageUp to (KeyAction.PreviousSubDatum to null),
        )
        assertEquals(expected.size, KeyMaps.default().size)
        expected.forEach { (key, value) ->
            val mapping = entry(key)
            assertEquals(value.first, mapping?.action, "action of $key")
            assertEquals(value.second, mapping?.axis, "axis of $key")
            assertNull(mapping?.deviceDescriptor, "a default key works on each device")
        }
        assertEquals(KeyMaps.default().size, KeyMaps.default().map { it.keyCode }.toSet().size, "each key is in the map one time")
    }

    @Test
    fun theDefaultMapAppliesUntilTheUserChangesTheMap() {
        assertEquals(KeyMaps.default(), KeyMaps.effective(AppSettings()))
        val own = listOf(KeyMapping(Key.F5.keyCode, action = KeyAction.ToggleUnits))
        assertEquals(KeyMaps.default(), KeyMaps.effective(AppSettings(keyMappings = own, keyMapCustomized = false)))
        assertEquals(own, KeyMaps.effective(AppSettings(keyMappings = own, keyMapCustomized = true)))
        assertEquals(emptyList<KeyMapping>(), KeyMaps.effective(AppSettings(keyMappings = emptyList(), keyMapCustomized = true)))
    }

    @Test
    fun anActionThatThisVersionDoesNotKnowIsNotAMapping() {
        val map = listOf(KeyMapping(Key.F5.keyCode, action = KeyAction.None), KeyMapping(Key.F6.keyCode, action = KeyAction.ZeroAll))
        assertNull(KeyMaps.find(map, Key.F5.keyCode))
        assertEquals(KeyAction.ZeroAll, KeyMaps.find(map, Key.F6.keyCode)?.action)
        assertNull(KeyMaps.find(map, Key.F7.keyCode))
    }

    @Test
    fun theTextsGiveTheKeyAndTheActionWithItsAxis() {
        assertEquals("Num 1", KeyMaps.keyText(Key.NumPad1.keyCode).english())
        assertEquals("Num +", KeyMaps.keyText(Key.NumPadAdd.keyCode).english())
        assertEquals("Page Down", KeyMaps.keyText(Key.PageDown.keyCode).english())
        assertEquals("F5", KeyMaps.keyText(Key.F5.keyCode).english())
        assertTrue(KeyMaps.keyText(Key.ScrollLock.keyCode).english().startsWith("Key code "))
        assertEquals("Zero X", KeyMaps.actionText(KeyAction.ZeroAxis, AxisId.X).english())
        assertEquals("Set Z", KeyMaps.actionText(KeyAction.SetAxis, AxisId.Z).english())
        assertEquals("Hold angular axis", KeyMaps.actionText(KeyAction.HoldAxis, AxisId.Angular).english())
        assertEquals("Half of Y", KeyMaps.actionText(KeyAction.HalfAxis, AxisId.Y).english())
        assertEquals("Keypad of X", KeyMaps.actionText(KeyAction.OpenKeypad, AxisId.X).english())
        assertEquals("Zero all axes", KeyMaps.actionText(KeyAction.ZeroAll, null).english())
        assertEquals("ABS or INC", KeyMaps.actionText(KeyAction.ToggleAbsoluteIncremental, null).english())
        assertEquals("Units (inch or mm)", KeyMaps.actionText(KeyAction.ToggleUnits, null).english())
        assertEquals("Next point", KeyMaps.actionText(KeyAction.NextSubDatum, null).english())
        assertEquals("Previous point", KeyMaps.actionText(KeyAction.PreviousSubDatum, null).english())
    }

    @Test
    fun theUserCanMapEachActionExceptNoneAndTheAxisActionsNeedAnAxis() {
        assertEquals(KeyAction.entries.toSet() - KeyAction.None, KeyMaps.SELECTABLE.toSet())
        assertTrue(KeyMaps.needsAxis(KeyAction.ZeroAxis))
        assertTrue(KeyMaps.needsAxis(KeyAction.HalfAxis))
        assertFalse(KeyMaps.needsAxis(KeyAction.ZeroAll))
        assertEquals(listOf(AxisId.X, AxisId.Y, AxisId.Z, AxisId.Angular), KeyMaps.AXES)
        assertTrue(Key.Escape.keyCode in KeyMaps.CANCEL_KEYS)
    }
}
