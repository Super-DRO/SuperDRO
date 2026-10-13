package org.superdro.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The fields of [AppSettings] that the settings area added: the key map flag, the DXF import
 * limits, and the version of the "What changed" text. An old file
 * with no such field loads with the defaults.
 */
class AppSettingsFieldsTest {

    @Test
    fun anOldFileWithNoNewFieldLoadsWithTheDefaults() {
        val old = """{"schemaVersion":1,"themeMode":"Dark","batteryExemptionAsked":true,"keyMappings":[]}"""
        val settings = SuperDroJson.decodeFromString(AppSettings.serializer(), old)
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertEquals(true, settings.batteryExemptionAsked)
        assertFalse(settings.keyMapCustomized)
        assertEquals(DxfImportLimits(), settings.dxfImportLimits)
        assertNull(settings.lastRunVersion)
        assertFalse(settings.whatChangedPending)
    }

    @Test
    fun theNewFieldsGoThroughTheJson() {
        val settings = AppSettings(
            keyMapCustomized = true,
            keyMappings = listOf(KeyMapping(keyCode = 7L, action = KeyAction.HoldAxis, axis = AxisId.Z)),
            dxfImportLimits = DxfImportLimits(maxEntities = 500),
            lastRunVersion = "26.41.1",
            whatChangedPending = true,
        )
        val text = SuperDroJson.encodeToString(AppSettings.serializer(), settings)
        assertEquals(settings, SuperDroJson.decodeFromString(AppSettings.serializer(), text))
    }

    @Test
    fun aKeyActionThatThisVersionDoesNotKnowGivesNone() {
        val text = """{"keyMappings":[{"keyCode":5,"action":"SomeLaterAction"}]}"""
        val settings = SuperDroJson.decodeFromString(AppSettings.serializer(), text)
        assertEquals(KeyAction.None, settings.keyMappings.single().action)
    }
}
