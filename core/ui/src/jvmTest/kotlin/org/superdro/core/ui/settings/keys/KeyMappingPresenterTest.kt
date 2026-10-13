package org.superdro.core.ui.settings.keys

import androidx.compose.ui.input.key.Key
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.ui.tabs.english
import org.superdro.core.ui.tabs.testPresenterContext
import org.superdro.core.ui.testing.UiStoreFixture

/** The presenter of the key mapping screen with virtual time. */
class KeyMappingPresenterTest {

    private val scope = TestScope()
    private val store = UiStoreFixture(scope)
    private val presenter = KeyMappingPresenter(store.appSettings, scope.testPresenterContext())

    @AfterTest
    fun close() {
        presenter.close()
        store.close()
    }

    private val settings get() = store.appSettings.settings.value

    private fun TestScope.addKey(action: KeyAction, axis: AxisId?, key: Key) {
        presenter.startAdd()
        presenter.setDraftAction(action)
        if (axis != null) presenter.setDraftAxis(axis)
        presenter.startCapture()
        runCurrent()
        assertEquals(true, presenter.uiState.value.draft?.capturing)
        presenter.onKeyCaptured(key.keyCode)
    }

    @Test
    fun theScreenShowsTheDefaultMapFirst() = scope.runTest {
        runCurrent()
        val state = presenter.uiState.value
        assertFalse(state.customized)
        assertEquals(KeyMaps.default().size, state.rows.size)
        assertEquals("Num 1", state.rows.first().key.english())
        assertEquals("Zero X", state.rows.first().action.english())
    }

    @Test
    fun aCapturedKeyGoesIntoTheMapAtOnceAndTheMapIsNowTheMapOfTheUser() = scope.runTest {
        runCurrent()
        addKey(KeyAction.HalfAxis, AxisId.Z, Key.F5)
        runCurrent()
        assertTrue(settings.keyMapCustomized)
        assertEquals(KeyMaps.default() + KeyMapping(Key.F5.keyCode, action = KeyAction.HalfAxis, axis = AxisId.Z), settings.keyMappings)
        assertNull(presenter.uiState.value.draft)
        assertEquals("Key F5: Half of Z", presenter.uiState.value.message?.text?.english())
        advanceUntilIdle()
        assertTrue(store.appSettings.state.value.keyMapCustomized)
    }

    @Test
    fun anActionWithNoAxisKeepsNoAxis() = scope.runTest {
        runCurrent()
        addKey(KeyAction.ZeroAll, AxisId.Y, Key.F6)
        runCurrent()
        assertEquals(KeyMapping(Key.F6.keyCode, action = KeyAction.ZeroAll, axis = null), settings.keyMappings.last())
    }

    @Test
    fun aKeyThatHasAMappingGetsTheNewActionWithAMessage() = scope.runTest {
        runCurrent()
        addKey(KeyAction.ToggleUnits, null, Key.NumPad1)
        runCurrent()
        val map = settings.keyMappings
        assertEquals(KeyMaps.default().size, map.size)
        assertEquals(listOf(KeyMapping(Key.NumPad1.keyCode, action = KeyAction.ToggleUnits)), map.filter { it.keyCode == Key.NumPad1.keyCode })
        assertEquals(
            "Key Num 1 had the action Zero X. Its action is now Units (inch or mm).",
            presenter.uiState.value.message?.text?.english(),
        )
    }

    @Test
    fun escapeCancelsTheCaptureWithNoChange() = scope.runTest {
        runCurrent()
        addKey(KeyAction.ZeroAxis, AxisId.X, Key.Escape)
        runCurrent()
        assertNull(presenter.uiState.value.draft)
        assertFalse(settings.keyMapCustomized)
        assertEquals(emptyList<KeyMapping>(), settings.keyMappings)
    }

    @Test
    fun theChangeOfTheKeyOfARowKeepsItsPositionAndItsAction() = scope.runTest {
        runCurrent()
        presenter.startChangeKey(0)
        runCurrent()
        val draft = assertNotNull(presenter.uiState.value.draft)
        assertTrue(draft.capturing)
        assertEquals(KeyAction.ZeroAxis, draft.action)
        presenter.onKeyCaptured(Key.F9.keyCode)
        runCurrent()
        assertEquals(KeyMapping(Key.F9.keyCode, action = KeyAction.ZeroAxis, axis = AxisId.X), settings.keyMappings.first())
        assertEquals(KeyMaps.default().size, settings.keyMappings.size)
    }

    @Test
    fun aRemoveHasAnUndoFor10Seconds() = scope.runTest {
        runCurrent()
        presenter.remove(0)
        runCurrent()
        assertEquals(KeyMaps.default().drop(1), settings.keyMappings)
        val message = assertNotNull(presenter.uiState.value.message)
        assertTrue(message.undo)
        assertEquals("Key Num 1 removed", message.text.english())
        presenter.undoRemove(message.id)
        runCurrent()
        assertEquals(KeyMaps.default(), settings.keyMappings)

        presenter.remove(1)
        runCurrent()
        val second = assertNotNull(presenter.uiState.value.message)
        advanceTimeBy(KeyMappingPresenter.UNDO_MS + 1)
        runCurrent()
        assertNull(presenter.uiState.value.message)
        presenter.undoRemove(second.id)
        runCurrent()
        assertEquals(KeyMaps.default().size - 1, settings.keyMappings.size, "no undo after 10 s")
    }

    @Test
    fun theResetGivesTheDefaultMapBack() = scope.runTest {
        runCurrent()
        presenter.remove(0)
        presenter.remove(0)
        runCurrent()
        assertTrue(presenter.uiState.value.customized)
        presenter.resetToDefault()
        runCurrent()
        assertFalse(settings.keyMapCustomized)
        assertEquals(emptyList<KeyMapping>(), settings.keyMappings)
        assertEquals(KeyMaps.default().size, presenter.uiState.value.rows.size)
        assertEquals("The default key map is back.", presenter.uiState.value.message?.text?.english())
    }
}
