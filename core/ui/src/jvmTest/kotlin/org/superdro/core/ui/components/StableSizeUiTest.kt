package org.superdro.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.model.ThemeMode
import org.superdro.core.ui.graphics.view.ToggleChip
import org.superdro.core.ui.theme.AppTheme

/** A control keeps its size when its state changes. */
@OptIn(ExperimentalTestApi::class)
class StableSizeUiTest {

    @Test
    fun aStableTextKeepsTheWidthOfItsWidestText() = runComposeUiTest {
        var long by mutableStateOf(false)
        setContent {
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
                StableText(if (long) "Stop the server" else "Go", listOf("Go", "Stop the server"), Modifier.testTag("text"))
            }
        }
        val short = onNodeWithTag("text").getBoundsInRoot().width
        long = true
        waitForIdle()
        assertEquals(short, onNodeWithTag("text").getBoundsInRoot().width)
        onNodeWithText("Go").assertDoesNotExist()
    }

    @Test
    fun aToggleChipKeepsItsWidthWhenItTurnsOn() = runComposeUiTest {
        var on by mutableStateOf(false)
        setContent {
            AppTheme(mode = ThemeMode.Dark, systemInDarkTheme = true) {
                ToggleChip("Arm thread cutting", selected = on, onClick = { on = !on }, modifier = Modifier.testTag("chip"))
            }
        }
        val off = onNodeWithTag("chip").getBoundsInRoot().width
        on = true
        waitForIdle()
        assertEquals(off, onNodeWithTag("chip").getBoundsInRoot().width)
    }
}
