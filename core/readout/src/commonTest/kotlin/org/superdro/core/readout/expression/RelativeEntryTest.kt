package org.superdro.core.readout.expression

import kotlin.test.Test
import kotlin.test.assertEquals
import org.superdro.core.readout.UnitConversion

/** The relative entry `++x` and `--x`. */
class RelativeEntryTest {

    @Test
    fun plusPlusAddsToTheCurrentValue() {
        assertValue("11.5", "++1.5", current = 10.000)
    }

    @Test
    fun minusMinusSubtractsFromTheCurrentValue() {
        assertValue("8.5", "--1.5", current = 10.000)
        assertValue("-13", "--0.3", current = -12.7)
    }

    @Test
    fun keypadExample() {
        // X shows 1.2500 in, absolute. The user types ++0.5. The value is 1.75 in = 44450 µm.
        val result = ExpressionEvaluator.evaluate("++0.5", current = 1.2500)
        assertEquals(ExpressionResult.Value("1.75", 1.75), result)
        assertEquals(44450.0, UnitConversion.inchToUm(result.valueOrNull()!!))
    }

    @Test
    fun currentIsZeroForOtherFields() {
        assertValue("5", "++5")
        assertValue("-5", "--5")
    }

    @Test
    fun theRestOfTheTextIsAnExpression() {
        assertValue("7", "++(1+2)*2", current = 1.0)
        assertValue("8", "++-2", current = 10.0)
        assertValue("11.5", "++1,5", current = 10.0)
        assertValue("12", "--max(1, -2)", current = 13.0)
        assertError("Expected expression", "++")
        assertError("Expected expression", "--")
        // No unary plus.
        assertError("Expected expression", "+++1")
    }

    @Test
    fun spaceBeforeThePrefixIsIgnored() {
        assertValue("3", " \t++1", current = 2.0)
    }

    @Test
    fun aPrefixOnlyAtTheStartIsARelativeEntry() {
        assertValue("8", "5--3", current = 100.0)
        assertValue("5", "- -5", current = 100.0)
    }

    @Test
    fun theCurrentValueIsTheShortestDecimalOfTheDouble() {
        // The Double 0.1 + 0.2 is 0.30000000000000004. The evaluator adds 0.1 and 0.2 as decimals.
        assertValue("0.3", "++0.2", current = 0.1)
    }

    @Test
    fun aCurrentValueThatIsNotFiniteIsAnError() {
        assertError("Value is out of range", "++1", current = Double.NaN)
        assertError("Value is out of range", "--1", current = Double.POSITIVE_INFINITY)
        // Without a relative entry, the evaluator does not use the current value.
        assertValue("1", "1", current = Double.NaN)
    }
}
