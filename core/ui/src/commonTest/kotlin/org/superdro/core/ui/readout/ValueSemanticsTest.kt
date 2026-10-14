package org.superdro.core.ui.readout

import kotlin.test.Test
import kotlin.test.assertEquals

class ValueSemanticsTest {

    private val english = ValueSemanticsTemplates(value = "%1\$s axis, %2\$s %3\$s", state = "%1\$s axis, %2\$s", valueState = "%1\$s, %2\$s")
    private val french = ValueSemanticsTemplates(value = "Axe %1\$s, %2\$s %3\$s", state = "Axe %1\$s, %2\$s", valueState = "%1\$s ; %2\$s")

    @Test
    fun aNormalValueGivesTheLabelTheValueAndTheUnit() {
        assertEquals("Y axis, -12.005 millimeters", ValueSemantics.description(english, "Y", ValueState.Normal, "-12.005", "millimeters", null))
    }

    @Test
    fun aValueWithAStateAddsTheStateText() {
        assertEquals(
            "X axis, 125.430 millimeters, Offset",
            ValueSemantics.description(english, "X", ValueState.Modified, "125.430", "millimeters", "Offset"),
        )
        assertEquals(
            "Axe X, 125.430 millimètres ; Décalage",
            ValueSemantics.description(french, "X", ValueState.Modified, "125.430", "millimètres", "Décalage"),
        )
    }

    @Test
    fun anErrorAndNoInputGiveOnlyTheState() {
        assertEquals("Z axis, Error", ValueSemantics.description(english, "Z", ValueState.Error, "1.000", "millimeters", "Error"))
        assertEquals("Z axis, ", ValueSemantics.description(english, "Z", ValueState.NoInput, "1.000", "millimeters", null))
    }

    @Test
    fun formatReplacesTheNumberedPlaceholdersInAnyOrder() {
        assertEquals("b a 5", ValueSemantics.format("%2\$s %1\$s %3\$d", "a", "b", "5"))
        assertEquals("100% done", ValueSemantics.format("100% done"))
    }
}
