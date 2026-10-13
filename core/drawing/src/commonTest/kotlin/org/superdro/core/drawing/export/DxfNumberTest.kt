package org.superdro.core.drawing.export

import kotlin.test.Test
import kotlin.test.assertEquals

class DxfNumberTest {

    @Test
    fun coordinatesHaveOneToNineDecimals() {
        val cases = mapOf(
            10.0 to "10.0",
            2.5 to "2.5",
            -2.5 to "-2.5",
            0.0 to "0.0",
            -0.0 to "0.0",
            -1.0e-12 to "0.0",
            1.0 / 3.0 to "0.333333333",
            2.0 / 3.0 to "0.666666667",
            0.1 + 0.2 to "0.3",
            3.175 to "3.175",
            0.0984252 to "0.0984252",
            123456789.123456789 to "123456789.12345679",
            1.0e20 to "100000000000000000000.0",
            0.9999999996 to "1.0",
            -0.9999999996 to "-1.0",
            1.0e-9 to "0.000000001",
            5.0e-10 to "0.000000001",
            25.4 to "25.4",
        )
        for ((value, text) in cases) assertEquals(text, DxfNumber.coordinate(value), "value $value")
    }

    @Test
    fun bulgesHaveUpTo17Decimals() {
        assertEquals("0.41421356237309503", DxfNumber.bulge(0.41421356237309503))
        assertEquals("-1.0", DxfNumber.bulge(-1.0))
        assertEquals("0.00000000000000001", DxfNumber.bulge(1.0e-17))
        assertEquals("0.0", DxfNumber.bulge(4.0e-18))
    }
}
