package org.superdro.core.ui.settings.keys

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertTrue
import org.superdro.core.model.AxisId
import org.superdro.core.model.KeyAction
import org.superdro.core.model.KeyMapping
import org.superdro.core.ui.settings.SettingsWindow
import org.superdro.core.ui.settings.renderSettings

/**
 * Draws the key mapping screen, the "Add a key" dialog, and the "Press a key" dialog to PNG
 * files (`core/ui/build/screenshots/settings-keys*.png`) in light and dark mode.
 */
class KeyMappingScreenshotTest {

    private object NoEvents : KeyMappingEvents {
        override fun startAdd() = Unit
        override fun setDraftAction(action: KeyAction) = Unit
        override fun setDraftAxis(axis: AxisId) = Unit
        override fun startCapture() = Unit
        override fun startChangeKey(index: Int) = Unit
        override fun cancelDraft() = Unit
        override fun onKeyCaptured(keyCode: Long) = Unit
        override fun remove(index: Int) = Unit
        override fun resetToDefault() = Unit
    }

    private fun state(customized: Boolean = false, draft: KeyDraft? = null): KeyMappingUiState {
        val map = if (customized) KeyMaps.default().take(6) + KeyMapping(Key.F5.keyCode, action = KeyAction.HalfAxis, axis = AxisId.Angular) else KeyMaps.default()
        return KeyMappingUiState(
            rows = map.mapIndexed { index, mapping -> KeyMappingRow(index, mapping, KeyMaps.keyText(mapping.keyCode), KeyMaps.actionText(mapping)) },
            customized = customized,
            draft = draft,
        )
    }

    @Test
    fun theKeyMappingScreen() {
        val files = renderSettings("keys") { _ -> KeyMappingScreen(state(), NoEvents, onBack = {}) } +
            renderSettings("keys-custom", listOf(SettingsWindow.Landscape)) { _ -> KeyMappingScreen(state(customized = true), NoEvents, onBack = {}) }
        files.forEach { file -> assertTrue(file.length() > 0) }
    }

    @Test
    fun theAddAndTheCaptureDialogs() {
        val files = renderSettings("keys-add") { _ -> KeyMappingScreen(state(draft = KeyDraft(KeyAction.HoldAxis, AxisId.Z)), NoEvents, onBack = {}) } +
            renderSettings("keys-capture") { _ ->
                KeyMappingScreen(state(draft = KeyDraft(KeyAction.ZeroAxis, AxisId.Y, capturing = true)), NoEvents, onBack = {})
            }
        files.forEach { file -> assertTrue(file.length() > 0) }
    }
}
