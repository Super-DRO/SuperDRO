package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each field has a default, so an old file loads. An unknown
 * key and an unknown enum value do not stop the load.
 */
class OldJsonTest {

    @Test
    fun anEmptyObjectGivesTheDefaults() {
        val profile = SuperDroJson.decodeFromString(MachineProfile.serializer(), "{}")
        assertEquals("Mill", profile.name)
        assertEquals(MachineType.VerticalMill, profile.machineType)
        assertEquals(1, profile.workspaces.size)
        assertEquals("Default Workspace", profile.workspaces.single().name)
        assertEquals(profile.workspaces.single().id, profile.activeWorkspaceId)

        assertEquals(AppSettings().copy(), SuperDroJson.decodeFromString(AppSettings.serializer(), "{}"))
        assertEquals(TabState(), SuperDroJson.decodeFromString(TabState.serializer(), "{}"))
        assertEquals(DataOutputConfig(), SuperDroJson.decodeFromString(DataOutputConfig.serializer(), "{}"))
    }

    @Test
    fun anUnknownKeyIsIgnored() {
        val settings = SuperDroJson.decodeFromString(
            AppSettings.serializer(),
            """{"themeMode":"Dark","fieldOfAFutureVersion":42}""",
        )
        assertEquals(ThemeMode.Dark, settings.themeMode)
    }

    @Test
    fun anUnknownEnumValueGivesTheDefault() {
        val settings = SuperDroJson.decodeFromString(AppSettings.serializer(), """{"themeMode":"Light"}""")
        assertEquals(ThemeMode.System, settings.themeMode)
    }

    @Test
    fun aProfileFromAnOlderFileKeepsItsValues() {
        val profile = SuperDroJson.decodeFromString(
            MachineProfile.serializer(),
            """{"id":"p1","name":"Lathe","machineType":"Lathe","adapter":{"type":"bluetooth_spp","mac":"24:6F:28:A1:B2:C3"}}""",
        )
        assertEquals("p1", profile.id)
        assertEquals(MachineType.Lathe, profile.machineType)
        assertEquals(AdapterAddress.BluetoothSpp("24:6F:28:A1:B2:C3"), profile.adapter)
        assertNull(profile.adapterProfileId)
        assertTrue(profile.twoWayCommunication)
    }
}
