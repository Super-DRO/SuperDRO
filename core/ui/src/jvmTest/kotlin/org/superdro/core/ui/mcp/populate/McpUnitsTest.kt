package org.superdro.core.ui.mcp.populate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.superdro.core.model.MachineProfile

class McpUnitsTest {

    @Test
    fun lengthsConvertToAndFromMicrometers() {
        assertEquals(25_400.0, McpUnits.lengthToUm(1.0, "inch"))
        assertEquals(1_000.0, McpUnits.lengthToUm(1.0, "mm"))
        assertEquals(7.0, McpUnits.lengthToUm(7.0, "um"))
        assertEquals(2.0, McpUnits.lengthFromUm(50_800.0, "inch"))
        assertEquals(1.5, McpUnits.lengthFromUm(1_500.0, "mm"))
        assertEquals(1_500.0, McpUnits.lengthFromUm(1_500.0, "um"))
    }

    @Test
    fun anglesConvertToAndFromArcSeconds() {
        assertEquals(3_600.0, McpUnits.angleToArcSec(1.0))
        assertEquals(90.0, McpUnits.angleFromArcSec(324_000.0))
    }

    @Test
    fun theDefaultUnitsFollowTheProfile() {
        assertEquals("mm", McpUnits.defaultUnits(MachineProfile(metricDefault = true)))
        assertEquals("inch", McpUnits.defaultUnits(MachineProfile(metricDefault = false)))
    }

    @Test
    fun anUnknownUnitIsAnError() {
        assertFailsWith<IllegalArgumentException> { McpUnits.lengthToUm(1.0, "cm") }
        assertEquals(false, McpUnits.isValid("cm"))
        assertEquals(listOf("mm", "inch", "um"), McpUnits.ALL)
    }
}
