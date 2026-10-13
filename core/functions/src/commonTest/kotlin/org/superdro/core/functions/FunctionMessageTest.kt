package org.superdro.core.functions

import kotlin.test.Test
import kotlin.test.assertEquals

/** The message type and the number text of the machine functions. */
class FunctionMessageTest {

    @Test
    fun theTemplateGetsItsArgumentsAndANestedMessageGivesItsText() {
        val label = MessageTemplate("label", "Hole Count")
        val message = MessageTemplate("key", "%1\$s value is too large (%2\$d)").format(label.message(), 7)
        assertEquals("Hole Count value is too large (7)", message.text)
        assertEquals("key", message.key)
        assertEquals("No argument %1\$s", MessageTemplate("key", "No argument %1\$s").message().text)
    }

    @Test
    fun theNumberTextRoundsHalfUpAndHasNoNegativeZero() {
        assertEquals("0.050", formatFixed(0.05, 3))
        assertEquals("12346", formatFixed(12345.5, 0))
        assertEquals("-12345", formatFixed(-12345.5, 0))
        assertEquals("0.0000", formatFixed(-0.00001, 4))
        assertEquals("-1.250", formatFixed(-1.25, 3))
        assertEquals("2.4301", formatFixed(2.430118, 4))
    }
}
