package org.superdro.core.ui.readout.keypad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.ui.theme.AppTheme

/**
 * The expression field of the keypad opens no soft keyboard, and it has the focus for a physical
 * keyboard.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class KeypadFieldUiTest {

    @Test
    fun theFieldHasTheFocusAndNoRequestOfTheInputMethodGoesToThePlatform() = runComposeUiTest {
        var requests = 0
        var text by mutableStateOf("")
        var sets = 0
        // The interceptor outside the field stands for the platform: it counts each request that
        // gets past the field.
        val platform = PlatformTextInputInterceptor { request, next ->
            requests++
            next.startInputMethod(request)
        }
        setContent {
            AppTheme(systemInDarkTheme = false) {
                InterceptPlatformTextInput(interceptor = platform) {
                    ExpressionField(text = text, onTextChange = { next -> text = next }, onSet = { sets++ })
                }
            }
        }
        onNodeWithTag(KEYPAD_FIELD_TAG).assertIsFocused()
        onNodeWithTag(KEYPAD_FIELD_TAG).performClick()
        waitForIdle()
        assertEquals(0, requests, "The soft keyboard must not open for the keypad field")
        // A physical keyboard: Enter is "Set".
        onNodeWithTag(KEYPAD_FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, sets)
    }
}
