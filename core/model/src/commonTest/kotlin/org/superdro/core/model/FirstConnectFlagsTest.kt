package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The stored flags of the requests of the first connect. */
class FirstConnectFlagsTest {

    @Test
    fun anOldFileWithNoFlagsLoadsWithTheFlagsOff() {
        val old = SuperDroJson.decodeFromString(AppSettings.serializer(), """{"schemaVersion":1,"batteryExemptionAsked":true}""")
        assertEquals(true, old.batteryExemptionAsked)
        assertFalse(old.notificationPermissionAsked)
        assertFalse(old.localNetworkPermissionAsked)
    }

    @Test
    fun theFlagsStayInTheFile() {
        val asked = AppSettings(notificationPermissionAsked = true, localNetworkPermissionAsked = true)
        val text = SuperDroJson.encodeToString(AppSettings.serializer(), asked)
        assertEquals(asked, SuperDroJson.decodeFromString(AppSettings.serializer(), text))
    }
}
