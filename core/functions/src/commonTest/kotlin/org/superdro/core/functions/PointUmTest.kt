package org.superdro.core.functions

import kotlin.test.Test
import kotlin.test.assertEquals

class PointUmTest {

    @Test
    fun theDistanceOfA3To4To5TriangleIs5() {
        assertEquals(50000.0, PointUm(0.0, 0.0).distanceTo(PointUm(30000.0, 40000.0)))
    }

    @Test
    fun theMidpointIsHalfWay() {
        assertEquals(PointUm(20000.0, 15000.0), PointUm(0.0, 30000.0).midpoint(PointUm(40000.0, 0.0)))
    }
}
