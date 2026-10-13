package org.superdro.core.ui.adaptive

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.superdro.core.ui.theme.TouchTargets

/** The window classes and the touch targets. */
class WindowLayoutTest {

    @Test
    fun theWidthClassesHaveTheBreakpointsOf600And840Dp() {
        assertEquals(WidthClass.Compact, WindowLayout.of(599.dp, 900.dp).widthClass)
        assertEquals(WidthClass.Medium, WindowLayout.of(600.dp, 960.dp).widthClass)
        assertEquals(WidthClass.Medium, WindowLayout.of(839.dp, 1300.dp).widthClass)
        assertEquals(WidthClass.Expanded, WindowLayout.of(840.dp, 525.dp).widthClass)
        assertEquals(WidthClass.Expanded, WindowLayout.of(1200.dp, 750.dp).widthClass, "Large uses the expanded layout")
        assertTrue(WindowLayout.of(1200.dp, 750.dp).landscape)
        assertFalse(WindowLayout.of(800.dp, 1280.dp).landscape)
    }

    @Test
    fun theLayoutComesFromTheWidthOnly() {
        // A portrait window of 840 dp or more uses the expanded layout of 7.5.
        assertTrue(WindowLayout.of(900.dp, 1440.dp).expanded)
        assertFalse(WindowLayout.of(900.dp, 1440.dp).landscape, "A sheet stays a bottom sheet in portrait")
        assertTrue(WindowLayout.of(1280.dp, 800.dp).expanded)
        // The SM-X230 in portrait (800 dp wide) uses the medium layout.
        assertFalse(WindowLayout.of(800.dp, 1280.dp).expanded)
        assertFalse(WindowLayout.of(839.dp, 525.dp).expanded)
    }

    @Test
    fun largeButtonsAdd25Percent() {
        assertEquals(TouchTargets(64.dp, 72.dp, 12.dp), TouchTargets.of(largeButtons = false))
        assertEquals(TouchTargets(80.dp, 90.dp, 15.dp), TouchTargets.of(largeButtons = true))
    }
}
